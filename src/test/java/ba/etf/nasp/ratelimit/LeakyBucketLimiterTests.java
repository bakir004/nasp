package ba.etf.nasp.ratelimit;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeakyBucketLimiterTests extends RateLimiterContractTests {

    @Override
    protected RateLimiter createLimiter(RateLimitProperties properties, RateLimitMetrics metrics,
            LongSupplier nanoTime) {
        return new LeakyBucketLimiter(properties, metrics, nanoTime);
    }

    @Test
    void drainsContinuouslyBetweenRequests() {
        // 10 tokens per 20 s drains half a token per second.
        assertThat(limiter.tryAcquire("client", 4).remainingTokens()).isEqualTo(6);
        time.set(TimeUnit.SECONDS.toNanos(4));
        assertThat(limiter.tryAcquire("client", 8))
                .isEqualTo(new RateLimitDecision(true, 0, 10, 20, 0));
        time.set(TimeUnit.SECONDS.toNanos(5));
        assertThat(limiter.tryAcquire("client", 2))
                .isEqualTo(new RateLimitDecision(false, 0, 10, 20, 3));
    }

    @Test
    void drainBoundaryIsExactToTheNanosecond() {
        limiter.tryAcquire("client", 10);
        time.set(TimeUnit.SECONDS.toNanos(2) - 1);
        assertThat(limiter.tryAcquire("client", 1).allowed()).isFalse();
        time.set(TimeUnit.SECONDS.toNanos(2));
        assertThat(limiter.tryAcquire("client", 1).allowed()).isTrue();
    }

    @Test
    void emptyBucketAcceptsCapacityWhenDrainIntervalIsFractional() {
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
    void makesTheSameDecisionsAsTheAtomicTokenBucket() {
        // A leaky bucket meter's level is the token bucket's spent quota: level = capacity - tokens.
        Random random = new Random(42);
        for (var properties : new RateLimitProperties[]{new RateLimitProperties(10, 20, false),
                new RateLimitProperties(3, 1, false), new RateLimitProperties(7, 60, false)}) {
            // System.nanoTime has an arbitrary origin, possibly negative.
            AtomicLong clock = new AtomicLong(random.nextLong(1L << 60) - (1L << 59));
            RateLimiter leaky = createLimiter(properties, new RateLimitMetrics(), clock::get);
            RateLimiter tokens = new AtomicTokenBucketLimiter(properties, new RateLimitMetrics(), clock::get);
            for (int i = 0; i < 5_000; i++) {
                clock.addAndGet(random.nextInt(4) == 0 ? 0 : random.nextLong(3 * 1_000_000_000L));
                String key = "client-" + random.nextInt(3);
                long cost = 1 + random.nextInt(properties.capacityTokens());
                assertThat(leaky.tryAcquire(key, cost))
                        .as("%s, request %d", properties, i)
                        .isEqualTo(tokens.tryAcquire(key, cost));
            }
        }
    }

    @Test
    void contendedUpdatesAreCountedAsCasRetries() throws Exception {
        assertCasRetriesObservedUnderContention();
    }
}
