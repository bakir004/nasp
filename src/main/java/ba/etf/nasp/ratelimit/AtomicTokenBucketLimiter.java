package ba.etf.nasp.ratelimit;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lock-free token bucket. Each client's whole bucket state is one {@link AtomicLong}: the
 * monotonic time at which the bucket would have been empty. Available tokens follow from the
 * time elapsed since then, capped at capacity; consuming tokens moves that instant forward.
 * Updates use compare-and-set, so concurrent requests cannot spend the same tokens and no
 * thread ever blocks another.
 */
public final class AtomicTokenBucketLimiter implements RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int capacity;
    private final int windowSeconds;
    private final long nanosPerToken;
    private final long fullBucketNanos;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<String, AtomicLong> buckets = new ConcurrentHashMap<>();

    public AtomicTokenBucketLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    // Injectable monotonic time source keeps refill tests deterministic.
    AtomicTokenBucketLimiter(RateLimitProperties properties, LongSupplier nanoTime) {
        Objects.requireNonNull(properties, "properties");
        this.capacity = properties.capacityTokens();
        this.windowSeconds = properties.windowSeconds();
        // Rounding the refill interval up once keeps all later arithmetic exact: a full bucket
        // holds exactly `capacity` tokens, and each token arrives less than 1 ns late.
        this.nanosPerToken = ceilDiv(windowSeconds * NANOS_PER_SECOND, capacity);
        this.fullBucketNanos = nanosPerToken * capacity;
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
        AtomicLong state = buckets.computeIfAbsent(key, ignored -> new AtomicLong(now - fullBucketNanos));

        while (true) {
            long emptyAt = state.get();
            // A bucket idle for longer than a full refill is full, not over capacity.
            long base = Math.max(emptyAt, now - fullBucketNanos);
            long availableNanos = now - base;

            if (availableNanos < costNanos) {
                // Rejections leave the state untouched, so they cost nothing and never retry.
                long remaining = Math.max(0, availableNanos) / nanosPerToken;
                long retryAfter = ceilDiv(costNanos - availableNanos, NANOS_PER_SECOND);
                return new RateLimitDecision(false, remaining, capacity, windowSeconds, retryAfter);
            }

            long nextEmptyAt = base + costNanos;
            if (state.compareAndSet(emptyAt, nextEmptyAt)) {
                long remaining = (now - nextEmptyAt) / nanosPerToken;
                return new RateLimitDecision(true, remaining, capacity, windowSeconds, 0);
            }
            // Another request updated this bucket first; re-read its state and try again.
        }
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
