package ba.etf.nasp.ratelimit;

import ba.etf.nasp.ratelimit.RateLimiter.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TokenBucketLimiterTests {

    // 10 tokens per 20 s: one token refills every 2 s.
    private static final long REFILL = TimeUnit.SECONDS.toNanos(2);

    private final AtomicLong time = new AtomicLong();
    private final TokenBucketLimiter limiter = new TokenBucketLimiter(10, 20, time::get);

    @Test
    void startsFullAndRejectsWhenExhausted() {
        for (int i = 9; i >= 0; i--) {
            assertThat(limiter.tryAcquire("client")).isEqualTo(Decision.allowNow(i));
        }
        assertThat(limiter.tryAcquire("client")).isEqualTo(Decision.reject(2));
    }

    @Test
    void keepsClientsIndependent() {
        drain("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b")).isEqualTo(Decision.allowNow(9));
    }

    @Test
    void refillsAtBoundaryAndRoundsRetryUp() {
        drain("client");
        time.set(TimeUnit.MILLISECONDS.toNanos(1_500));
        assertThat(limiter.tryAcquire("client")).isEqualTo(Decision.reject(1));
        time.set(REFILL);
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
    }

    @Test
    void refillBoundaryIsExactToTheNanosecond() {
        drain("client");
        time.set(REFILL - 1);
        assertThat(limiter.tryAcquire("client")).isEqualTo(Decision.reject(1));
        time.set(REFILL);
        assertThat(limiter.tryAcquire("client")).isEqualTo(Decision.allowNow(0));
    }

    @Test
    void capsRefillAtCapacityAfterLongIdlePeriod() {
        drain("client");
        time.set(TimeUnit.DAYS.toNanos(30));
        assertThat(drain("client")).isEqualTo(10);
    }

    @Test
    void timeMovingBackwardDoesNotCreateTokens() {
        drain("client");
        time.set(-TimeUnit.SECONDS.toNanos(2));
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
        time.set(0);
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
        time.set(REFILL);
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
    }

    @Test
    void fullBucketHoldsCapacityWhenRefillIntervalIsFractional() {
        // 1 s / 3 tokens is not a whole number of nanoseconds.
        AtomicLong clock = new AtomicLong();
        TokenBucketLimiter thirds = new TokenBucketLimiter(3, 1, clock::get);
        for (int i = 2; i >= 0; i--) {
            assertThat(thirds.tryAcquire("client").remaining()).isEqualTo(i);
        }
        assertThat(thirds.tryAcquire("client").allowed()).isFalse();
        clock.set(TimeUnit.SECONDS.toNanos(1) + 2);
        for (int i = 0; i < 3; i++) {
            assertThat(thirds.tryAcquire("client").allowed()).isTrue();
        }
    }

    @Test
    void largestConfigurationDoesNotOverflow() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE / 2);
        TokenBucketLimiter large = new TokenBucketLimiter(Integer.MAX_VALUE, Integer.MAX_VALUE, clock::get);
        assertThat(large.tryAcquire("client")).isEqualTo(Decision.allowNow(Integer.MAX_VALUE - 1L));
        clock.addAndGet(TimeUnit.DAYS.toNanos(365));
        assertThat(large.tryAcquire("client")).isEqualTo(Decision.allowNow(Integer.MAX_VALUE - 1L));
    }

    @Test
    void rejectsInvalidInputWithoutConsumingTokens() {
        for (String key : new String[]{null, "", "  "}) {
            assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(key));
        }
        assertThat(drain("client")).isEqualTo(10);
        assertThatIllegalArgumentException().isThrownBy(() -> new TokenBucketLimiter(0, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> new TokenBucketLimiter(10, 0));
    }

    @Test
    void concurrentRequestsCannotOverspendCapacity() throws Exception {
        AtomicLong allowed = new AtomicLong();
        runConcurrently(16, 100, task -> () -> {
            if (limiter.tryAcquire("shared").allowed()) {
                allowed.incrementAndGet();
            }
            return null;
        });
        assertThat(allowed).hasValue(10);
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
            if (limiter.tryAcquire("client-" + client).allowed()) {
                allowed[client].incrementAndGet();
            }
            return null;
        });
        for (AtomicLong count : allowed) {
            assertThat(count).hasValue(10);
        }
    }

    @Test
    void concurrentRequestsDuringRefillNeverExceedEarnedTokens() throws Exception {
        long refillSteps = 50;
        AtomicLong allowed = new AtomicLong();
        runConcurrently(16, 20_000, task -> () -> {
            // One task in every 400 advances the clock by one token's refill interval.
            if (task % 400 == 0 && time.get() < refillSteps * REFILL) {
                time.addAndGet(REFILL);
            } else if (limiter.tryAcquire("shared").allowed()) {
                allowed.incrementAndGet();
            }
            return null;
        });
        long earned = 10 + time.get() / REFILL;
        assertThat(allowed.get()).isLessThanOrEqualTo(earned).isGreaterThan(10);
    }

    // Spends every available token of a client and returns how many there were.
    private int drain(String client) {
        int spent = 0;
        while (limiter.tryAcquire(client).allowed()) {
            spent++;
        }
        return spent;
    }

    /** Runs the created tasks on a thread pool, releasing them together to maximize contention. */
    private static void runConcurrently(int threads, int tasks, TaskFactory factory) throws Exception {
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
    private interface TaskFactory {
        Callable<Void> create(int task);
    }
}
