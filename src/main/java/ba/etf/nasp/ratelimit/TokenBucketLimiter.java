package ba.etf.nasp.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lock-free token bucket. Each client's whole bucket state is one {@link AtomicLong}: the
 * monotonic time at which the bucket would have been empty. Available tokens follow from the
 * time elapsed since then, capped at capacity; spending a token moves that instant forward.
 * Updates use compare-and-set, so concurrent requests cannot spend the same token and no thread
 * ever blocks another.
 */
public final class TokenBucketLimiter implements RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long nanosPerToken;
    private final long fullBucketNanos;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<String, AtomicLong> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(int capacity, int windowSeconds) {
        this(capacity, windowSeconds, System::nanoTime);
    }

    // Injectable monotonic time source keeps refill tests deterministic.
    TokenBucketLimiter(int capacity, int windowSeconds, LongSupplier nanoTime) {
        if (capacity <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("Capacity and window must be positive");
        }
        // Rounding the refill interval up once keeps all later arithmetic exact: a full bucket
        // holds exactly `capacity` tokens, and each token arrives less than 1 ns late.
        this.nanosPerToken = ceilDiv(windowSeconds * NANOS_PER_SECOND, capacity);
        this.fullBucketNanos = nanosPerToken * capacity;
        this.nanoTime = nanoTime;
    }

    @Override
    public Decision tryAcquire(String client) {
        if (client == null || client.isBlank()) {
            throw new IllegalArgumentException("Client key must not be blank");
        }
        long now = nanoTime.getAsLong();
        // A new client's bucket was empty a full refill ago, so it starts full.
        AtomicLong state = buckets.computeIfAbsent(client, ignored -> new AtomicLong(now - fullBucketNanos));

        while (true) {
            long emptyAt = state.get();
            // A bucket idle for longer than a full refill is full, not over capacity.
            long base = Math.max(emptyAt, now - fullBucketNanos);
            long availableNanos = now - base;

            if (availableNanos < nanosPerToken) {
                // Rejections leave the state untouched, so they cost nothing and never retry.
                return Decision.reject(ceilDiv(nanosPerToken - availableNanos, NANOS_PER_SECOND));
            }

            long nextEmptyAt = base + nanosPerToken;
            if (state.compareAndSet(emptyAt, nextEmptyAt)) {
                return Decision.allowNow((now - nextEmptyAt) / nanosPerToken);
            }
            // Another request updated this bucket first; re-read its state and try again.
        }
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
