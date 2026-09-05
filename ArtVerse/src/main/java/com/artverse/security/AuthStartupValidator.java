package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class AuthStartupValidator {

    private final ArtVerseProperties properties;

    public AuthStartupValidator(ArtVerseProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        if (properties.getAuth().getChallenge().getMode() != ArtVerseProperties.ChallengeMode.ENFORCE) {
            return;
        }
        if (!"graphic-captcha".equalsIgnoreCase(properties.getAuth().getChallenge().getProvider())) {
            throw new IllegalStateException("Only graphic-captcha challenge provider is supported");
        }
        if (properties.getAuth().getChallenge().getCaptchaLength() < 3
                || properties.getAuth().getChallenge().getCaptchaLength() > 8) {
            throw new IllegalStateException("Captcha length must be between 3 and 8");
        }
        if (blank(properties.getAuth().getRisk().getHmacKey())) {
            throw new IllegalStateException("Challenge enforce mode requires auth risk HMAC key");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
