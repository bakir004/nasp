package ba.etf.nasp.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Charges one token per request and rejects exhausted clients before they reach a controller. */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String POLICY_NAME = "default";
    static final String QUOTA_EXCEEDED_TYPE =
            "https://iana.org/assignments/http-problem-types#quota-exceeded";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RateLimitDecision decision = rateLimiter.tryAcquire(clientKey(request), 1);
        setRateLimitHeaders(response, decision);
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
        } else {
            writeTooManyRequests(request, response, decision);
        }
    }

    // Client identification policies (principal, API key, forwarded headers) are not applied yet.
    private static String clientKey(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return "ip:" + (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress);
    }

    // Header syntax follows the IETF HTTPAPI RateLimit header fields draft.
    private static void setRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader("RateLimit-Policy", "\"" + POLICY_NAME + "\";q="
                + decision.capacityTokens() + ";w=" + decision.windowSeconds());
        String limit = "\"" + POLICY_NAME + "\";r=" + decision.remainingTokens();
        if (!decision.allowed()) {
            limit += ";t=" + decision.retryAfterSeconds();
        }
        response.setHeader("RateLimit", limit);
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response,
            RateLimitDecision decision) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", QUOTA_EXCEEDED_TYPE);
        problem.put("title", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        problem.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        problem.put("detail", "Request quota exceeded. Retry after "
                + decision.retryAfterSeconds() + " seconds.");
        problem.put("instance", request.getRequestURI());
        problem.put("policy", POLICY_NAME);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
