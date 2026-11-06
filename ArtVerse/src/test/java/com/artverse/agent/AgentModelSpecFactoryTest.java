package com.artverse.agent;

import com.artverse.application.UserProviderConfig;
import com.artverse.config.ArtVerseProperties;
import com.artverse.security.ProviderEndpointPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelSpecFactoryTest {
    @Test void unknownCapabilitiesDoNotBecomeTrueBecauseTheTransportIsCompatible() {
        var factory = new AgentModelSpecFactory(mock(ProviderEndpointPolicy.class), new ArtVerseProperties());
        factory.validateDeclarations();
        var spec = factory.fromProviderConfig(config("fixture-model", 7L));
        assertThat(spec.configId()).isEqualTo(7L);
        assertThat(spec.capabilities()).isEqualTo(AgentModelCapabilities.UNKNOWN);
        assertThat(spec.apiKeyHash()).doesNotContain("fixture-api-key");
    }

    @Test void exactConfigurationDeclarationsStayIsolatedAndSpecsAreImmutable() {
        var properties = new ArtVerseProperties();
        var nativeDeclaration = declaration(7L, true, true, true);
        var syntheticDeclaration = declaration(8L, true, false, false);
        properties.getAgent().setModelCapabilities(List.of(nativeDeclaration, syntheticDeclaration));
        var factory = new AgentModelSpecFactory(mock(ProviderEndpointPolicy.class), properties);
        factory.validateDeclarations();
        var nativeSpec = factory.fromProviderConfig(config("fixture-model", 7L));
        var syntheticSpec = factory.fromProviderConfig(config("fixture-model", 8L));
        assertThat(nativeSpec.capabilities().nativeStructuredOutputWithTools()).isTrue();
        assertThat(syntheticSpec.capabilities().toolCalling()).isTrue();
        assertThat(syntheticSpec.capabilities().nativeStructuredOutput()).isFalse();
        assertThat(factory.fromProviderConfig(config("other-model", 7L)).capabilities()).isEqualTo(AgentModelCapabilities.UNKNOWN);
        assertThat(factory.fromProviderConfig(new UserProviderConfig("llm", "fixture", "fixture", "fixture-api-key",
                "https://other.example.test/v1", "fixture-model", 7L)).capabilities()).isEqualTo(AgentModelCapabilities.UNKNOWN);
        assertThat(factory.fromProviderConfig(config("fixture-model", 9L)).capabilities()).isEqualTo(AgentModelCapabilities.UNKNOWN);
        nativeDeclaration.setNativeStructuredOutputWithTools(false);
        nativeDeclaration.setNativeStructuredOutput(false);
        assertThat(nativeSpec.capabilities().nativeStructuredOutputWithTools()).isTrue();
    }

    @Test void overlappingOrInvalidDeclarationsFailAtInitialization() {
        var properties = new ArtVerseProperties();
        properties.getAgent().setModelCapabilities(List.of(declaration(null, true, false, false), declaration(7L, true, true, true)));
        var factory = new AgentModelSpecFactory(mock(ProviderEndpointPolicy.class), properties);
        assertThatThrownBy(factory::validateDeclarations).isInstanceOf(IllegalStateException.class).hasMessageContaining("overlap");
        properties.getAgent().setModelCapabilities(List.of(declaration(7L, true, false, true)));
        assertThatThrownBy(factory::validateDeclarations).isInstanceOf(IllegalArgumentException.class);
        properties.getAgent().setModelCapabilities(List.of(declaration(7L, false, true, true)));
        assertThatThrownBy(factory::validateDeclarations).isInstanceOf(IllegalArgumentException.class);
        var missing = declaration(7L, true, false, false); missing.setDeclarationVersion(null);
        properties.getAgent().setModelCapabilities(List.of(missing));
        assertThatThrownBy(factory::validateDeclarations).isInstanceOf(IllegalArgumentException.class);
    }

    private static UserProviderConfig config(String model, long id) {
        return new UserProviderConfig("llm", "fixture", "fixture", "fixture-api-key", "https://provider.example.test/v1/", model, id);
    }

    private static ArtVerseProperties.Agent.ModelCapability declaration(Long configId, boolean tools, boolean nativeOutput, boolean withTools) {
        var value = new ArtVerseProperties.Agent.ModelCapability();
        value.setProvider("fixture"); value.setBaseUrl("https://provider.example.test/v1");
        value.setModel("fixture-model"); value.setConfigId(configId); value.setToolCalling(tools);
        value.setNativeStructuredOutput(nativeOutput); value.setNativeStructuredOutputWithTools(withTools);
        value.setDeclarationVersion("fixture-v1");
        return value;
    }
}
