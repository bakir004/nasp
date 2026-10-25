package ba.etf.nasp.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RateLimitContractTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitConfig.class);

    @Test
    void bindsDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class))
                    .isEqualTo(new RateLimitProperties(100, 60, false));
        });
    }

    @Test
    void bindsCustomConfiguration() {
        contextRunner.withPropertyValues("rate-limit.capacity-tokens=20",
                "rate-limit.window-seconds=10", "rate-limit.trust-forwarded-for=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RateLimitProperties.class))
                            .isEqualTo(new RateLimitProperties(20, 10, true));
                });
    }

    @Test
    void invalidConfigurationFailsStartup() {
        for (String property : new String[]{"rate-limit.capacity-tokens=0",
                "rate-limit.capacity-tokens=-1", "rate-limit.window-seconds=0",
                "rate-limit.window-seconds=-1"}) {
            contextRunner.withPropertyValues(property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void decisionRetainsQuotaMetadata() {
        var decision = new RateLimitDecision(false, 0, 100, 60, 1);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remainingTokens()).isZero();
        assertThat(decision.capacityTokens()).isEqualTo(100);
        assertThat(decision.windowSeconds()).isEqualTo(60);
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidDecisions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(true, -1, 10, 60, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(true, 11, 10, 60, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(true, 1, 10, 60, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(false, 0, 10, 60, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(false, 0, 0, 60, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(false, 0, 10, 0, 1));
    }
}
