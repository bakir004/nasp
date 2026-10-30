package ba.etf.nasp.ratelimit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorsTests {

    private static final String PAGE_ORIGIN = "http://localhost:8090";

    // The context and its limiter are shared across tests, so each test uses its own client address.
    @Nested
    @SpringBootTest(properties = {"rate-limit.capacity-tokens=2", "rate-limit.window-seconds=60",
            "cors.allowed-origins=null,http://localhost:[*]"})
    @AutoConfigureMockMvc
    class Enabled {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void preflightIsAnsweredWithoutConsumingTokens() throws Exception {
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(options("/api/hello")
                                .header("Origin", PAGE_ORIGIN)
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "x-api-key, x-forwarded-for")
                                .with(remoteAddress("10.1.0.1")))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Access-Control-Allow-Origin", PAGE_ORIGIN))
                        .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("X-API-Key")))
                        .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("X-Forwarded-For")))
                        .andExpect(header().string("Access-Control-Max-Age", "3600"))
                        .andExpect(header().doesNotExist("RateLimit"));
            }
            hello(PAGE_ORIGIN, "10.1.0.1")
                    .andExpect(status().isOk())
                    .andExpect(header().string("RateLimit", "\"default\";r=1"));
        }

        @Test
        void allowedAndRejectedResponsesExposeQuotaHeaders() throws Exception {
            hello(PAGE_ORIGIN, "10.1.0.2")
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", PAGE_ORIGIN))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("RateLimit-Policy")));
            hello(PAGE_ORIGIN, "10.1.0.2");
            hello(PAGE_ORIGIN, "10.1.0.2")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Access-Control-Allow-Origin", PAGE_ORIGIN))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("RateLimit")))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
        }

        @Test
        void pageOpenedFromDiskIsAllowed() throws Exception {
            hello("null", "10.1.0.3")
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "null"));
        }

        @Test
        void unknownOriginIsRefusedWithoutConsumingTokens() throws Exception {
            hello("https://evil.example", "10.1.0.4")
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            hello(PAGE_ORIGIN, "10.1.0.4")
                    .andExpect(header().string("RateLimit", "\"default\";r=1"));
        }

        private ResultActions hello(String origin, String address) throws Exception {
            return mockMvc.perform(get("/api/hello").header("Origin", origin).with(remoteAddress(address)));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class DisabledByDefault {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void crossOriginRequestsGetNoCorsHeaders() throws Exception {
            mockMvc.perform(get("/api/hello").header("Origin", PAGE_ORIGIN))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                    .andExpect(header().exists("RateLimit"));
        }
    }

    private static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
