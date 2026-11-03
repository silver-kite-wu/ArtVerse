package com.artverse.config;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.InitializingBean;

@Component
public class ProductionSecurityConfigurationValidator implements EnvironmentAware, InitializingBean {

    private final ArtVerseProperties properties;
    private Environment environment;

    public ProductionSecurityConfigurationValidator(ArtVerseProperties properties) {
        this.properties = properties;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment == null || !environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            return;
        }

        requireStrongKey("ARTVERSE_SECRET_ENCRYPTION_KEY", properties.getSecrets().getEncryptionKey());
        requireStrongKey("ARTVERSE_AUTH_RISK_HMAC_KEY", properties.getAuth().getRisk().getHmacKey());
        requireStrongKey("ARTVERSE_AGENT_MCP_SHARED_SECRET", properties.getAgent().getMcp().getSharedSecret());
        if (properties.getSecrets().getEncryptionKey().equals(properties.getAuth().getRisk().getHmacKey())) {
            throw new IllegalStateException("Production encryption and risk HMAC keys must be different.");
        }
        if (properties.getAgent().getMcp().getSharedSecret().equals(properties.getSecrets().getEncryptionKey())
                || properties.getAgent().getMcp().getSharedSecret().equals(properties.getAuth().getRisk().getHmacKey())) {
            throw new IllegalStateException("Production MCP signing key must be different from other security keys.");
        }
        if (!properties.getAuth().getCookie().isSecure()) {
            throw new IllegalStateException("Production requires ARTVERSE_AUTH_COOKIE_SECURE=true.");
        }
        if (properties.getAuth().getCsrf().getMode() != ArtVerseProperties.EnforcementMode.ENFORCE) {
            throw new IllegalStateException("Production requires artverse.auth.csrf.mode=enforce.");
        }
        if (properties.getFlyway().isAutoRepairChecksumMismatch()) {
            throw new IllegalStateException("Production must not auto-repair Flyway checksum mismatches.");
        }
    }

    private void requireStrongKey(String name, String value) {
        if (!StringUtils.hasText(value) || value.trim().length() < 32) {
            throw new IllegalStateException(name + " must be configured with at least 32 characters in production.");
        }
    }
}
