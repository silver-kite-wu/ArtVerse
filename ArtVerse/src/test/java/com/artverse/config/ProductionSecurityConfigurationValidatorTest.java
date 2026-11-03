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
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getSecrets().setEncryptionKey("0123456789abcdef0123456789abcdef");
        properties.getAuth().getRisk().setHmacKey("abcdef0123456789abcdef0123456789");
        properties.getAuth().getCookie().setSecure(true);
        properties.getAuth().getCsrf().setMode(ArtVerseProperties.EnforcementMode.ENFORCE);
        properties.getFlyway().setAutoRepairChecksumMismatch(false);
        ProductionSecurityConfigurationValidator validator = new ProductionSecurityConfigurationValidator(properties);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        validator.setEnvironment(environment);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    private ProductionSecurityConfigurationValidator validator(MockEnvironment environment) {
        ProductionSecurityConfigurationValidator validator = new ProductionSecurityConfigurationValidator(new ArtVerseProperties());
        validator.setEnvironment(environment);
        return validator;
    }
}
