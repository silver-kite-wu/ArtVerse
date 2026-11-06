package com.artverse.agent;

import com.artverse.application.UserProviderConfig;
import com.artverse.common.BusinessException;
import com.artverse.security.ProviderEndpointPolicy;
import com.artverse.config.ArtVerseProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AgentModelSpecFactory {

    private static final int HASH_PREFIX_LENGTH = 12;

    private final ProviderEndpointPolicy endpointPolicy;
    private final ArtVerseProperties properties;

    @PostConstruct
    public void validateDeclarations() {
        var declarations = properties.getAgent().getModelCapabilities();
        if (declarations == null) throw new IllegalStateException("Model capability declarations cannot be null");
        for (int i = 0; i < declarations.size(); i++) {
            var current = declarations.get(i);
            if (current == null || blank(current.getProvider()) || blank(current.getBaseUrl())
                    || blank(current.getModel()) || (current.getConfigId() != null && current.getConfigId() <= 0))
                throw new IllegalStateException("Model capability declaration requires an exact provider, endpoint and model");
            capabilities(current);
            for (int j = 0; j < i; j++) {
                var other = declarations.get(j);
                if (sameTarget(current, other) && (current.getConfigId() == null || other.getConfigId() == null
                        || current.getConfigId().equals(other.getConfigId())))
                    throw new IllegalStateException("Model capability declarations overlap");
            }
        }
    }

    /**
     * Converts a user-saved provider configuration into the agent model spec
     * used by the workflow engine. The API key and endpoint are always the
     * user's own; operator configuration is never used as a fallback.
     */
    public AgentModelSpec fromProviderConfig(UserProviderConfig config) {
        endpointPolicy.requireSafeBaseUrl(config.baseUrl());
        return new AgentModelSpec(
                config.provider(),
                config.baseUrl(),
                config.model(),
                shortHash(config.apiKey()),
                config.configId(),
                declaredCapabilities(config)
        );
    }

    private AgentModelCapabilities declaredCapabilities(UserProviderConfig config) {
        var matches = properties.getAgent().getModelCapabilities().stream().filter(declaration ->
                normalizeProvider(declaration.getProvider()).equals(normalizeProvider(config.provider()))
                        && normalizeEndpoint(declaration.getBaseUrl()).equals(normalizeEndpoint(config.baseUrl()))
                        && declaration.getModel().equals(config.model())
                        && (declaration.getConfigId() == null || Objects.equals(declaration.getConfigId(), config.configId())))
                .toList();
        if (matches.size() > 1) throw new BusinessException(503, "模型能力声明存在重叠，请检查服务端配置");
        return matches.isEmpty() ? AgentModelCapabilities.UNKNOWN : capabilities(matches.getFirst());
    }

    private static AgentModelCapabilities capabilities(ArtVerseProperties.Agent.ModelCapability declaration) {
        return new AgentModelCapabilities(declaration.isToolCalling(), declaration.isNativeStructuredOutput(),
                declaration.isNativeStructuredOutputWithTools(), declaration.getDeclarationVersion());
    }

    private static boolean sameTarget(ArtVerseProperties.Agent.ModelCapability a, ArtVerseProperties.Agent.ModelCapability b) {
        return normalizeProvider(a.getProvider()).equals(normalizeProvider(b.getProvider()))
                && normalizeEndpoint(a.getBaseUrl()).equals(normalizeEndpoint(b.getBaseUrl()))
                && a.getModel().equals(b.getModel());
    }

    private static String normalizeProvider(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static String normalizeEndpoint(String value) { return value.trim().replaceAll("/+$", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public static String shortHash(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String hash = sha256Hex(value);
        return hash.substring(0, Math.min(HASH_PREFIX_LENGTH, hash.length()));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to hash agent model value");
        }
    }
}
