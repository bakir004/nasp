package ba.etf.nasp.ratelimit;

import ba.etf.nasp.ratelimit.RateLimiter.Decision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LeakyBucketLimiterTests {

    // 4 requests per 2 s: one leaks out every 500 ms.
    private static final long LEAK = TimeUnit.MILLISECONDS.toNanos(500);

    private final AtomicLong time = new AtomicLong();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final LeakyBucketLimiter limiter = new LeakyBucketLimiter(4, 2, scheduler, time::get);

    @Test
    void emptyBucketLetsTheFirstRequestThroughAtOnce() {
        Decision first = limiter.tryAcquire("client");
        assertThat(first).isEqualTo(Decision.allowNow(3));
        assertThat(released(first)).isTrue();
    }

    @Test
    void burstLeavesOneRequestPerLeakIntervalInArrivalOrder() {
        List<Decision> burst = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            burst.add(limiter.tryAcquire("client"));
        }
        assertThat(burst).allMatch(Decision::allowed);
        assertThat(burst).extracting(Decision::remaining).containsExactly(3L, 2L, 1L, 0L);
        assertThat(burst).extracting(LeakyBucketLimiterTests::released).containsExactly(true, false, false, false);

        scheduler.advanceTo(LEAK - 1);
        assertThat(burst).extracting(LeakyBucketLimiterTests::released).containsExactly(true, false, false, false);
        scheduler.advanceTo(LEAK);
        assertThat(burst).extracting(LeakyBucketLimiterTests::released).containsExactly(true, true, false, false);
        scheduler.advanceTo(2 * LEAK);
        assertThat(burst).extracting(LeakyBucketLimiterTests::released).containsExactly(true, true, true, false);
        scheduler.advanceTo(3 * LEAK);
        assertThat(burst).extracting(LeakyBucketLimiterTests::released).containsOnly(true);
    }

    @Test
    void fullBucketRejectsWithTimeUntilTheNextLeak() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("client");
        }
        assertThat(limiter.tryAcquire("client")).isEqualTo(Decision.reject(1));

        // One leak frees one place in the bucket.
        scheduler.advanceTo(LEAK);
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
    }

    @Test
    void requestArrivingBeforeTheLeakIsDueWaitsForIt() {
        limiter.tryAcquire("client");
        time.set(LEAK / 2);
        Decision second = limiter.tryAcquire("client");
        assertThat(released(second)).isFalse();
        scheduler.advanceTo(LEAK);
        assertThat(released(second)).isTrue();
    }

    @Test
    void drainedBucketPassesRequestsImmediatelyAgain() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("client");
        }
        scheduler.advanceTo(3 * LEAK);
        time.set(4 * LEAK);
        Decision later = limiter.tryAcquire("client");
        assertThat(later.remaining()).isEqualTo(3);
        assertThat(released(later)).isTrue();
    }

    @Test
    void releaseTimeIsFixedWhenTheRequestArrives() {
        limiter.tryAcquire("client");
        time.set(LEAK / 5);
        limiter.tryAcquire("client");
        time.set(LEAK / 2);
        limiter.tryAcquire("client");
        // Arrival times do not matter while the bucket is busy: each leaves one interval apart.
        assertThat(scheduler.dueTimes()).containsExactly(LEAK, 2 * LEAK);
    }

    @Test
    void concurrentRequestsCannotOverfillTheBucket() throws Exception {
        LeakyBucketLimiter shared = new LeakyBucketLimiter(4, 2, (task, delayNanos) -> { }, time::get);
        AtomicLong allowed = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 400; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    if (shared.tryAcquire("shared").allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(allowed).hasValue(4);
    }

    @Test
    void clientsHaveSeparateBuckets() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        Decision other = limiter.tryAcquire("b");
        assertThat(other.allowed()).isTrue();
        assertThat(released(other)).isTrue();
    }

    @Test
    void rejectsInvalidInput() {
        for (String key : new String[]{null, "", "  "}) {
            assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(key));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new LeakyBucketLimiter(0, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new LeakyBucketLimiter(4, 0));
    }

    private static boolean released(Decision decision) {
        return decision.ready().isDone();
    }

    /** Runs scheduled tasks when the test moves the shared clock forward. */
    private final class ManualScheduler implements LeakyBucketLimiter.Scheduler {

        private record Task(long dueAt, Runnable task) {
        }

        private final List<Task> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayNanos) {
            tasks.add(new Task(time.get() + delayNanos, task));
        }

        List<Long> dueTimes() {
            return tasks.stream().map(Task::dueAt).toList();
        }

        // Runs every task due by the given time, earliest first, each at its own due time.
        void advanceTo(long until) {
            while (true) {
                Task next = tasks.stream().min(Comparator.comparingLong(Task::dueAt)).orElse(null);
                if (next == null || next.dueAt() > until) {
                    break;
                }
                tasks.remove(next);
                time.set(next.dueAt());
                next.task().run();
            }
            time.set(Math.max(time.get(), until));
        }
    }
}
