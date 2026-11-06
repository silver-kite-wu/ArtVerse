package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Model-facing artifact views never return the persisted execution, overwrite or read credentials. */
@Service
@RequiredArgsConstructor
public class AgentArtifactAccessService {
    private static final String STORYBOARD_ARTIFACT_TYPE = "STORYBOARD_DRAFT";
    private final MangaAgentRunService runs;
    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunArtifactRepository artifacts;
    private final ObjectMapper mapper;
    private final AgentToolEffectService effects;

    @Transactional
    public Map<String,Object> get(MangaAgentRuntimeContext context, UUID id, String cursor, Integer requestedLimit) {
        active(context);
        var artifact = scoped(context, id, false);
        var result = summary(artifact); var payload = read(artifact.getPayload());
        String content;
        if (NovelContentArtifactService.ARTIFACT_TYPE.equals(artifact.getArtifactType()))
            content = Objects.toString(payload.get("reviewed_content"), Objects.toString(payload.get("content"), ""));
        else if (STORYBOARD_ARTIFACT_TYPE.equals(artifact.getArtifactType()))
            content = json(Map.of("pages", payload.getOrDefault("pages", List.of()), "scenes", payload.getOrDefault("scenes", List.of())));
        else if(CreativeProposalArtifacts.TYPES.contains(artifact.getArtifactType())) {
            var projected=new LinkedHashMap<String,Object>();
            for(String key:List.of("before","after","base_version","character_id","mode","selection","targets","request_id","operation_id","recovery_action","recovery_version","model_calls","estimated_cost","quota_availability"))if(payload.containsKey(key))projected.put(key,payload.get(key));
            var receipt=new LinkedHashMap<>(read(artifact.getEvaluation()));receipt.remove("commands");projected.put("author_review_receipt",receipt);content=json(projected);
        }
        else throw denied();
        int limit = limit(requestedLimit, 8000, 8000), offset = 0;
        String binding = id + ":" + version(artifact);
        if (cursor != null && !cursor.isBlank()) { var decoded = decode(cursor); if (!binding.equals(decoded.get("scope"))) throw stale(); offset = number(decoded.get("offset")); }
        if (offset < 0 || offset > content.length() || offset > 0 && offset < content.length() && Character.isLowSurrogate(content.charAt(offset))) throw stale();
        int end = Math.min(content.length(), offset + limit);
        if (end < content.length() && end > offset && Character.isHighSurrogate(content.charAt(end - 1)) && Character.isLowSurrogate(content.charAt(end))) end--;
        if (end == offset && end < content.length()) end = Math.min(content.length(), offset + 2);
        result.put("content", content.substring(offset, end)); result.put("content_length", content.length());
        result.put("start", offset); result.put("end", end); result.put("offset_unit", "UTF16_CHARACTERS");
        result.put("content_format", NovelContentArtifactService.ARTIFACT_TYPE.equals(artifact.getArtifactType()) ? "text" : "json");
        result.put("has_more", end < content.length()); result.put("next_cursor", end < content.length() ? encode(Map.of("scope", binding, "offset", end)) : "");
        return result;
    }

