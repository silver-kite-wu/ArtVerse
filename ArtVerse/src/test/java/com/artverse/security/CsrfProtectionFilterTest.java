package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void permitsUnsafeApiRequestFromConfiguredOriginWithExpectedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/stories");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("X-ArtVerse-Client", "web");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
