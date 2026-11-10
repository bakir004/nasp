package ba.etf.nasp.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    // A leaky bucket is closed on shutdown (Spring calls close()), which stops its leak timer.
    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties) {
        int capacity = properties.capacityTokens();
        int window = properties.windowSeconds();
        return switch (properties.algorithm()) {
            case TOKEN_BUCKET -> new TokenBucketLimiter(capacity, window);
            case LEAKY_BUCKET -> new LeakyBucketLimiter(capacity, window);
        };
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter rateLimiter,
            RateLimitProperties properties, ObjectMapper objectMapper) {
        var filter = new RateLimitFilter(rateLimiter,
                new ClientKeyResolver(properties.trustForwardedFor()), properties, objectMapper);
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * Lets browser pages on the configured origins call the API and read its quota headers.
     * It runs before the rate limit filter, so preflight requests never consume tokens and the
     * 429 responses written by the rate limit filter still carry CORS headers.
     */
    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(RateLimitProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(properties.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET"));
        cors.setAllowedHeaders(List.of(ClientKeyResolver.API_KEY_HEADER,
                ClientKeyResolver.FORWARDED_FOR_HEADER));
        cors.setExposedHeaders(List.of(RateLimitFilter.RATE_LIMIT_HEADER,
                RateLimitFilter.RATE_LIMIT_POLICY_HEADER, HttpHeaders.RETRY_AFTER));
        // Browsers then preflight once per hour instead of before every request.
        cors.setMaxAge(Duration.ofHours(1));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        var registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setEnabled(!properties.corsAllowedOrigins().isEmpty());
        return registration;
    }
}
