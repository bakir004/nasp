package ba.etf.nasp.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    RateLimitMetrics rateLimitMetrics() {
        return new RateLimitMetrics();
    }

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, RateLimitMetrics metrics) {
        return new AtomicTokenBucketLimiter(properties, metrics);
    }

    @Bean
    ClientKeyResolver clientKeyResolver(RateLimitProperties properties) {
        return new ClientKeyResolver(properties.trustForwardedFor());
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter rateLimiter,
            ClientKeyResolver clientKeyResolver, ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(
                new RateLimitFilter(rateLimiter, clientKeyResolver, objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