    @Transactional
    public Map<String,Object> list(MangaAgentRuntimeContext context, String type, String status, String cursor, Integer requestedLimit) {
        var run = active(context); int limit = limit(requestedLimit, 20, 50);
        if (type != null && !Set.of(NovelContentArtifactService.ARTIFACT_TYPE, STORYBOARD_ARTIFACT_TYPE).contains(type)&&!CreativeProposalArtifacts.TYPES.contains(type)) throw new BusinessException(400, "不支持的草稿类型");
        if (status != null && !Set.of("DRAFT", "VALIDATED", "COMMITTED", "REJECTED", "SUPERSEDED", "DISCARDED").contains(status)) throw new BusinessException(400, "不支持的草稿状态");
        String scope = ToolIdempotencyService.sha256(context.userId() + ":" + context.conversationId() + ":" + run.getId() + ":" + type + ":" + status);
        String snapshot = ToolIdempotencyService.sha256(json(artifacts.agentSnapshot(run.getId(), type, status)));
        long upper = artifacts.highestId(run.getId()); Long before = null;
        if (cursor != null && !cursor.isBlank()) { var value = decode(cursor); if (!scope.equals(value.get("scope")) || !snapshot.equals(value.get("snapshot"))) throw stale(); upper = longNumber(value.get("upper")); before = longNumber(value.get("before")); }
        var rows = artifacts.agentPage(run.getId(), upper, before, type, status, PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit; var page = rows.stream().limit(limit).toList();
        return Map.of("items", page.stream().map(this::summary).toList(), "has_more", more, "snapshot", snapshot,
                "next_cursor", more ? encode(Map.of("scope", scope, "snapshot", snapshot, "upper", upper, "before", page.getLast().getId())) : "");
    }

    @Transactional
    public Map<String,Object> discard(MangaAgentRuntimeContext context, UUID id, String expectedVersion) {
        return discard(context, id, expectedVersion, null);
    }

    @Transactional
    public Map<String,Object> discard(MangaAgentRuntimeContext context, UUID id, String expectedVersion, AgentToolAttempt attempt) {
        if (attempt != null && !"discard_artifact".equals(attempt.toolName())) throw denied();
        var run = active(context);
        if (!Set.of(AgentTaskType.MANGA_STORYBOARD, AgentTaskType.STORY_CHAT_WRITE).contains(context.taskType())) throw denied();
        var artifact = scoped(context, id, true); var evaluation = read(artifact.getEvaluation());
        if (!run.getId().equals(artifact.getRun().getId()) || run.getUserInputRequestJson() != null
                || read(run.getRunAttributesJson()).containsKey("review_result") || evaluation.containsKey("overwrite_authorization")) throw denied();
        String target = context.taskType() == AgentTaskType.STORY_CHAT_WRITE ? NovelContentArtifactService.ARTIFACT_TYPE : STORYBOARD_ARTIFACT_TYPE;
        if (!target.equals(artifact.getArtifactType())) throw denied();
        var payload = read(artifact.getPayload());
        if (payload.get("step_id") instanceof String step && !step.equals(context.stepId())) throw denied();
        if (expectedVersion == null || expectedVersion.isBlank()) throw new BusinessException(400, "expected_artifact_version不能为空");
        if ("DISCARDED".equals(artifact.getStatus()) && expectedVersion.equals(evaluation.get("discard_original_version"))) {
            recordDiscard(context, artifact, expectedVersion, attempt);
            return summary(artifact);
        }
        if (!Set.of("DRAFT", "VALIDATED").contains(artifact.getStatus()) || !expectedVersion.equals(version(artifact))) throw stale();
        if (payload.keySet().stream().anyMatch(key -> key.startsWith("reviewed_"))) throw denied();
        evaluation.put("discard_original_version", expectedVersion); artifact.setEvaluation(json(evaluation));
        artifact.setStatus("DISCARDED"); artifacts.saveAndFlush(artifact);
        recordDiscard(context, artifact, expectedVersion, attempt);
        return summary(artifact);
    }

    private void recordDiscard(MangaAgentRuntimeContext context, MangaAgentRunArtifact artifact, String expectedVersion, AgentToolAttempt attempt) {
        effects.record(attempt, context, "ARTIFACT_DISCARD", artifact.getArtifactUuid(), artifact.getArtifactType(), "DISCARDED",
                artifact.getChecksum(), Map.of("original_artifact_version", expectedVersion));
    }

    private MangaAgentRun active(MangaAgentRuntimeContext context) {
        runs.lockAutomationExecution(context);
        var run = runRepository.findForUpdate(context.userId(), context.chapterId(), context.requestId()).orElseThrow(AgentArtifactAccessService::denied);
        runs.requireToolExecution(context, read(run.getRunAttributesJson()).get("review_result") instanceof Map<?,?>); return run;
    }
    private MangaAgentRunArtifact scoped(MangaAgentRuntimeContext context, UUID id, boolean lock) {
        var artifact = (lock ? artifacts.findForCommit(id, context.userId(), context.chapterId())
                : artifacts.findByArtifactUuidAndRunUserIdAndRunChapterId(id, context.userId(), context.chapterId())).orElseThrow(AgentArtifactAccessService::denied);
        var run = artifact.getRun();
        if (!context.storyId().equals(run.getStory().getId()) || !context.conversationId().equals(run.getConversation().getConversationUuid())) throw denied(); return artifact;
    }
    private LinkedHashMap<String,Object> summary(MangaAgentRunArtifact artifact) {
        var result = new LinkedHashMap<String,Object>(); var payload = read(artifact.getPayload()); var evaluation = read(artifact.getEvaluation());
        result.put("artifact_id", artifact.getArtifactUuid().toString()); result.put("artifact_type", artifact.getArtifactType());
        result.put("status", artifact.getStatus()); result.put("schema_version", artifact.getSchemaVersion());
        result.put("artifact_version", version(artifact)); result.put("created_at", Objects.toString(artifact.getCreatedAt(), ""));
        for (String key : List.of("base_version", "content_hash", "word_count", "restore_revision_id", "reviewed_chapter_version", "reviewed_decision", "reviewed_changed"))
            if (payload.containsKey(key)) result.put(key, payload.get(key));
        for (String key : List.of("structure_valid", "quality_passed", "page_count")) if (evaluation.containsKey(key)) result.put(key, evaluation.get(key));
        return result;
    }
    private String version(MangaAgentRunArtifact artifact) { return ToolIdempotencyService.sha256(artifact.getChecksum() + ":" + artifact.getUpdatedAt() + ":" + artifact.getStatus()); }
    private int limit(Integer value, int fallback, int max) { int result = value == null ? fallback : value; if (result < 1 || result > max) throw new BusinessException(400, "分页大小超出范围"); return result; }
    private long longNumber(Object value) { if (!(value instanceof Number number) || number.longValue() < 0 || number.doubleValue() != number.longValue()) throw stale(); return number.longValue(); }
    private int number(Object value) { long number = longNumber(value); if (number > Integer.MAX_VALUE) throw stale(); return (int) number; }
    private Map<String,Object> read(String value) { try { return value == null ? new LinkedHashMap<>() : mapper.readValue(value, new TypeReference<LinkedHashMap<String,Object>>() {}); } catch (Exception e) { throw stale(); } }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String encode(Object value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(json(value).getBytes(StandardCharsets.UTF_8)); }
    private Map<String,Object> decode(String value) { try { if (value.length() > 2048) throw stale(); return read(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)); } catch (RuntimeException e) { throw stale(); } }
    private static BusinessException denied() { return new BusinessException(403, "SCOPE_DENIED: artifact scope or review authority differs"); }
    private static BusinessException stale() { return new BusinessException(409, "草稿版本或分页范围已变化，请重新读取"); }
}
