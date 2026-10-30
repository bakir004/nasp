package ba.etf.nasp.ratelimit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Behavior every limiter must share: both token buckets and the leaky bucket meter make the same
 * decisions for the same capacity and window. Subclasses supply the implementation.
 */
abstract class RateLimiterContractTests {

    protected final AtomicLong time = new AtomicLong();
    protected final RateLimitMetrics metrics = new RateLimitMetrics();
    protected final RateLimiter limiter =
            createLimiter(new RateLimitProperties(10, 20, false), metrics, time::get);

    protected abstract RateLimiter createLimiter(RateLimitProperties properties,
            RateLimitMetrics metrics, LongSupplier nanoTime);

    @Test
    void startsFullAndRejectsWhenExhausted() {
        for (int i = 9; i >= 0; i--) {
            var decision = limiter.tryAcquire("client", 1);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remainingTokens()).isEqualTo(i);
            assertThat(decision.retryAfterSeconds()).isZero();
        }
        assertThat(limiter.tryAcquire("client", 1))
                .isEqualTo(new RateLimitDecision(false, 0, 10, 20, 2));
    }

    @Test
    void supportsWeightedRequestsWithoutChargingRejections() {
        assertThat(limiter.tryAcquire("client", 7).remainingTokens()).isEqualTo(3);
        assertThat(limiter.tryAcquire("client", 4).allowed()).isFalse();
        assertThat(limiter.tryAcquire("client", 3).allowed()).isTrue();
    }

    @Test
    void keepsClientsIndependent() {
        limiter.tryAcquire("a", 10);
        assertThat(limiter.tryAcquire("a", 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 10).allowed()).isTrue();
    }

    @Test
    void refillsAtBoundaryAndRoundsRetryUp() {
        limiter.tryAcquire("client", 10);
        time.set(1_500_000_000L);
        assertThat(limiter.tryAcquire("client", 1))
                .isEqualTo(new RateLimitDecision(false, 0, 10, 20, 1));
        time.set(2_000_000_000L);
        assertThat(limiter.tryAcquire("client", 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("client", 1).allowed()).isFalse();
    }

    @Test
    void capsRefillAtCapacityAfterLongIdlePeriod() {
        limiter.tryAcquire("client", 10);
        time.set(TimeUnit.DAYS.toNanos(30));
        assertThat(limiter.tryAcquire("client", 10).allowed()).isTrue();
        assertThat(limiter.tryAcquire("client", 1).allowed()).isFalse();
    }

    @Test
    void timeMovingBackwardDoesNotCreateTokens() {
        limiter.tryAcquire("client", 10);
        time.set(-TimeUnit.SECONDS.toNanos(2));
        assertThat(limiter.tryAcquire("client", 1).allowed()).isFalse();
        time.set(0);
        assertThat(limiter.tryAcquire("client", 1).allowed()).isFalse();
        time.set(TimeUnit.SECONDS.toNanos(2));
        assertThat(limiter.tryAcquire("client", 1).allowed()).isTrue();
    }

    @Test
    void rejectsInvalidKeysAndCostsWithoutConsumingTokens() {
        for (String key : new String[]{null, "", "  "}) {
            assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(key, 1));
        }
        for (long cost : new long[]{-1, 0, 11, Long.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire("client", cost));
        }
        assertThat(limiter.tryAcquire("client", 10).allowed()).isTrue();
        assertThat(metrics.snapshot()).isEqualTo(new RateLimitMetrics.Snapshot(1, 0, 0));
    }

    @Test
    void recordsEveryDecisionByOutcome() {
        assertThat(metrics.snapshot()).isEqualTo(new RateLimitMetrics.Snapshot(0, 0, 0));
        limiter.tryAcquire("a", 7);
        limiter.tryAcquire("a", 4);
        limiter.tryAcquire("a", 3);
        limiter.tryAcquire("a", 1);
        limiter.tryAcquire("b", 1);
        assertThat(metrics.snapshot()).isEqualTo(new RateLimitMetrics.Snapshot(3, 2, 0));
    }

    @Test
    void concurrentRequestsCannotOverspendCapacity() throws Exception {
        var executor = Executors.newFixedThreadPool(16);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                results.add(executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start signal timed out");
                    }
                    return limiter.tryAcquire("shared", 1).allowed();
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(10);
            assertThat(metrics.snapshot().allowedRequests()).isEqualTo(10);
            assertThat(metrics.snapshot().rejectedRequests()).isEqualTo(90);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentClientsEachReceiveExactlyCapacity() throws Exception {
        int clients = 8;
        AtomicLong[] allowed = new AtomicLong[clients];
        for (int i = 0; i < clients; i++) {
            allowed[i] = new AtomicLong();
        }
        runConcurrently(32, 400, task -> () -> {
            int client = task % clients;
            if (limiter.tryAcquire("client-" + client, 1).allowed()) {
                allowed[client].incrementAndGet();
            }
            return null;
        });
        for (AtomicLong count : allowed) {
            assertThat(count).hasValue(10);
        }
    }

    @Test
    void concurrentWeightedRequestsCannotOverspendCapacity() throws Exception {
        AtomicLong spent = new AtomicLong();
        runConcurrently(16, 100, task -> () -> {
            long cost = task % 3 + 1;
            if (limiter.tryAcquire("shared", cost).allowed()) {
                spent.addAndGet(cost);
            }
            return null;
        });
        // 33 cost-1 requests outnumber the 10 tokens, so the bucket always ends exactly empty.
        assertThat(spent).hasValue(10);
    }

    /** For lock-free limiters: concurrent allowed requests on one bucket must produce retries. */
    protected void assertCasRetriesObservedUnderContention() throws Exception {
        // Retries need two threads to race on one bucket, which the scheduler may not produce
        // in a single round, so the test repeats until it observes one.
        int threads = 16;
        int perThread = 2_000;
        for (int round = 0; round < 20; round++) {
            RateLimitMetrics roundMetrics = new RateLimitMetrics();
            RateLimiter large = createLimiter(
                    new RateLimitProperties(threads * perThread, 60, false), roundMetrics, () -> 0);
            runConcurrently(threads, threads, task -> () -> {
                for (int i = 0; i < perThread; i++) {
                    large.tryAcquire("shared", 1);
                }
                return null;
            });
            var snapshot = roundMetrics.snapshot();
            assertThat(snapshot.allowedRequests()).isEqualTo((long) threads * perThread);
            assertThat(snapshot.rejectedRequests()).isZero();
            if (snapshot.casRetries() > 0) {
                return;
            }
        }
        throw new AssertionError("No CAS retry observed in 20 contended rounds");
    }

    /** Runs the created tasks on a thread pool, releasing them together to maximize contention. */
    protected static void runConcurrently(int threads, int tasks, TaskFactory factory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> results = new ArrayList<>();
        try {
            for (int i = 0; i < tasks; i++) {
                Callable<Void> task = factory.create(i);
                results.add(executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start signal timed out");
                    }
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Void> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @FunctionalInterface
    protected interface TaskFactory {
        Callable<Void> create(int task);
    }
}
