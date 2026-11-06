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

/** Historical text is immutable input; restoring it always produces an ordinary author-review draft. */
@Service
@RequiredArgsConstructor
public class NovelHistoryAgentService {
    private final MangaAgentRunService runs;
    private final MangaAgentRunRepository runRepository;
    private final ChapterRepository chapters;
    private final ChapterNovelRevisionRepository revisions;
    private final NovelModelReadService reads;
    private final NovelContentArtifactService drafts;
    private final ObjectMapper mapper;
    private final AgentToolEffectService effects;

    @Transactional
    public Map<String,Object> list(MangaAgentRuntimeContext context, String cursor, Integer requestedLimit) {
        scope(context); int limit = limit(requestedLimit, 20, 50); Long before = null;
        String scope = ToolIdempotencyService.sha256(context.userId() + ":" + context.storyId() + ":" + context.chapterId() + ":" + context.conversationId() + ":" + context.requestId());
        if (cursor != null && !cursor.isBlank()) { var value = decode(cursor); if (!scope.equals(value.get("scope"))) throw invalid(); before = positive(value.get("before")); }
        var rows = revisions.agentPage(context.chapterId(), before, PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit; var page = rows.stream().limit(limit).toList();
        return Map.of("items", page.stream().map(this::summary).toList(), "has_more", more,
                "next_cursor", more ? encode(Map.of("scope", scope, "before", page.getLast().getId())) : "");
    }

    @Transactional
    public Map<String,Object> get(MangaAgentRuntimeContext context, Long id, String cursor, Integer requestedLimit) {
        requireRevision(id); scope(context); var revision = revisions.findByIdAndChapterId(id, context.chapterId()).orElseThrow(() -> new BusinessException(404, "Novel revision not found"));
        int limit = limit(requestedLimit, 8000, 8000); int offset = 0;
        String scope = ToolIdempotencyService.sha256(context.userId() + ":" + context.chapterId() + ":" + context.conversationId() + ":" + context.requestId() + ":" + id + ":" + revision.getContentHash());
        if (cursor != null && !cursor.isBlank()) { var value = decode(cursor); if (!scope.equals(value.get("scope"))) throw invalid(); long raw = positive(value.get("offset")); if (raw > Integer.MAX_VALUE) throw invalid(); offset = (int) raw; }
        String content = revision.getContent(); if (offset > content.length() || offset > 0 && offset < content.length() && Character.isLowSurrogate(content.charAt(offset))) throw invalid();
        int end = Math.min(content.length(), offset + limit);
        if (end < content.length() && end > offset && Character.isHighSurrogate(content.charAt(end - 1)) && Character.isLowSurrogate(content.charAt(end))) end--;
        if (end == offset && end < content.length()) end = Math.min(content.length(), offset + 2);
        var result = new LinkedHashMap<>(summary(revision));
        result.put("content", content.substring(offset, end)); result.put("content_length", content.length());
        result.put("start", offset); result.put("end", end); result.put("offset_unit", "UTF16_CHARACTERS");
        result.put("has_more", end < content.length()); result.put("next_cursor", end < content.length() ? encode(Map.of("scope", scope, "offset", end)) : ""); return result;
    }

    @Transactional
    public NovelContentArtifactService.ArtifactView draft(String content, MangaAgentRuntimeContext context) {
        requireWrite(context); long version = reads.requireCurrentBaseline(context);
        return drafts.draft(content, context, version);
    }

    @Transactional
    public NovelContentArtifactService.ArtifactView restore(Long id, MangaAgentRuntimeContext context) {
        return restore(id, context, null);
    }

    @Transactional
    public NovelContentArtifactService.ArtifactView restore(Long id, MangaAgentRuntimeContext context, AgentToolAttempt attempt) {
        if (attempt != null && !"draft_novel_restore".equals(attempt.toolName())) throw new BusinessException(403, "TOOL_EFFECT_DENIED");
        requireRevision(id); requireWrite(context); long version = reads.requireCurrentBaseline(context);
        var artifact = drafts.draftRestore(id, context, version);
        effects.record(attempt, context, "NOVEL_RESTORE_DRAFT", artifact.artifactId(), artifact.type(), artifact.status(),
                artifact.checksum(), Map.of("base_version", version, "restore_revision_id", id,
                        "content_hash", artifact.payload().get("content_hash")));
        return artifact;
    }

    private void requireWrite(MangaAgentRuntimeContext context) { if (context.taskType() != AgentTaskType.STORY_CHAT_WRITE) throw new BusinessException(403, "TOOL_NOT_ALLOWED: current target is not novel text"); scope(context); }
    private void scope(MangaAgentRuntimeContext context) {
        runs.lockAutomationExecution(context);
        var run = runRepository.findForUpdate(context.userId(), context.chapterId(), context.requestId()).orElseThrow(() -> new BusinessException(403, "SCOPE_DENIED"));
        runs.requireToolExecution(context, false);
        var chapter = chapters.findByIdAndUserId(context.chapterId(), context.userId()).orElseThrow(() -> new BusinessException(403, "SCOPE_DENIED"));
        if (!context.storyId().equals(chapter.getStory().getId())) throw new BusinessException(403, "SCOPE_DENIED");
    }
    private Map<String,Object> summary(ChapterNovelRevision value) { return Map.of("revision_id", value.getId(), "revision_number", value.getRevisionNumber(), "content_hash", value.getContentHash(), "source", value.getSource().name(), "created_at", Objects.toString(value.getCreatedAt(), ""), "historical", true); }
    private int limit(Integer value, int fallback, int max) { int result = value == null ? fallback : value; if (result < 1 || result > max) throw invalid(); return result; }
    private void requireRevision(Long id) { if (id == null || id <= 0) throw new BusinessException(400, "revision_id必须是正整数"); }
    private long positive(Object value) { if (!(value instanceof Number number) || number.longValue() < 0 || number.doubleValue() != number.longValue()) throw invalid(); return number.longValue(); }
    private String encode(Object value) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(value)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private Map<String,Object> decode(String value) { try { if (value.length() > 2048) throw invalid(); return mapper.readValue(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8), new TypeReference<Map<String,Object>>() {}); } catch (Exception e) { throw invalid(); } }
    private static BusinessException invalid() { return new BusinessException(400, "历史版本分页范围或大小无效，请重新读取"); }
}
