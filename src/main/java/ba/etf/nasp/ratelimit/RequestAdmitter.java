package ba.etf.nasp.ratelimit;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Decides when a client's request may proceed: now, later, or not at all. Policing limiters
 * ({@link RateLimiter}) answer at once; a shaping queue may admit a request but release it later.
 */
public interface RequestAdmitter {

    Admission admit(String key);

    /**
     * The decision for a request and the moment it may proceed. {@code released} is already
     * complete for requests that proceed at once, and never completes for rejected ones.
     */
    record Admission(RateLimitDecision decision, CompletionStage<Void> released) {

        public Admission {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(released, "released");
        }

        static Admission immediate(RateLimitDecision decision) {
            return new Admission(decision, decision.allowed()
                    ? CompletableFuture.completedFuture(null) : new CompletableFuture<>());
        }
    }

    /** Admits each request according to a policing limiter, charging one token. */
    record Immediate(RateLimiter limiter) implements RequestAdmitter {

        public Immediate {
            Objects.requireNonNull(limiter, "limiter");
        }

        @Override
        public Admission admit(String key) {
            return Admission.immediate(limiter.tryAcquire(key, 1));
        }
    }
}
