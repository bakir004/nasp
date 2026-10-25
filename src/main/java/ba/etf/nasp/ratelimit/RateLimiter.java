package ba.etf.nasp.ratelimit;

public interface RateLimiter {

    /** Attempts to consume a positive cost from the bucket identified by a nonblank key. */
    RateLimitDecision tryAcquire(String key, long costTokens);
}
