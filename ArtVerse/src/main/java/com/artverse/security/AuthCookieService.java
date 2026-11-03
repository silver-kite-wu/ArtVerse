package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookieService {

    private final ArtVerseProperties properties;

    public AuthCookieService(ArtVerseProperties properties) {
        this.properties = properties;
    }

    public void writeRefreshCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        response.addHeader("Set-Cookie", buildCookie(token, maxAgeSeconds).toString());
    }

    public void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildCookie("", 0).toString());
    }

    public String readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String refreshCookieName = refreshCookieName();
        for (Cookie cookie : cookies) {
            if (refreshCookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public String refreshCookieName() {
        if (properties.getAuth().getCookie().isSecure()) {
            return "__Host-artverse-refresh";
        }
        String configured = properties.getAuth().getCookie().getRefreshCookieName();
        return configured == null || configured.isBlank() ? "artverse-refresh" : configured.trim();
    }

    private ResponseCookie buildCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(refreshCookieName(), value)
                .httpOnly(true)
                .secure(properties.getAuth().getCookie().isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
