package ba.etf.nasp.ratelimit;

import ba.etf.nasp.ratelimit.RateLimitProperties.Algorithm;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(RateLimitConfig.class);

    @Test
    void bindsDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class))
                    .isEqualTo(new RateLimitProperties(100, 60, Algorithm.TOKEN_BUCKET, false, List.of()));
        });
    }

    @Test
    void bindsCustomConfiguration() {
        contextRunner.withPropertyValues("rate-limit.capacity-tokens=20",
                "rate-limit.window-seconds=10", "rate-limit.algorithm=leaky-bucket",
                "rate-limit.trust-forwarded-for=true",
                "rate-limit.cors-allowed-origins=null,http://localhost:[*]")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RateLimitProperties.class)).isEqualTo(new RateLimitProperties(
                            20, 10, Algorithm.LEAKY_BUCKET, true, List.of("null", "http://localhost:[*]")));
                });
    }

    @Test
    void selectsAlgorithmAtStartup() {
        contextRunner.run(context ->
                assertThat(context.getBean(RateLimiter.class)).isInstanceOf(TokenBucketLimiter.class));
        contextRunner.withPropertyValues("rate-limit.algorithm=token-bucket").run(context ->
                assertThat(context.getBean(RateLimiter.class)).isInstanceOf(TokenBucketLimiter.class));
        contextRunner.withPropertyValues("rate-limit.algorithm=leaky-bucket").run(context ->
                assertThat(context.getBean(RateLimiter.class)).isInstanceOf(LeakyBucketLimiter.class));
        contextRunner.withPropertyValues("rate-limit.algorithm=fifo").run(context ->
                assertThat(context).hasFailed());
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
}
