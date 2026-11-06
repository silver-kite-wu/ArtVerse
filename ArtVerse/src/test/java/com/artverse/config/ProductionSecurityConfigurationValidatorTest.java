package com.artverse.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityConfigurationValidatorTest {

    @Test
    void doesNotRequireProductionSecretsInDevelopment() {
        ProductionSecurityConfigurationValidator validator = validator(new MockEnvironment());

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsProductionWithoutStrongKeys() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        ProductionSecurityConfigurationValidator validator = validator(environment);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARTVERSE_SECRET_ENCRYPTION_KEY");
    }

    @Test
    void acceptsProductionWithDistinctKeysAndSecureSettings() {
        ProductionSecurityConfigurationValidator validator = productionValidator(secureProperties());

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsInsecureCookiesInProduction() {
        ArtVerseProperties properties = secureProperties();
        properties.getAuth().getCookie().setSecure(false);

        assertThatThrownBy(productionValidator(properties)::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARTVERSE_AUTH_COOKIE_SECURE=true");
    }

    @Test
    void rejectsReportOnlyCsrfInProduction() {
        ArtVerseProperties properties = secureProperties();
        properties.getAuth().getCsrf().setMode(ArtVerseProperties.EnforcementMode.REPORT);

        assertThatThrownBy(productionValidator(properties)::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("csrf.mode=enforce");
    }

    private ArtVerseProperties secureProperties() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getSecrets().setEncryptionKey("0123456789abcdef0123456789abcdef");
        properties.getAuth().getRisk().setHmacKey("abcdef0123456789abcdef0123456789");
        properties.getAuth().getCookie().setSecure(true);
        properties.getAuth().getCsrf().setMode(ArtVerseProperties.EnforcementMode.ENFORCE);
        properties.getFlyway().setAutoRepairChecksumMismatch(false);
        return properties;
    }

    private ProductionSecurityConfigurationValidator productionValidator(ArtVerseProperties properties) {
        ProductionSecurityConfigurationValidator validator = new ProductionSecurityConfigurationValidator(properties);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        validator.setEnvironment(environment);

        return validator;
    }

    private ProductionSecurityConfigurationValidator validator(MockEnvironment environment) {
        ProductionSecurityConfigurationValidator validator = new ProductionSecurityConfigurationValidator(new ArtVerseProperties());
        validator.setEnvironment(environment);
        return validator;
    }
}
