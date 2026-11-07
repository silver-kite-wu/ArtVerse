package com.artverse.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtVersePropertiesDevModeTest {

    @Test
    void devModeAddsLocalOriginsAndDisablesSecureCookie() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.setDevMode(true);
        properties.setCorsOrigins(List.of("https://artverse.duckdns.org"));
        properties.getAuth().getCookie().setSecure(true);

        properties.applyDevModeSettings();

        assertThat(properties.getCorsOrigins()).containsExactly(
                "https://artverse.duckdns.org",
                "http://localhost:5173",
                "http://127.0.0.1:5173");
        assertThat(properties.getAuth().getCookie().isSecure()).isFalse();
    }

    @Test
    void onlineModeKeepsConfiguredOriginsAndSecureCookie() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.setDevMode(false);
        properties.setCorsOrigins(List.of("https://artverse.duckdns.org"));
        properties.getAuth().getCookie().setSecure(true);

        properties.applyDevModeSettings();

        assertThat(properties.getCorsOrigins()).containsExactly("https://artverse.duckdns.org");
        assertThat(properties.getAuth().getCookie().isSecure()).isTrue();
    }
}
