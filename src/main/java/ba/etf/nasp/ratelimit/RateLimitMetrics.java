package ba.etf.nasp.ratelimit;

import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory limiter counters. {@link LongAdder} spreads concurrent increments across cells,
 * so recording metrics does not add a contended hot spot to the request path.
 */
public final class RateLimitMetrics {

    private final LongAdder allowedRequests = new LongAdder();
    private final LongAdder rejectedRequests = new LongAdder();
    private final LongAdder casRetries = new LongAdder();

    void recordDecision(RateLimitDecision decision) {
        (decision.allowed() ? allowedRequests : rejectedRequests).increment();
    }

    void recordCasRetry() {
        casRetries.increment();
    }

    /** Counters are read one at a time, so a snapshot taken under load is approximate. */
    public Snapshot snapshot() {
        return new Snapshot(allowedRequests.sum(), rejectedRequests.sum(), casRetries.sum());
    }

    public record Snapshot(long allowedRequests, long rejectedRequests, long casRetries) {
    }
}
