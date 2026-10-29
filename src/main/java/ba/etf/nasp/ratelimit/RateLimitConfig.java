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
    RateLimiter rateLimiter(RateLimitProperties properties) {
        return new SynchronizedTokenBucketLimiter(properties);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(new RateLimitFilter(rateLimiter, objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
