package com.artverse.agent;

/** Server-owned declarations. Provider verification remains a separate recorded test result. */
public record AgentModelCapabilities(
        boolean toolCalling,
        boolean nativeStructuredOutput,
        boolean nativeStructuredOutputWithTools,
        String declarationVersion
) {
    public static final AgentModelCapabilities UNKNOWN = new AgentModelCapabilities(false, false, false, "unknown");

    public AgentModelCapabilities {
        if (declarationVersion == null || declarationVersion.isBlank())
            throw new IllegalArgumentException("Model capability declaration version is required");
        if (nativeStructuredOutputWithTools && (!nativeStructuredOutput || !toolCalling))
            throw new IllegalArgumentException("Native output with tools requires native output and tool calling");
    }
}
