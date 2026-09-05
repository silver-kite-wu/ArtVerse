package com.artverse.agent;

import com.artverse.application.UserProviderConfig;
import com.artverse.common.BusinessException;
import com.artverse.security.ProviderEndpointPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
public class AgentModelSpecFactory {

    private static final int HASH_PREFIX_LENGTH = 12;

    private final ProviderEndpointPolicy endpointPolicy;

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
                shortHash(config.apiKey())
        );
    }

    public static String shortHash(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String hash = sha256Hex(value);
        return hash.substring(0, Math.min(HASH_PREFIX_LENGTH, hash.length()));
    }

    static String sha256Hex(String value) {
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
