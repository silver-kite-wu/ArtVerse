package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChapterNovelRevision;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunArtifact;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.NovelContentRevisionSource;
import com.artverse.persistence.ChapterNovelRevisionRepository;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaAgentRunArtifactRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NovelContentArtifactService {

    public static final String ARTIFACT_TYPE = "NOVEL_CONTENT_DRAFT";
    private static final int MAX_DRAFT_ATTEMPTS = 3;

    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunArtifactRepository artifactRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterNovelRevisionRepository revisionRepository;
    private final NovelContentService novelContentService;
    private final ObjectMapper objectMapper;
    private final AgentRunLeaseService leaseService;

    @Transactional
    public ArtifactView draft(String content, MangaAgentRuntimeContext context) {
        return draft(content, context, null);
    }

    @Transactional
    public ArtifactView draft(String content, MangaAgentRuntimeContext context, Long expectedBaseVersion) {
        return draftInternal(content, context, expectedBaseVersion, Map.of());
    }

    /** The model supplies only a historical revision ID; canonical historical text stays server-owned. */
    @Transactional
    public ArtifactView draftRestore(Long revisionId, MangaAgentRuntimeContext context, Long expectedBaseVersion) {
        var revision = revisionRepository.findByIdAndChapterId(revisionId, context.chapterId())
                .orElseThrow(() -> new BusinessException(404, "Novel revision not found"));
        return draftInternal(revision.getContent(), context, expectedBaseVersion, Map.of(
                "restore_revision_id", revision.getId(), "restore_revision_number", revision.getRevisionNumber(),
                "restore_revision_hash", revision.getContentHash()));
    }

    private ArtifactView draftInternal(String content, MangaAgentRuntimeContext context, Long expectedBaseVersion, Map<String,Object> provenance) {
        String normalized = normalize(content);
        MangaAgentRun run = requireRun(context.userId(), context.chapterId(), context.requestId());
        Chapter chapter = chapterRepository.findByIdAndUserIdForUpdate(context.chapterId(), context.userId())
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        long baseVersion = chapter.getVersion() == null ? 0L : chapter.getVersion();
        if (expectedBaseVersion != null && expectedBaseVersion != baseVersion) {
            throw new BusinessException(409, "生成建议期间正文已变化，请基于最新正文重新优化");
        }
        String contentHash = ToolIdempotencyService.sha256(normalized);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", normalized);
        payload.put("content_hash", contentHash);
        payload.put("base_version", baseVersion);
        payload.put("base_content", chapter.getNovelContent() == null ? "" : chapter.getNovelContent());
        payload.put("word_count", normalized.length());
        payload.put("current_word_count", chapter.getNovelContent() == null ? 0 : chapter.getNovelContent().trim().length());
        payload.put("chapter_id", chapter.getId());
        payload.put("story_id", chapter.getStory().getId());
        if (context.stepId() != null) payload.put("step_id", context.stepId());
        payload.putAll(provenance);
        String payloadJson = writeJson(payload);
        String checksum = ToolIdempotencyService.sha256(payloadJson);
        return artifactRepository.findFirstByRunIdAndChecksumOrderByCreatedAtDesc(run.getId(), checksum)
                .filter(artifact -> !"DISCARDED".equals(artifact.getStatus()))
                .map(this::toView)
                .orElseGet(() -> {
                    long attempts = artifactRepository.countByRunIdAndArtifactType(run.getId(), ARTIFACT_TYPE);
                    if (attempts >= MAX_DRAFT_ATTEMPTS) {
                        throw new BusinessException(409, "Novel content draft rewrite limit reached");
                    }
                    MangaAgentRunArtifact artifact = new MangaAgentRunArtifact();
                    artifact.setRun(run);
                    artifact.setArtifactType(ARTIFACT_TYPE);
                    artifact.setStatus("VALIDATED");
                    artifact.setSchemaVersion("1");
                    artifact.setPayload(payloadJson);
                    artifact.setEvaluation(writeJson(Map.of(
                            "content_hash", contentHash,
                            "base_version", baseVersion,
                            "word_count", normalized.length(),
                            "quality_passed", true
                    )));
                    artifact.setChecksum(checksum);
                    return toView(artifactRepository.save(artifact));
                });
    }

    /** Explicit author review is a deterministic write, independent of model execution/leases. */
    @Transactional
    public CommitView review(Long userId, Long chapterId, UUID requestId, UUID artifactId,
                             String decision, String content, Long baseVersion, String expectedContentHash) {
        MangaAgentRun run = runRepository.findForUpdate(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        if (!java.util.Set.of(MangaAgentRunStatus.WAITING_USER, MangaAgentRunStatus.SUCCEEDED, MangaAgentRunStatus.FAILED)
                .contains(run.getStatus())) {
            throw new BusinessException(409, "任务仍在执行或已停止，请刷新任务状态后重试");
        }
        MangaAgentRunArtifact artifact = artifactRepository.findForCommit(artifactId, userId, chapterId)
                .orElseThrow(() -> new BusinessException(404, "修改草稿不存在"));
        if (!ARTIFACT_TYPE.equals(artifact.getArtifactType())
                || !artifact.getRun().getId().equals(run.getId())) {
            throw new BusinessException(404, "修改草稿不存在");
        }
        Map<String, Object> payload = new LinkedHashMap<>(readMap(artifact.getPayload()));
        String draft = String.valueOf(payload.getOrDefault("content", ""));
        String draftHash = String.valueOf(payload.getOrDefault("content_hash", ""));
        if (!draftHash.equals(expectedContentHash) || !draftHash.equals(ToolIdempotencyService.sha256(draft))) {
            throw new BusinessException(409, "草稿内容已变化，请重新载入后审阅");
        }
        if (!"confirm".equals(decision) && !"discard".equals(decision)) {
            throw new BusinessException(400, "decision must be confirm or discard");
        }
        if (baseVersion == null || baseVersion != longValue(payload.get("base_version"))) {
            throw new BusinessException(409, "草稿基准版本不匹配，请重新载入");
        }
        Chapter chapter = chapterRepository.findByIdAndUserIdForUpdate(chapterId, userId)
                .orElseThrow(() -> new BusinessException(404, "章节不存在"));
        if ("COMMITTED".equals(artifact.getStatus())) {
            String committedHash = String.valueOf(payload.getOrDefault("reviewed_content_hash", draftHash));
            if (!"confirm".equals(decision) || !committedHash.equals(ToolIdempotencyService.sha256(normalize(content)))) {
                throw new BusinessException(409, "这份草稿已经保存，请刷新章节");
            }
            return toCommitView(artifact, false);
        }
        if ("REJECTED".equals(artifact.getStatus()) && "discard".equals(decision)) {
            return toCommitView(artifact, false);
        }
        if (!"VALIDATED".equals(artifact.getStatus())) {
            throw new BusinessException(409, "这份草稿已失效，请重新生成修改建议");
        }
        if ("discard".equals(decision)) {
            payload.put("reviewed_chapter_version", chapter.getVersion());
            payload.put("reviewed_changed", false);
            payload.put("reviewed_decision", "discard");
            artifact.setPayload(writeJson(payload));
            artifact.setChecksum(ToolIdempotencyService.sha256(artifact.getPayload()));
            artifact.setStatus("REJECTED");
            artifactRepository.save(artifact);
            return toCommitView(artifact, false);
        }
        String reviewed = normalize(content);
        payload.put("reviewed_content", reviewed);
        payload.put("reviewed_content_hash", ToolIdempotencyService.sha256(reviewed));
        artifact.setPayload(writeJson(payload));
        artifact.setChecksum(ToolIdempotencyService.sha256(artifact.getPayload()));
        NovelContentService.SaveResult saved = novelContentService.save(chapterId, userId, reviewed, baseVersion,
                payload.get("restore_revision_id") instanceof Number ? NovelContentRevisionSource.RESTORE : NovelContentRevisionSource.AI, null, artifact);
        payload.put("reviewed_chapter_version", saved.chapter().getVersion());
        payload.put("reviewed_changed", saved.changed());
        payload.put("reviewed_decision", "confirm");
        // A no-op save may return an older revision. It does not belong to this review.
        if (saved.changed() && saved.revision() != null) {
            payload.put("reviewed_revision_id", saved.revision().getId());
            payload.put("reviewed_revision_number", saved.revision().getRevisionNumber());
        }
        artifact.setPayload(writeJson(payload));
        artifact.setChecksum(ToolIdempotencyService.sha256(artifact.getPayload()));
        artifact.setStatus("COMMITTED");
        artifactRepository.findByRunIdOrderByCreatedAtAsc(artifact.getRun().getId()).stream()
                .filter(other -> !other.getId().equals(artifact.getId()))
                .filter(other -> ARTIFACT_TYPE.equals(other.getArtifactType()) && "VALIDATED".equals(other.getStatus()))
                .forEach(other -> other.setStatus("SUPERSEDED"));
        artifactRepository.save(artifact);
        return toCommitView(artifact, saved.changed());
    }

    /** Immutable domain evidence used to repair a missing run projection; never saves chapter text. */
    @Transactional(readOnly = true)
    public Map<String, Object> reviewReceipt(Long userId, Long chapterId, UUID requestId, UUID artifactId) {
        var artifact = reviewedArtifact(userId, chapterId, requestId, artifactId);
        var payload = readMap(artifact.getPayload());
        var result = toCommitView(artifact, Boolean.TRUE.equals(payload.get("reviewed_changed")));
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("artifact_id", artifactId.toString());
        receipt.put("decision", "COMMITTED".equals(result.status()) ? "confirm" : "discard");
        receipt.put("status", result.status());
        receipt.put("chapter_id", chapterId);
        receipt.put("chapter_version", result.chapterVersion());
        receipt.put("base_version", longValue(payload.get("base_version")));
        receipt.put("content_hash", result.contentHash());
        receipt.put("changed", result.changed());
        if ("COMMITTED".equals(result.status())) {
            receipt.put("word_count", verifiedReviewedContent(artifact, payload, result.contentHash()).length());
        }
        if (result.revisionId() != null) receipt.put("revision_id", result.revisionId());
        if (result.revisionNumber() != null) receipt.put("revision_number", result.revisionNumber());
        return java.util.Collections.unmodifiableMap(receipt);
    }

    @Transactional(readOnly = true)
    public String reviewedContent(Long userId, Long chapterId, UUID requestId, UUID artifactId, String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new BusinessException(400, "审阅结果哈希不能为空");
        }
        var artifact = reviewedArtifact(userId, chapterId, requestId, artifactId);
        var payload = readMap(artifact.getPayload());
        return verifiedReviewedContent(artifact, payload, expectedHash);
    }

    private MangaAgentRunArtifact reviewedArtifact(Long userId, Long chapterId, UUID requestId, UUID artifactId) {
        if (requestId == null || artifactId == null) throw new BusinessException(400, "审阅结果引用不能为空");
        var artifact = artifactRepository.findByArtifactUuidAndRunUserIdAndRunChapterId(artifactId, userId, chapterId)
                .orElseThrow(() -> new BusinessException(404, "审阅结果不存在"));
        if (!ARTIFACT_TYPE.equals(artifact.getArtifactType()) || !requestId.equals(artifact.getRun().getRequestId())
                || !java.util.Set.of("COMMITTED", "REJECTED").contains(artifact.getStatus())) {
            throw new BusinessException(409, "请先完成这份草稿的人工审阅");
        }
        return artifact;
    }

    private String verifiedReviewedContent(MangaAgentRunArtifact artifact, Map<String, Object> payload, String expectedHash) {
        String content = payload.get("reviewed_content") instanceof String value ? value : null;
        if (content == null) {
            content = revisionRepository.findByAgentRunArtifactId(artifact.getId())
                    .map(ChapterNovelRevision::getContent).orElse(null);
        }
        if (!"COMMITTED".equals(artifact.getStatus()) || content == null || content.isBlank()
                || !expectedHash.equals(payload.get("reviewed_content_hash"))
                || !expectedHash.equals(ToolIdempotencyService.sha256(content))) {
            throw new BusinessException(409, "审阅结果与保存回执不一致");
        }
        return content;
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> list(Long userId, Long chapterId, UUID requestId) {
        MangaAgentRun run = requireRun(userId, chapterId, requestId);
        return artifactRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(artifact -> ARTIFACT_TYPE.equals(artifact.getArtifactType()))
                .map(this::toView)
                .toList();
    }

    private MangaAgentRun requireRun(Long userId, Long chapterId, UUID requestId) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        return runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    private ArtifactView toView(MangaAgentRunArtifact artifact) {
        return new ArtifactView(artifact.getArtifactUuid(), artifact.getRun().getRequestId(),
                artifact.getArtifactType(), artifact.getStatus(), artifact.getSchemaVersion(),
                readMap(artifact.getPayload()), readMap(artifact.getEvaluation()), artifact.getChecksum());
    }

    private CommitView toCommitView(MangaAgentRunArtifact artifact, boolean changed) {
        var payload = readMap(artifact.getPayload());
        if (!(payload.get("reviewed_chapter_version") instanceof Number savedVersion)) {
            throw new BusinessException(409, "原审阅保存版本证据不完整，请查看历史审阅结果");
        }
        var revision = "COMMITTED".equals(artifact.getStatus())
                ? revisionRepository.findByAgentRunArtifactId(artifact.getId()).orElse(null) : null;
        return new CommitView(artifact.getArtifactUuid(), changed, savedVersion.longValue(),
                revision == null ? null : revision.getId(),
                revision == null ? null : revision.getRevisionNumber(),
                String.valueOf(readMap(artifact.getPayload()).getOrDefault("reviewed_content_hash",
                        readMap(artifact.getPayload()).getOrDefault("content_hash", ""))), artifact.getStatus());
    }

    private String normalize(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(400, "Novel content draft cannot be empty");
        }
        return content.trim();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize novel content artifact", error);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Failed to read novel content artifact", error);
        }
    }

    public record ArtifactView(UUID artifactId, UUID requestId, String type, String status,
                               String schemaVersion, Map<String, Object> payload,
                               Map<String, Object> evaluation, String checksum) { }

    public record CommitView(UUID artifactId, boolean changed, Long chapterVersion, Long revisionId,
                             Integer revisionNumber, String contentHash, String status) { }
}
