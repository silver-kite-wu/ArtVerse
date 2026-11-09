package com.artverse.application;

import com.artverse.agent.AgentToolAttempt;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Domain evidence commits with the mutation; native result observations cannot erase it. */
@Service
@RequiredArgsConstructor
public class AgentToolEffectService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AgentToolAttempt attempt, MangaAgentRuntimeContext context, String effectKind,
                       UUID artifactId, String artifactType, String status, String contentHash, Map<String,Object> details) {
        if (attempt == null) return; // Deterministic application APIs do not invent native attempts.
        attempt.requireScope(context);
        if (artifactId == null || effectKind == null || artifactType == null || status == null
                || contentHash == null || contentHash.length() != 64) throw denied();
        String slot = ToolIdempotencyService.sha256(context.taskType().name() + ":" + context.stepId());
        UUID id = UUID.nameUUIDFromBytes((slot + ":" + attempt.callId()).getBytes(StandardCharsets.UTF_8));
        var rows = jdbc.queryForList("""
                SELECT id,metadata,step_id,task_type,attempt_hash FROM agent_usage_ledger
                WHERE user_id=? AND root_request_id=? AND request_id=? AND usage_kind='TOOL_CALL'
                  AND attempt_id=? FOR UPDATE
                """, context.userId(), context.requestId(), context.requestId(), id);
        if (rows.size() != 1) throw denied();
        var row = rows.getFirst();
        JsonNode metadata = read(row.get("metadata"));
        if (!slot.equals(row.get("step_id")) || !context.taskType().name().equals(row.get("task_type"))
                || !attempt.inputHash().equals(row.get("attempt_hash"))
                || !attempt.toolName().equals(metadata.path("tool").asText())) throw denied();
        var receipt = new LinkedHashMap<String,Object>();
        receipt.put("schema", 1); receipt.put("effect_kind", effectKind); receipt.put("artifact_id", artifactId.toString());
        receipt.put("artifact_type", artifactType); receipt.put("status", status); receipt.put("content_hash", contentHash);
        receipt.put("chapter_id", context.chapterId()); receipt.put("story_id", context.storyId());
        receipt.put("request_id", context.requestId().toString()); receipt.put("step_id", context.stepId());
        receipt.put("call_id", attempt.callId()); receipt.put("tool", attempt.toolName()); receipt.put("input_hash", attempt.inputHash());
        receipt.put("details", details == null ? Map.of() : details);
        JsonNode formed = read(json(receipt));
        if (metadata.has("domain_effect") && !metadata.get("domain_effect").equals(formed)) throw denied();
        jdbc.update("UPDATE agent_usage_ledger SET metadata=jsonb_set(metadata,'{domain_effect}',?::jsonb,true) WHERE id=?",
                json(formed), row.get("id"));
    }

    /** Attach only actual immutable author evidence, without pretending the author executed a model tool. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAuthorReview(Long actor, Long chapterId, UUID requestId, UUID artifactId, Map<String,Object> receipt) {
        if (receipt == null || !artifactId.toString().equals(String.valueOf(receipt.get("artifact_id")))
                || !java.util.Set.of("COMMITTED", "REJECTED").contains(receipt.get("status"))) throw denied();
        var rows = jdbc.queryForList("""
                SELECT id,metadata FROM agent_usage_ledger WHERE user_id=? AND root_request_id=?
                  AND usage_kind='TOOL_CALL' AND metadata->'domain_effect'->>'artifact_id'=?
                  AND metadata->'domain_effect'->>'chapter_id'=? FOR UPDATE
                """, actor, requestId, artifactId.toString(), chapterId.toString());
        JsonNode formed = read(json(receipt));
        for (var row : rows) {
            JsonNode metadata = read(row.get("metadata"));
            if (metadata.has("author_review_receipt") && !metadata.get("author_review_receipt").equals(formed)) throw denied();
            jdbc.update("UPDATE agent_usage_ledger SET metadata=jsonb_set(metadata,'{author_review_receipt}',?::jsonb,true) WHERE id=?",
                    json(formed), row.get("id"));
        }
    }

    /** Record an AI auto-approval separately so audit data never claims a human reviewed it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAiAutoReview(Long actor, Long chapterId, UUID requestId, UUID artifactId, Map<String,Object> receipt) {
        if (receipt == null || !artifactId.toString().equals(String.valueOf(receipt.get("artifact_id")))
                || !java.util.Set.of("COMMITTED", "REJECTED").contains(receipt.get("status"))) throw denied();
        var rows = jdbc.queryForList("""
                SELECT id,metadata FROM agent_usage_ledger WHERE user_id=? AND root_request_id=?
                  AND usage_kind='TOOL_CALL' AND metadata->'domain_effect'->>'artifact_id'=?
                  AND metadata->'domain_effect'->>'chapter_id'=? FOR UPDATE
                """, actor, requestId, artifactId.toString(), chapterId.toString());
        JsonNode formed = read(json(receipt));
        for (var row : rows) {
            JsonNode metadata = read(row.get("metadata"));
            if (metadata.has("ai_auto_review_receipt") && !metadata.get("ai_auto_review_receipt").equals(formed)) throw denied();
            jdbc.update("UPDATE agent_usage_ledger SET metadata=jsonb_set(metadata,'{ai_auto_review_receipt}',?::jsonb,true) WHERE id=?",
                    json(formed), row.get("id"));
        }
    }

    private static JsonNode read(Object value) { try { return JSON.readTree(value.toString()); } catch (Exception e) { throw denied(); } }
    private static String json(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception e) { throw denied(); } }
    private static BusinessException denied() { return new BusinessException(409, "TOOL_EFFECT_DENIED: domain receipt does not match the reserved native attempt"); }
}
