package ba.etf.nasp.ratelimit;

import java.util.concurrent.CompletableFuture;

/** Decides whether a client's request may proceed, and when. Every request costs one token. */
public interface RateLimiter {

    /** Decides for one request from the client identified by a nonblank key. */
    Decision tryAcquire(String client);

    /**
     * The answer for one request.
     *
     * @param allowed           whether the request may proceed
     * @param remaining         how many more requests the client could make right now
     * @param retryAfterSeconds for a rejection, whole seconds until a request can succeed
     * @param ready             completes when an allowed request may proceed: already complete
     *                          unless the request has to wait in a queue; unused for rejections
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds,
            CompletableFuture<Void> ready) {

        private static final CompletableFuture<Void> NOW = CompletableFuture.completedFuture(null);

        public static Decision allowNow(long remaining) {
            return new Decision(true, remaining, 0, NOW);
        }

        public static Decision allowLater(long remaining, CompletableFuture<Void> ready) {
            return new Decision(true, remaining, 0, ready);
        }

        public static Decision reject(long retryAfterSeconds) {
            return new Decision(false, 0, retryAfterSeconds, NOW);
        }
    }
}
