package ba.etf.nasp.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicTokenBucketLimiterTests extends RateLimiterContractTests {

    @Override
    protected RateLimiter createLimiter(RateLimitProperties properties, RateLimitMetrics metrics,
            LongSupplier nanoTime) {
        return new AtomicTokenBucketLimiter(properties, metrics, nanoTime);
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
        RateLimiter thirds = createLimiter(new RateLimitProperties(3, 1, false),
                new RateLimitMetrics(), clock::get);
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
                new RateLimitProperties(Integer.MAX_VALUE, Integer.MAX_VALUE, false),
                new RateLimitMetrics(), clock::get);
        assertThat(large.tryAcquire("client", Integer.MAX_VALUE))
                .isEqualTo(new RateLimitDecision(true, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
        assertThat(large.tryAcquire("client", 1).allowed()).isFalse();
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
        assertThat(metrics.snapshot().allowedRequests()).isEqualTo(allowed.get());
    }

    @Test
    void contendedUpdatesAreCountedAsCasRetries() throws Exception {
        assertCasRetriesObservedUnderContention();
    }

}
