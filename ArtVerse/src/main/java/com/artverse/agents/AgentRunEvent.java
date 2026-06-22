package com.artverse.agents;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentRunEvent(
        String type,
        String phase,
        String label,
        String toolName,
        String status,
        String text,
        Map<String, Object> data,
        OffsetDateTime createdAt
) {
    public static AgentRunEvent of(String type, String phase, String label) {
        return new AgentRunEvent(type, phase, label, null, null, null, Map.of(), OffsetDateTime.now());
    }

    public static AgentRunEvent of(String type, String phase, String label, Map<String, Object> data) {
        return new AgentRunEvent(type, phase, label, null, null, null,
                data == null ? Map.of() : Map.copyOf(data), OffsetDateTime.now());
    }

    public static AgentRunEvent text(String delta) {
        return new AgentRunEvent("text_delta", "replying", "姝ｅ湪鏁寸悊鍥炲", null, "running",
                delta, Map.of(), OffsetDateTime.now());
    }

    public static AgentRunEvent step(String node, String status, String label, Map<String, Object> data) {
        return of("workflow_step", status, label, merge(node, status, data));
    }

    public static AgentRunEvent tool(String type, String label, String toolName, String status, Map<String, Object> data) {
        return new AgentRunEvent(type, "tool", label, toolName, status, null,
                data == null ? Map.of() : Map.copyOf(data), OffsetDateTime.now());
    }

    private static Map<String, Object> merge(String node, String status, Map<String, Object> data) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        merged.put("node", node);
        if (status != null) {
            merged.put("status", status);
        }
        if (data != null) {
            merged.putAll(data);
        }
        return merged;
    }
}
