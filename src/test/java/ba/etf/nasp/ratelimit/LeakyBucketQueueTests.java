package ba.etf.nasp.ratelimit;

import ba.etf.nasp.ratelimit.RequestAdmitter.Admission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LeakyBucketQueueTests {

    // 4 requests per 2 s: one leaks out every 500 ms.
    private static final long LEAK = TimeUnit.MILLISECONDS.toNanos(500);

    private final AtomicLong time = new AtomicLong();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final RateLimitMetrics metrics = new RateLimitMetrics();
    private final LeakyBucketQueue queue =
            new LeakyBucketQueue(new RateLimitProperties(4, 2, false), metrics, scheduler, time::get);

    @Test
    void emptyBucketLetsTheFirstRequestThroughAtOnce() {
        Admission first = queue.admit("client");
        assertThat(first.decision()).isEqualTo(new RateLimitDecision(true, 3, 4, 2, 0));
        assertThat(released(first)).isTrue();
    }

    @Test
    void burstLeavesOneRequestPerLeakIntervalInArrivalOrder() {
        List<Admission> burst = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            burst.add(queue.admit("client"));
        }
        assertThat(burst).allMatch(a -> a.decision().allowed());
        assertThat(burst).extracting(a -> a.decision().remainingTokens()).containsExactly(3L, 2L, 1L, 0L);
        assertThat(burst).extracting(LeakyBucketQueueTests::released).containsExactly(true, false, false, false);

        scheduler.advanceTo(LEAK - 1);
        assertThat(burst).extracting(LeakyBucketQueueTests::released).containsExactly(true, false, false, false);
        scheduler.advanceTo(LEAK);
        assertThat(burst).extracting(LeakyBucketQueueTests::released).containsExactly(true, true, false, false);
        scheduler.advanceTo(2 * LEAK);
        assertThat(burst).extracting(LeakyBucketQueueTests::released).containsExactly(true, true, true, false);
        scheduler.advanceTo(3 * LEAK);
        assertThat(burst).extracting(LeakyBucketQueueTests::released).containsOnly(true);
    }

    @Test
    void fullBucketRejectsWithTimeUntilTheNextLeak() {
        for (int i = 0; i < 4; i++) {
            queue.admit("client");
        }
        Admission overflow = queue.admit("client");
        assertThat(overflow.decision()).isEqualTo(new RateLimitDecision(false, 0, 4, 2, 1));
        assertThat(released(overflow)).isFalse();

        // One leak frees one place in the bucket.
        scheduler.advanceTo(LEAK);
        assertThat(queue.admit("client").decision().allowed()).isTrue();
        assertThat(queue.admit("client").decision().allowed()).isFalse();
    }

    @Test
    void requestArrivingBeforeTheLeakIsDueWaitsForIt() {
        queue.admit("client");
        time.set(LEAK / 2);
        Admission second = queue.admit("client");
        assertThat(released(second)).isFalse();
        scheduler.advanceTo(LEAK);
        assertThat(released(second)).isTrue();
    }

    @Test
    void drainedBucketPassesRequestsImmediatelyAgain() {
        for (int i = 0; i < 4; i++) {
            queue.admit("client");
        }
        scheduler.advanceTo(3 * LEAK);
        time.set(4 * LEAK);
        Admission later = queue.admit("client");
        assertThat(later.decision().remainingTokens()).isEqualTo(3);
        assertThat(released(later)).isTrue();
    }

    @Test
    void lateTimerNeverReleasesTwoRequestsCloserThanTheInterval() {
        for (int i = 0; i < 3; i++) {
            queue.admit("client");
        }
        // The timer fires 300 ms late; the following leak is still a full interval after it.
        scheduler.runDueAt(LEAK + TimeUnit.MILLISECONDS.toNanos(300));
        assertThat(scheduler.nextDueAt()).isEqualTo(2 * LEAK + TimeUnit.MILLISECONDS.toNanos(300));
    }

    @Test
    void clientsHaveSeparateBuckets() {
        for (int i = 0; i < 4; i++) {
            queue.admit("a");
        }
        assertThat(queue.admit("a").decision().allowed()).isFalse();
        Admission other = queue.admit("b");
        assertThat(other.decision().allowed()).isTrue();
        assertThat(released(other)).isTrue();
    }

    @Test
    void recordsDecisionsInMetrics() {
        for (int i = 0; i < 6; i++) {
            queue.admit("client");
        }
        assertThat(metrics.snapshot()).isEqualTo(new RateLimitMetrics.Snapshot(4, 2, 0));
    }

    @Test
    void rejectsBlankKeys() {
        for (String key : new String[]{null, "", "  "}) {
            assertThatIllegalArgumentException().isThrownBy(() -> queue.admit(key));
        }
    }

    private static boolean released(Admission admission) {
        return admission.released().toCompletableFuture().isDone();
    }

    /** Runs scheduled tasks when the test moves the shared clock forward. */
    private final class ManualScheduler implements LeakyBucketQueue.Scheduler {

        private record Task(long dueAt, Runnable task) {
        }

        private final List<Task> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayNanos) {
            tasks.add(new Task(time.get() + delayNanos, task));
        }

        long nextDueAt() {
            return tasks.stream().mapToLong(Task::dueAt).min().orElseThrow();
        }

        // Runs every task due by the given time, each at its own due time.
        void advanceTo(long until) {
            while (!tasks.isEmpty() && nextDueAt() <= until) {
                runDueAt(nextDueAt());
            }
            time.set(Math.max(time.get(), until));
        }

        // Runs the earliest task as if its timer fired at the given (possibly late) time.
        void runDueAt(long at) {
            Task next = tasks.stream().min(Comparator.comparingLong(Task::dueAt)).orElseThrow();
            tasks.remove(next);
            time.set(at);
            next.task().run();
        }
    }
}
