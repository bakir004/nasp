package ba.etf.nasp.ratelimit;

/** Immutable quota snapshot. Retry delay is in whole seconds, rounded upward. */
public record RateLimitDecision(
        boolean allowed,
        long remainingTokens,
        long capacityTokens,
        long windowSeconds,
        long retryAfterSeconds) {

    public RateLimitDecision {
        if (capacityTokens <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("Capacity and window must be positive");
        }
        if (remainingTokens < 0 || remainingTokens > capacityTokens) {
            throw new IllegalArgumentException("Remaining tokens must be within bucket capacity");
        }
        if (retryAfterSeconds < 0 || (allowed && retryAfterSeconds != 0)) {
            throw new IllegalArgumentException("Invalid retry delay");
        }
    }
}
