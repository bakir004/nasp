package ba.etf.nasp.ratelimit;

import java.util.function.LongSupplier;

class SynchronizedTokenBucketLimiterTests extends TokenBucketLimiterContractTests {

    @Override
    protected RateLimiter createLimiter(RateLimitProperties properties, LongSupplier nanoTime) {
        return new SynchronizedTokenBucketLimiter(properties, nanoTime);
    }
}
