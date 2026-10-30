package ba.etf.nasp.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The context and its limiter are shared across tests, so each test uses its own client address.
@SpringBootTest(properties = {"rate-limit.capacity-tokens=2", "rate-limit.window-seconds=60"})
@AutoConfigureMockMvc
class RateLimitFilterTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitMetrics metrics;

    @Test
    void allowedRequestsReachControllerWithQuotaHeaders() throws Exception {
        hello("10.0.0.1")
                .andExpect(status().isOk())
                .andExpect(content().string("ok"))
                .andExpect(header().string("RateLimit-Policy", "\"default\";q=2;w=60"))
                .andExpect(header().string("RateLimit", "\"default\";r=1"))
                .andExpect(header().doesNotExist("Retry-After"));
        hello("10.0.0.1")
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit", "\"default\";r=0"));
    }

    @Test
    void exhaustedClientReceivesProblemResponse() throws Exception {
        hello("10.0.0.2");
        hello("10.0.0.2");
        hello("10.0.0.2")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("RateLimit-Policy", "\"default\";q=2;w=60"))
                .andExpect(header().string("RateLimit", "\"default\";r=0;t=30"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(RateLimitFilter.QUOTA_EXCEEDED_TYPE))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Request quota exceeded. Retry after 30 seconds."))
                .andExpect(jsonPath("$.instance").value("/api/hello"))
                .andExpect(jsonPath("$.policy").value("default"));
    }

    @Test
    void clientsHaveIndependentQuotas() throws Exception {
        hello("10.0.0.3");
        hello("10.0.0.3");
        hello("10.0.0.3").andExpect(status().isTooManyRequests());
        hello("10.0.0.4")
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit", "\"default\";r=1"));
    }

    @Test
    void apiKeysBehindOneAddressHaveIndependentQuotas() throws Exception {
        withApiKey("10.0.0.7", "key-a");
        withApiKey("10.0.0.7", "key-a");
        withApiKey("10.0.0.7", "key-a").andExpect(status().isTooManyRequests());
        withApiKey("10.0.0.7", "key-b").andExpect(status().isOk());
        hello("10.0.0.7")
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit", "\"default\";r=1"));
    }

    @Test
    void forwardedForIsIgnoredByDefault() throws Exception {
        hello("10.0.0.8");
        hello("10.0.0.8");
        mockMvc.perform(get("/api/hello").header("X-Forwarded-For", "203.0.113.7").with(request -> {
            request.setRemoteAddr("10.0.0.8");
            return request;
        })).andExpect(status().isTooManyRequests());
    }

    @Test
    void httpDecisionsAreRecordedInApplicationMetrics() throws Exception {
        var before = metrics.snapshot();
        hello("10.0.0.9");
        hello("10.0.0.9");
        hello("10.0.0.9").andExpect(status().isTooManyRequests());
        request("/missing", "10.0.0.9");
        var after = metrics.snapshot();
        assertThat(after.allowedRequests() - before.allowedRequests()).isEqualTo(2);
        assertThat(after.rejectedRequests() - before.rejectedRequests()).isEqualTo(1);
    }

    @Test
    void unknownApiPathsStillConsumeQuota() throws Exception {
        request("/api/missing", "10.0.0.5")
                .andExpect(status().isNotFound())
                .andExpect(header().string("RateLimit", "\"default\";r=1"));
    }

    @Test
    void pathsOutsideApiAreNotLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            request("/missing", "10.0.0.6")
                    .andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("RateLimit"))
                    .andExpect(header().doesNotExist("RateLimit-Policy"));
        }
    }

    private ResultActions hello(String remoteAddress) throws Exception {
        return request("/api/hello", remoteAddress);
    }

    private ResultActions withApiKey(String remoteAddress, String apiKey) throws Exception {
        return mockMvc.perform(get("/api/hello").header("X-API-Key", apiKey).with(request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        }));
    }

    private ResultActions request(String path, String remoteAddress) throws Exception {
        return mockMvc.perform(get(path).with(request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        }));
    }
}
