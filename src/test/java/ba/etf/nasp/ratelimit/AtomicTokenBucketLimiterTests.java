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

class AtomicTokenBucketLimiterTests extends TokenBucketLimiterContractTests {

    @Override
    protected RateLimiter createLimiter(RateLimitProperties properties, LongSupplier nanoTime) {
        return new AtomicTokenBucketLimiter(properties, nanoTime);
    }

    @Test
    void refillBoundaryIsExactToTheNanosecond() {
        limiter.tryAcquire("client", 10);
        time.set(TimeUnit.SECONDS.toNanos(2) - 1);
        assertThat(limiter.tryAcquire("client", 1))
                .isEqualTo(new RateLimitDecision(false, 0, 10, 20, 1));
        time.set(TimeUnit.SECONDS.toNanos(2));
        assertThat(limiter.tryAcquire("client", 1))
                .isEqualTo(new RateLimitDecision(true, 0, 10, 20, 0));
    }

    @Test
    void fullBucketHoldsCapacityWhenRefillIntervalIsFractional() {
        // 1 s / 3 tokens is not a whole number of nanoseconds.
        AtomicLong clock = new AtomicLong();
        RateLimiter thirds = createLimiter(new RateLimitProperties(3, 1, false), clock::get);
        for (int i = 2; i >= 0; i--) {
            assertThat(thirds.tryAcquire("client", 1).remainingTokens()).isEqualTo(i);
        }
        assertThat(thirds.tryAcquire("client", 1).allowed()).isFalse();
        clock.set(TimeUnit.SECONDS.toNanos(1) + 2);
        assertThat(thirds.tryAcquire("client", 3).allowed()).isTrue();
    }

    @Test
    void largestConfigurationDoesNotOverflow() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE / 2);
        RateLimiter large = createLimiter(
                new RateLimitProperties(Integer.MAX_VALUE, Integer.MAX_VALUE, false), clock::get);
        assertThat(large.tryAcquire("client", Integer.MAX_VALUE))
                .isEqualTo(new RateLimitDecision(true, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
        assertThat(large.tryAcquire("client", 1).allowed()).isFalse();
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

    @Test
    void concurrentRequestsDuringRefillNeverExceedEarnedTokens() throws Exception {
        long refillSteps = 50;
        AtomicLong allowed = new AtomicLong();
        runConcurrently(16, 20_000, task -> () -> {
            // One task in every 400 advances the clock by one token's refill interval.
            if (task % 400 == 0 && time.get() < refillSteps * TimeUnit.SECONDS.toNanos(2)) {
                time.addAndGet(TimeUnit.SECONDS.toNanos(2));
            } else if (limiter.tryAcquire("shared", 1).allowed()) {
                allowed.incrementAndGet();
            }
            return null;
        });
        long earned = 10 + time.get() / TimeUnit.SECONDS.toNanos(2);
        assertThat(allowed.get()).isLessThanOrEqualTo(earned).isGreaterThan(10);
    }

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
