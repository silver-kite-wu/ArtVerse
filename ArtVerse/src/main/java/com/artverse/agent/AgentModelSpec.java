package com.artverse.agent;

public record AgentModelSpec(
        String provider,
        String baseUrl,
        String model,
        String apiKeyHash,
        Long configId,
        AgentModelCapabilities capabilities
) {
    public AgentModelSpec(String provider, String baseUrl, String model, String apiKeyHash) {
        this(provider, baseUrl, model, apiKeyHash, null, AgentModelCapabilities.UNKNOWN);
    }

    public AgentModelSpec {
        provider = blankToDefault(provider, "deepseek");
        baseUrl = blankToDefault(baseUrl, "");
        model = blankToDefault(model, "");
        apiKeyHash = blankToDefault(apiKeyHash, "none");
        capabilities = capabilities == null ? AgentModelCapabilities.UNKNOWN : capabilities;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
