package ba.etf.nasp.ratelimit;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Browser origins allowed to call {@code /api/*} across origins. Empty, the default, disables
 * CORS. Entries are Spring origin patterns, such as {@code http://localhost:[*]}; {@code null}
 * matches pages opened directly from disk.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}
