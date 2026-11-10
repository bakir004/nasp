package ba.etf.nasp.ratelimit;

import ba.etf.nasp.ratelimit.RateLimiter.Decision;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncContext;
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

/**
 * Charges one token per request and rejects exhausted clients before they reach a controller.
 * A request that has to wait in a leaky bucket queue is suspended with servlet async processing,
 * so it holds no server thread while it waits, and continues when the queue releases it.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String RATE_LIMIT_HEADER = "RateLimit";
    static final String RATE_LIMIT_POLICY_HEADER = "RateLimit-Policy";
    static final String POLICY_NAME = "default";
    static final String QUOTA_EXCEEDED_TYPE =
            "https://iana.org/assignments/http-problem-types#quota-exceeded";

    private final RateLimiter rateLimiter;
    private final ClientKeyResolver clientKeyResolver;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ClientKeyResolver clientKeyResolver,
            RateLimitProperties properties, ObjectMapper objectMapper) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clientKeyResolver = Objects.requireNonNull(clientKeyResolver, "clientKeyResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Decision decision = rateLimiter.tryAcquire(clientKeyResolver.resolve(request));
        setRateLimitHeaders(response, decision);
        if (!decision.allowed()) {
            writeTooManyRequests(request, response, decision);
        } else if (decision.ready().isDone()) {
            filterChain.doFilter(request, response);
        } else if (request.isAsyncSupported()) {
            AsyncContext async = request.startAsync();
            // A queued request waits at most one window; the margin covers a slow release.
            async.setTimeout(properties.windowSeconds() * 1000L + 30_000);
            // The async dispatch re-enters the filter chain, where OncePerRequestFilter skips this
            // filter (it already ran) and the request continues to the controller.
            decision.ready().thenRun(() -> dispatch(async));
        } else {
            // Without async support the request waits for its release on its own thread.
            decision.ready().join();
            filterChain.doFilter(request, response);
        }
    }

    private static void dispatch(AsyncContext async) {
        try {
            async.dispatch();
        } catch (IllegalStateException e) {
            // The client disconnected or the request timed out while it was queued.
        }
    }

    // Header syntax follows the IETF HTTPAPI RateLimit header fields draft.
    private void setRateLimitHeaders(HttpServletResponse response, Decision decision) {
        response.setHeader(RATE_LIMIT_POLICY_HEADER, "\"" + POLICY_NAME + "\";q="
                + properties.capacityTokens() + ";w=" + properties.windowSeconds());
        String limit = "\"" + POLICY_NAME + "\";r=" + decision.remaining();
        if (!decision.allowed()) {
            limit += ";t=" + decision.retryAfterSeconds();
        }
        response.setHeader(RATE_LIMIT_HEADER, limit);
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response,
            Decision decision) throws IOException {
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
