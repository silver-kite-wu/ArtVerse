package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CsrfProtectionFilterTest {

    private final ArtVerseProperties properties = new ArtVerseProperties();
    private final CsrfProtectionFilter filter = new CsrfProtectionFilter(properties);

    @Test
    void rejectsUnsafeApiRequestWhenOriginAndHeaderAreMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/stories");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"code\"")
                .contains("\"message\":\"CSRF verification failed\"")
                .contains("\"data\":null");
        assertThat(((MockFilterChain) chain).getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173"})
    void permitsUnsafeApiRequestFromConfiguredOriginWithExpectedHeader(String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/stories");
        request.addHeader("Origin", origin);
        request.addHeader("X-ArtVerse-Client", "web");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "<missing>|web|ORIGIN_MISSING|OK",
            "null|web|ORIGIN_NOT_ALLOWED|OK",
            "https://unknown.example|web|ORIGIN_NOT_ALLOWED|OK",
            "http://localhost:5174|web|ORIGIN_NOT_ALLOWED|OK",
            "http://localhost:5173|<missing>|OK|CLIENT_HEADER_MISSING",
            "http://localhost:5173|wrong-value|OK|CLIENT_HEADER_INVALID"
    }, delimiter = '|', nullValues = "<missing>")
    void rejectsInvalidOriginOrClientHeaderIndependently(String origin, String clientHeader,
            String originReason, String headerReason, CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        if (origin != null) request.addHeader("Origin", origin);
        if (clientHeader != null) request.addHeader("X-ArtVerse-Client", clientHeader);
        request.addHeader("Cookie", "secret-cookie-marker");
        request.setContent("secret-password-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":40303");
        assertThat(chain.getRequest()).isNull();
        assertThat(output.getAll()).contains("mode=ENFORCE", "originReason=" + originReason,
                        "clientHeaderReason=" + headerReason)
                .doesNotContain("secret-cookie-marker", "secret-password-marker", "wrong-value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void protectsAllUnsafeMethods(String method) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(method, "/api/auth/login"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/auth/challenge/config", "HEAD,/api/stories", "OPTIONS,/api/auth/login",
            "POST,/static/public-image", "POST,/webhook"})
    void skipsSafeMethodsAndUnprotectedPaths(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/loggers"})
    void protectsUnsafeActuatorRequests(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("POST", path), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @CsvSource({"https://artverse.example,true", "http://localhost:5173,false",
            "http://127.0.0.1:5173,false", "https://unknown.example,false"})
    void deploymentAllowlistDoesNotAutomaticallyTrustDevelopmentOrigins(String origin, boolean allowed) throws Exception {
        properties.setCorsOrigins(List.of("https://artverse.example"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("Origin", origin);
        request.addHeader("X-ArtVerse-Client", "web");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(allowed ? 200 : 403);
        assertThat(chain.getRequest() != null).isEqualTo(allowed);
    }

    @Test
    void reportModeStillLogsMismatchAndContinues(CapturedOutput output) throws Exception {
        properties.getAuth().getCsrf().setMode(ArtVerseProperties.EnforcementMode.REPORT);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(output.getAll()).contains("mode=REPORT", "ORIGIN_MISSING", "CLIENT_HEADER_MISSING");
    }
}
