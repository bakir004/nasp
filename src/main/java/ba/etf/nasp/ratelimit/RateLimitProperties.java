package ba.etf.nasp.ratelimit;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All {@code rate-limit.*} settings.
 *
 * @param capacityTokens     bucket size: the largest burst a client may send
 * @param windowSeconds      time to refill (or drain) a full bucket
 * @param algorithm          {@code token-bucket} or {@code leaky-bucket}
 * @param trustForwardedFor  identify clients by the last {@code X-Forwarded-For} entry
 * @param corsAllowedOrigins browser origins allowed to call {@code /api/*}; empty disables CORS
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        @DefaultValue("100") int capacityTokens,
        @DefaultValue("60") int windowSeconds,
        @DefaultValue("token-bucket") Algorithm algorithm,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue List<String> corsAllowedOrigins) {

    public enum Algorithm { TOKEN_BUCKET, LEAKY_BUCKET }

    public RateLimitProperties {
        if (capacityTokens <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("Rate limit capacity and window must be positive");
        }
    }
}
