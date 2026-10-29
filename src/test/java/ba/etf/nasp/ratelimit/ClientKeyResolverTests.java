package ba.etf.nasp.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientKeyResolverTests {

    private static final String ALICE_SHA256 =
            "2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90";
    private static final String SECRET_SHA256 =
            "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b";

    private final ClientKeyResolver untrusted = new ClientKeyResolver(false);
    private final ClientKeyResolver trusted = new ClientKeyResolver(true);

    @Test
    void principalTakesPriorityAndIsHashed() {
        MockHttpServletRequest request = request();
        request.setUserPrincipal(() -> "alice");
        request.addHeader("X-API-Key", "secret");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(trusted.resolve(request)).isEqualTo("principal:" + ALICE_SHA256);
    }

    @Test
    void apiKeyIsUsedWithoutPrincipalAndIsHashed() {
        MockHttpServletRequest request = request();
        request.addHeader("X-API-Key", " secret ");

        String key = untrusted.resolve(request);
        assertThat(key).isEqualTo("api-key:" + SECRET_SHA256).doesNotContain("secret");
    }

    @Test
    void blankPrincipalAndApiKeyFallBackToRemoteAddress() {
        MockHttpServletRequest request = request();
        request.setUserPrincipal(() -> " ");
        request.addHeader("X-API-Key", "  ");

        assertThat(untrusted.resolve(request)).isEqualTo("ip:10.0.0.1");
    }

    @Test
    void forwardedForIsIgnoredUnlessTrusted() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(untrusted.resolve(request)).isEqualTo("ip:10.0.0.1");
        assertThat(trusted.resolve(request)).isEqualTo("ip:203.0.113.7");
    }

    @Test
    void trustedForwardedForUsesAddressAppendedByProxy() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "198.51.100.66, 203.0.113.7");

        assertThat(trusted.resolve(request)).isEqualTo("ip:203.0.113.7");
    }

    @Test
    void repeatedForwardedForHeadersAreReadInOrder() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "198.51.100.66");
        request.addHeader("X-Forwarded-For", "203.0.113.7, ");

        assertThat(trusted.resolve(request)).isEqualTo("ip:203.0.113.7");
    }

    @Test
    void emptyTrustedForwardedForFallsBackToRemoteAddress() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", " , ");

        assertThat(trusted.resolve(request)).isEqualTo("ip:10.0.0.1");
    }

    @Test
    void missingRemoteAddressUsesPlaceholder() {
        MockHttpServletRequest request = request();
        request.setRemoteAddr(null);

        assertThat(untrusted.resolve(request)).isEqualTo("ip:unknown");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/hello");
        request.setRemoteAddr("10.0.0.1");
        return request;
    }
}
