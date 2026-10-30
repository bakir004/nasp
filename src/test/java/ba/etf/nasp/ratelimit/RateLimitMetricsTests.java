package ba.etf.nasp.ratelimit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitMetricsTests {

    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, 1, 10, 60, 0);
    private static final RateLimitDecision REJECTED = new RateLimitDecision(false, 0, 10, 60, 6);

    private final RateLimitMetrics metrics = new RateLimitMetrics();

    @Test
    void startsAtZero() {
        assertThat(metrics.snapshot()).isEqualTo(new RateLimitMetrics.Snapshot(0, 0, 0));
    }

    @Test
    void countsDecisionsByOutcomeAndRetriesSeparately() {
        metrics.recordDecision(ALLOWED);
        metrics.recordDecision(ALLOWED);
        metrics.recordDecision(REJECTED);
        metrics.recordCasRetry();
        assertThat(metrics.snapshot()).isEqualTo(new RateLimitMetrics.Snapshot(2, 1, 1));
    }

    @Test
    void concurrentIncrementsAreNotLost() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int t = 0; t < 8; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < 10_000; i++) {
                        metrics.recordDecision(i % 2 == 0 ? ALLOWED : REJECTED);
                        metrics.recordCasRetry();
                    }
                });
            }
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(metrics.snapshot()).isEqualTo(new RateLimitMetrics.Snapshot(40_000, 40_000, 80_000));
    }
}
