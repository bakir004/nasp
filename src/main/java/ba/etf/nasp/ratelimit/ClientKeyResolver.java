package ba.etf.nasp.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.Collections;
import java.util.HexFormat;

/**
 * Picks the limiter key for a request: authenticated principal, then API key, then client
 * address. Principals and API keys are hashed so raw credentials never become map keys.
 */
public class ClientKeyResolver {

    static final String API_KEY_HEADER = "X-API-Key";
    static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final boolean trustForwardedFor;

    public ClientKeyResolver(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String resolve(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal != null && hasText(principal.getName())) {
            return "principal:" + sha256(principal.getName());
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (hasText(apiKey)) {
            return "api-key:" + sha256(apiKey.strip());
        }

        if (trustForwardedFor) {
            String forwardedAddress = lastForwardedAddress(request);
            if (forwardedAddress != null) {
                return "ip:" + forwardedAddress;
            }
        }

        String remoteAddress = request.getRemoteAddr();
        return "ip:" + (hasText(remoteAddress) ? remoteAddress : "unknown");
    }

    // Earlier entries are supplied by the client and can be forged; the last one was appended
    // by the trusted proxy in front of the application. A single proxy hop is assumed.
    private static String lastForwardedAddress(HttpServletRequest request) {
        String last = null;
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR_HEADER))) {
            for (String entry : header.split(",")) {
                if (hasText(entry)) {
                    last = entry.strip();
                }
            }
        }
        return last;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
