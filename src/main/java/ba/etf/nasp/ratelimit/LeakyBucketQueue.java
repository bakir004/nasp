package ba.etf.nasp.ratelimit;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Leaky bucket used as a queue: a traffic shaper. Each client's waiting requests sit in a
 * {@link RingBuffer}, and one request leaks out every {@code window / capacity}, so a burst
 * leaves the bucket at a steady rate instead of all at once. A request that finds the bucket
 * full is rejected.
 *
 * <p>The bucket holds the waiting requests plus the request that leaked most recently, which
 * occupies it until the next leak is due. A burst therefore admits exactly {@code capacity}
 * requests, as many as a full token bucket: one proceeds at once and the rest wait their turn.
 */
public final class LeakyBucketQueue implements RequestAdmitter {

    /** Runs a task after a delay; production code uses a scheduled executor. */
    @FunctionalInterface
    public interface Scheduler {
        void schedule(Runnable task, long delayNanos);
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int capacity;
    private final int windowSeconds;
    private final long leakIntervalNanos;
    private final RateLimitMetrics metrics;
    private final Scheduler scheduler;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LeakyBucketQueue(RateLimitProperties properties, RateLimitMetrics metrics,
            ScheduledExecutorService executor) {
        this(properties, metrics, (task, delayNanos) -> executor.schedule(task, delayNanos,
                TimeUnit.NANOSECONDS), System::nanoTime);
    }

    // Injectable scheduler and clock keep queue tests deterministic.
    LeakyBucketQueue(RateLimitProperties properties, RateLimitMetrics metrics, Scheduler scheduler,
            LongSupplier nanoTime) {
        Objects.requireNonNull(properties, "properties");
        this.capacity = properties.capacityTokens();
        this.windowSeconds = properties.windowSeconds();
        this.leakIntervalNanos = ceilDiv(windowSeconds * NANOS_PER_SECOND, capacity);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** One client's bucket. All fields are guarded by the bucket's monitor. */
    private final class Bucket {
        private final RingBuffer<CompletableFuture<Void>> waiting = new RingBuffer<>(capacity);
        private long nextLeakAt = Long.MIN_VALUE;
        private boolean leakScheduled;

        // The request that leaked last still drains until nextLeakAt.
        private int level(long now) {
            return waiting.size() + (now < nextLeakAt ? 1 : 0);
        }
    }

    @Override
    public Admission admit(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Client key must not be blank");
        }
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
        Admission admission;
        synchronized (bucket) {
            long now = nanoTime.getAsLong();
            int level = bucket.level(now);
            if (level >= capacity) {
                long untilLeak = Math.max(1, bucket.nextLeakAt - now);
                admission = Admission.immediate(new RateLimitDecision(false, 0, capacity,
                        windowSeconds, ceilDiv(untilLeak, NANOS_PER_SECOND)));
            } else if (level == 0) {
                // Empty bucket: the request leaks straight through.
                bucket.nextLeakAt = now + leakIntervalNanos;
                admission = Admission.immediate(new RateLimitDecision(true, capacity - 1, capacity,
                        windowSeconds, 0));
            } else {
                CompletableFuture<Void> released = new CompletableFuture<>();
                bucket.waiting.offer(released);
                if (!bucket.leakScheduled) {
                    bucket.leakScheduled = true;
                    scheduler.schedule(() -> leak(bucket), Math.max(0, bucket.nextLeakAt - now));
                }
                admission = new Admission(new RateLimitDecision(true, capacity - level - 1, capacity,
                        windowSeconds, 0), released);
            }
        }
        metrics.recordDecision(admission.decision());
        return admission;
    }

    // Releases the oldest waiting request and schedules the next leak while requests remain.
    private void leak(Bucket bucket) {
        CompletableFuture<Void> released;
        synchronized (bucket) {
            released = bucket.waiting.poll();
            // Timing the next leak from the actual release means a late timer never lets two
            // requests out closer together than the leak interval.
            bucket.nextLeakAt = nanoTime.getAsLong() + leakIntervalNanos;
            bucket.leakScheduled = !bucket.waiting.isEmpty();
            if (bucket.leakScheduled) {
                scheduler.schedule(() -> leak(bucket), leakIntervalNanos);
            }
        }
        // Completing outside the lock: the release resumes the request on another thread.
        released.complete(null);
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
