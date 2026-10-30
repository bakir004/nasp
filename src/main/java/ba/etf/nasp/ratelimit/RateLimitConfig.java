package ba.etf.nasp.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the rate limit filter. {@code rate-limit.algorithm} selects how requests are admitted:
 * {@code token-bucket} (default) and {@code leaky-bucket} admit or reject at once, while
 * {@code leaky-bucket-queue} queues requests and releases them at a steady rate.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    private static final String ALGORITHM = "rate-limit.algorithm";

    @Bean
    RateLimitMetrics rateLimitMetrics() {
        return new RateLimitMetrics();
    }

    @Bean
    @ConditionalOnProperty(name = ALGORITHM, havingValue = "token-bucket", matchIfMissing = true)
    RequestAdmitter tokenBucket(RateLimitProperties properties, RateLimitMetrics metrics) {
        return new RequestAdmitter.Immediate(new AtomicTokenBucketLimiter(properties, metrics));
    }

    @Bean
    @ConditionalOnProperty(name = ALGORITHM, havingValue = "leaky-bucket")
    RequestAdmitter leakyBucket(RateLimitProperties properties, RateLimitMetrics metrics) {
        return new RequestAdmitter.Immediate(new LeakyBucketLimiter(properties, metrics));
    }

    // Releases queued requests. Waiting requests use servlet async processing, so they hold a
    // queue slot rather than a server thread.
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(name = ALGORITHM, havingValue = "leaky-bucket-queue")
    ScheduledExecutorService leakScheduler() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "leaky-bucket-leak");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnProperty(name = ALGORITHM, havingValue = "leaky-bucket-queue")
    RequestAdmitter leakyBucketQueue(RateLimitProperties properties, RateLimitMetrics metrics,
            ScheduledExecutorService leakScheduler) {
        return new LeakyBucketQueue(properties, metrics, leakScheduler);
    }

    @Bean
    ClientKeyResolver clientKeyResolver(RateLimitProperties properties) {
        return new ClientKeyResolver(properties.trustForwardedFor());
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RequestAdmitter admitter,
            ClientKeyResolver clientKeyResolver, ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(
                new RateLimitFilter(admitter, clientKeyResolver, objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
