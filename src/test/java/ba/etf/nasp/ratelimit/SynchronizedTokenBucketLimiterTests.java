package ba.etf.nasp.ratelimit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SynchronizedTokenBucketLimiterTests {

    private final AtomicLong time = new AtomicLong();
    private final RateLimiter limiter = new SynchronizedTokenBucketLimiter(
            new RateLimitProperties(10, 20, false), time::get);

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
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
