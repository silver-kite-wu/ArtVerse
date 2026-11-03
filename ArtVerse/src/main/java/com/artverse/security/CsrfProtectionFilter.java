package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Slf4j
@Component
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ArtVerseProperties properties;

    public CsrfProtectionFilter(ArtVerseProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean protectedPath = path.startsWith("/api/") || path.equals("/actuator") || path.startsWith("/actuator/");
        return !protectedPath
                || !UNSAFE_METHODS.contains(request.getMethod())
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        String expectedHeaderName = properties.getAuth().getCsrf().getHeaderName();
        String expectedHeaderValue = properties.getAuth().getCsrf().getHeaderValue();
        boolean validOrigin = origin != null && properties.getCorsOrigins().contains(origin);
        boolean validHeader = expectedHeaderValue.equals(request.getHeader(expectedHeaderName));
        if (validOrigin && validHeader) {
            filterChain.doFilter(request, response);
            return;
        }

        if (properties.getAuth().getCsrf().getMode() == ArtVerseProperties.EnforcementMode.REPORT) {
            log.warn("CSRF report-only mismatch: method={}, uri={}, originPresent={}, headerPresent={}",
                    request.getMethod(), request.getRequestURI(), origin != null, request.getHeader(expectedHeaderName) != null);
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(403);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":" + AuthErrorCodes.CSRF_REJECTED
                + ",\"message\":\"CSRF verification failed\",\"data\":null}");
    }
}
