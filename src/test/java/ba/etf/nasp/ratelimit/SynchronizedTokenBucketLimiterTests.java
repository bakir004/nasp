package ba.etf.nasp.ratelimit;

import java.util.function.LongSupplier;

class SynchronizedTokenBucketLimiterTests extends TokenBucketLimiterContractTests {

    @Override
    protected RateLimiter createLimiter(RateLimitProperties properties, RateLimitMetrics metrics,
            LongSupplier nanoTime) {
        return new SynchronizedTokenBucketLimiter(properties, metrics, nanoTime);
    }
}
