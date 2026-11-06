package com.artverse.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Public activity records need metadata, while the model still receives the full tool result. */
final class StoryChatToolAuditSummary {
    private static final Set<String> TOOLS = Set.of(
            "get_novel_context", "draft_novel_content", "draft_novel_restore", "get_novel_revision", "list_novel_revisions",
            "get_artifact", "list_artifacts", "discard_artifact", "commit_novel_content", "ask_user");
    private static final Set<String> FIELDS = Set.of(
            "story_id", "chapter_id", "chapter_number", "chapter_display_name", "chapter_version",
            "artifact_id", "artifact_type", "status", "base_version", "content_hash", "word_count",
            "changed", "revision_id", "revision_number", "restore_revision_id", "artifact_version", "has_more",
            "historical", "content_length", "success", "errorCode", "retryable");

    private StoryChatToolAuditSummary() { }

    static Map<String, Object> project(String toolName, Map<String, Object> result) {
        if (result == null) return Map.of();
        if (!TOOLS.contains(toolName)) return result;
        Map<String, Object> summary = new LinkedHashMap<>();
        FIELDS.forEach(key -> {
            Object value = result.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) summary.put(key, value);
        });
        return Map.copyOf(summary);
    }
}
