package com.artverse.agents;

public record AgentModelSpec(
        String provider,
        String baseUrl,
        String model,
        String apiKeyHash
) {
    public AgentModelSpec {
        provider = blankToDefault(provider, "deepseek");
        baseUrl = blankToDefault(baseUrl, "");
        model = blankToDefault(model, "");
        apiKeyHash = blankToDefault(apiKeyHash, "none");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
