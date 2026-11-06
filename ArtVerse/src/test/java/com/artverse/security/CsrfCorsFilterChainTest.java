package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import com.artverse.config.CorsConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CsrfCorsFilterChainTest {

    private final ArtVerseProperties properties = new ArtVerseProperties();
    private final Servlet endpoint = mock(Servlet.class);

    // Both filters are registered as servlet filters without an explicit relative order.
    private MockFilterChain chain(boolean corsFirst) {
        Filter csrf = new CsrfProtectionFilter(properties);
        Filter cors = new CorsConfig(properties).corsFilter();
        return new MockFilterChain(endpoint, corsFirst ? new Filter[]{cors, csrf} : new Filter[]{csrf, cors});
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void permitsLocalLoginThroughBothFilters(boolean corsFirst) throws Exception {
        for (String origin : properties.getCorsOrigins()) {
            MockHttpServletRequest request = request("POST", origin);
            request.addHeader("X-ArtVerse-Client", "web");
            MockHttpServletResponse response = new MockHttpServletResponse();

            chain(corsFirst).doFilter(request, response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
            verify(endpoint).service(request, response);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void corsPermissionDoesNotBypassCsrfHeaderRequirement(boolean corsFirst) throws Exception {
        MockHttpServletRequest request = request("POST", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();

        chain(corsFirst).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":40303");
        verifyNoInteractions(endpoint);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsUnknownOriginEvenWithClientHeader(boolean corsFirst) throws Exception {
        MockHttpServletRequest request = request("POST", "https://unknown.example");
        request.addHeader("X-ArtVerse-Client", "web");
        MockHttpServletResponse response = new MockHttpServletResponse();

        chain(corsFirst).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(endpoint);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void permitsPreflightForLocalLoginWithoutActualClientHeader(boolean corsFirst) throws Exception {
        MockHttpServletRequest request = request("OPTIONS", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type,x-artverse-client");
        MockHttpServletResponse response = new MockHttpServletResponse();

        chain(corsFirst).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("x-artverse-client");
        verifyNoInteractions(endpoint);
    }

    private MockHttpServletRequest request(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/auth/login");
        request.setServerPort(8080);
        request.addHeader("Origin", origin);
        return request;
    }
}
