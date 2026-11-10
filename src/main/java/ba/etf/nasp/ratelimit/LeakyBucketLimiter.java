package ba.etf.nasp.ratelimit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Leaky bucket used as a queue: a traffic shaper. One request leaks out of each client's bucket
 * every {@code window / capacity}, so a burst leaves at a steady rate instead of all at once.
 * A request that finds the bucket full is rejected.
 *
 * <p>Because requests leak at a fixed rate, a request's release time is known the moment it
 * arrives: one leak interval after the previous request's. Each client's whole bucket is
 * therefore one {@link AtomicLong}, the time at which the bucket will have drained, and no
 * queue is stored. A waiting request is released by its own timer at its release time.
 *
 * <p>Every request occupies the bucket for one leak interval after its release, so a burst
 * admits exactly {@code capacity} requests, as many as a full token bucket: one proceeds at
 * once and the rest wait their turn.
 */
public final class LeakyBucketLimiter implements RateLimiter, AutoCloseable {

    /** Runs a task after a delay. */
    @FunctionalInterface
    interface Scheduler {
        void schedule(Runnable task, long delayNanos);
    }

    // The production scheduler: one daemon thread that releases every waiting request.
    private record ExecutorScheduler(ScheduledExecutorService executor) implements Scheduler {
        @Override
        public void schedule(Runnable task, long delayNanos) {
            executor.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
        }
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int capacity;
    private final long leakIntervalNanos;
    // The longest an admitted request may wait: behind capacity - 1 others.
    private final long maxWaitNanos;
    private final Scheduler scheduler;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<String, AtomicLong> buckets = new ConcurrentHashMap<>();

    public LeakyBucketLimiter(int capacity, int windowSeconds) {
        this(capacity, windowSeconds, new ExecutorScheduler(newLeakThread()), System::nanoTime);
    }

    private static ScheduledExecutorService newLeakThread() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "leaky-bucket-leak");
            thread.setDaemon(true);
            return thread;
        });
    }

    // Injectable scheduler and clock keep queue tests deterministic.
    LeakyBucketLimiter(int capacity, int windowSeconds, Scheduler scheduler, LongSupplier nanoTime) {
        if (capacity <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("Capacity and window must be positive");
        }
        this.capacity = capacity;
        this.leakIntervalNanos = ceilDiv(windowSeconds * NANOS_PER_SECOND, capacity);
        this.maxWaitNanos = leakIntervalNanos * (capacity - 1);
        this.scheduler = scheduler;
        this.nanoTime = nanoTime;
    }

    @Override
    public Decision tryAcquire(String client) {
        if (client == null || client.isBlank()) {
            throw new IllegalArgumentException("Client key must not be blank");
        }
        long now = nanoTime.getAsLong();
        // A new client's bucket drained long ago, so it starts empty.
        AtomicLong state = buckets.computeIfAbsent(client, ignored -> new AtomicLong(Long.MIN_VALUE));

        while (true) {
            long drainedAt = state.get();
            // The request leaves once everything ahead of it has leaked, or now if nothing is.
            long releaseAt = Math.max(now, drainedAt);
            long wait = releaseAt - now;

            if (wait > maxWaitNanos) {
                // Full: capacity requests already occupy the bucket. Room appears when the
                // wait falls back to maxWaitNanos. Rejections leave the state untouched.
                return Decision.reject(ceilDiv(wait - maxWaitNanos, NANOS_PER_SECOND));
            }

            long nextDrainedAt = releaseAt + leakIntervalNanos;
            if (state.compareAndSet(drainedAt, nextDrainedAt)) {
                long remaining = capacity - ceilDiv(nextDrainedAt - now, leakIntervalNanos);
                if (wait == 0) {
                    return Decision.allowNow(remaining);
                }
                CompletableFuture<Void> ready = new CompletableFuture<>();
                scheduler.schedule(() -> ready.complete(null), wait);
                return Decision.allowLater(remaining, ready);
            }
            // Another request joined this bucket first; re-read its state and try again.
        }
    }

    /** Stops the leak timer; Spring calls this when the application shuts down. */
    @Override
    public void close() {
        if (scheduler instanceof ExecutorScheduler production) {
            production.executor().shutdownNow();
        }
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
