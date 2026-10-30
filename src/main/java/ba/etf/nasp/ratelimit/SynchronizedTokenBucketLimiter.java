package ba.etf.nasp.ratelimit;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Simple single-JVM baseline: one monitor protects all client buckets. */
public final class SynchronizedTokenBucketLimiter implements RateLimiter {

    private final int capacity;
    private final int windowSeconds;
    private final RateLimitMetrics metrics;
    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public SynchronizedTokenBucketLimiter(RateLimitProperties properties, RateLimitMetrics metrics) {
        this(properties, metrics, System::nanoTime);
    }

    // Injectable monotonic time source keeps refill tests deterministic.
    SynchronizedTokenBucketLimiter(RateLimitProperties properties, RateLimitMetrics metrics,
            LongSupplier nanoTime) {
        Objects.requireNonNull(properties, "properties");
        this.capacity = properties.capacityTokens();
        this.windowSeconds = properties.windowSeconds();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public synchronized RateLimitDecision tryAcquire(String key, long costTokens) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Client key must not be blank");
        }
        if (costTokens <= 0 || costTokens > capacity) {
            throw new IllegalArgumentException("Cost must be positive and no greater than capacity");
        }

        long now = nanoTime.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, now));
        long elapsed = now - bucket.lastRefillNanos;
        if (elapsed > 0) {
            double refill = (elapsed / 1_000_000_000.0) * capacity / windowSeconds;
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.lastRefillNanos = now;
        }

        boolean allowed = bucket.tokens >= costTokens;
        long retryAfter = 0;
        if (allowed) {
            bucket.tokens -= costTokens;
        } else {
            retryAfter = (long) Math.ceil((costTokens - bucket.tokens) * windowSeconds / capacity);
        }
        var decision = new RateLimitDecision(allowed, (long) bucket.tokens, capacity,
                windowSeconds, retryAfter);
        metrics.recordDecision(decision);
        return decision;
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}
