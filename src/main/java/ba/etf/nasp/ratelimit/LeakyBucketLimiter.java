package ba.etf.nasp.ratelimit;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Lock-free leaky bucket used as a meter. Each request pours its cost into the bucket, the
 * bucket drains at {@code capacity / window} tokens per second, and a request is allowed only
 * if it fits without overflowing. Each client's state is an immutable {@link Level} swapped
 * with compare-and-set, so the two fields always change together without locks or bit packing.
 */
public final class LeakyBucketLimiter implements RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Level EMPTY = new Level(0, Long.MIN_VALUE);

    private final int capacity;
    private final int windowSeconds;
    private final long nanosPerToken;
    private final long capacityNanos;
    private final RateLimitMetrics metrics;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<String, AtomicReference<Level>> buckets = new ConcurrentHashMap<>();

    public LeakyBucketLimiter(RateLimitProperties properties, RateLimitMetrics metrics) {
        this(properties, metrics, System::nanoTime);
    }

    // Injectable monotonic time source keeps drain tests deterministic.
    LeakyBucketLimiter(RateLimitProperties properties, RateLimitMetrics metrics,
            LongSupplier nanoTime) {
        Objects.requireNonNull(properties, "properties");
        this.capacity = properties.capacityTokens();
        this.windowSeconds = properties.windowSeconds();
        // The level is measured in nanoseconds of draining. Rounding one token's drain time up
        // once keeps all later arithmetic exact, as in the atomic token bucket.
        this.nanosPerToken = ceilDiv(windowSeconds * NANOS_PER_SECOND, capacity);
        this.capacityNanos = nanosPerToken * capacity;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public RateLimitDecision tryAcquire(String key, long costTokens) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Client key must not be blank");
        }
        if (costTokens <= 0 || costTokens > capacity) {
            throw new IllegalArgumentException("Cost must be positive and no greater than capacity");
        }

        long costNanos = costTokens * nanosPerToken;
        long now = nanoTime.getAsLong();
        AtomicReference<Level> state =
                buckets.computeIfAbsent(key, ignored -> new AtomicReference<>(EMPTY));

        while (true) {
            Level current = state.get();
            long drained = current.drainedAt(now);

            if (drained + costNanos > capacityNanos) {
                // Rejections leave the state untouched, so they cost nothing and never retry.
                long remaining = (capacityNanos - drained) / nanosPerToken;
                long retryAfter = ceilDiv(drained + costNanos - capacityNanos, NANOS_PER_SECOND);
                return record(new RateLimitDecision(false, remaining, capacity, windowSeconds,
                        retryAfter));
            }

            // A clock that moved backward must not let the same interval drain twice.
            Level next = new Level(drained + costNanos, Math.max(now, current.updatedAt()));
            if (state.compareAndSet(current, next)) {
                long remaining = (capacityNanos - next.levelNanos()) / nanosPerToken;
                return record(new RateLimitDecision(true, remaining, capacity, windowSeconds, 0));
            }
            // Another request replaced this bucket's level first; re-read it and try again.
            metrics.recordCasRetry();
        }
    }

    private RateLimitDecision record(RateLimitDecision decision) {
        metrics.recordDecision(decision);
        return decision;
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    /** Bucket contents in nanoseconds of draining, as of {@code updatedAt}. */
    private record Level(long levelNanos, long updatedAt) {

        long drainedAt(long now) {
            if (levelNanos == 0 || now <= updatedAt) {
                return levelNanos;
            }
            return Math.max(0, levelNanos - (now - updatedAt));
        }
    }
}
