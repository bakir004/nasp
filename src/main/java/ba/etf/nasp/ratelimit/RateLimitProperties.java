package ba.etf.nasp.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        @DefaultValue("100") int capacityTokens,
        @DefaultValue("60") int windowSeconds,
        @DefaultValue("false") boolean trustForwardedFor) {

    public RateLimitProperties {
        if (capacityTokens <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("Rate limit capacity and window must be positive");
        }
    }
}
