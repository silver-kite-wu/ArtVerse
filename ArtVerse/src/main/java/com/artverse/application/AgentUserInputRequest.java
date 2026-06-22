package com.artverse.application;

import java.util.List;
import java.util.Map;

public record AgentUserInputRequest(
        String question,
        List<Option> options,
        boolean allowFreeText,
        String reason
) {
    public AgentUserInputRequest {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public Map<String, Object> toEventData() {
        return Map.of(
                "question", question == null ? "" : question,
                "options", options,
                "allowFreeText", allowFreeText,
                "reason", reason == null ? "" : reason
        );
    }

    public record Option(String id, String label, String description, boolean recommended) {
    }
}
