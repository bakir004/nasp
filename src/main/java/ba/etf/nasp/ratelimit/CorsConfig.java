package ba.etf.nasp.ratelimit;

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

/**
 * Lets browser pages on other origins call the API and read its quota headers.
 *
 * <p>The CORS filter runs before {@link RateLimitFilter}, for two reasons: preflight requests are
 * answered here and never consume tokens, and the 429 responses the rate limit filter writes
 * itself still carry CORS headers, so the browser lets the page read them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(CorsProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(properties.allowedOrigins());
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
        registration.setEnabled(!properties.allowedOrigins().isEmpty());
        return registration;
    }
}
