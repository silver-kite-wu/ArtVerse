package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NovelContentArtifactReviewTest {
    private final MangaAgentRunRepository runs = mock(MangaAgentRunRepository.class);
    private final MangaAgentRunArtifactRepository artifacts = mock(MangaAgentRunArtifactRepository.class);
    private final ChapterRepository chapters = mock(ChapterRepository.class);
    private final ChapterNovelRevisionRepository revisions = mock(ChapterNovelRevisionRepository.class);
    private final NovelContentService contents = mock(NovelContentService.class);
    private final AgentRunLeaseService leases = mock(AgentRunLeaseService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final NovelContentArtifactService service = new NovelContentArtifactService(runs, artifacts, chapters, revisions, contents, mapper, leases);
    private final UUID requestId = UUID.randomUUID(), artifactId = UUID.randomUUID();
    private final MangaAgentRun run = new MangaAgentRun();
    private final MangaAgentRunArtifact artifact = new MangaAgentRunArtifact();
    private final Chapter chapter = new Chapter();
    private final String hash = ToolIdempotencyService.sha256("AI修改");

    @BeforeEach
    void setup() throws Exception {
        run.setId(8L); run.setRequestId(requestId); run.setStatus(MangaAgentRunStatus.WAITING_USER);
        when(runs.findForUpdate(3L, 1L, requestId)).thenReturn(Optional.of(run));
        artifact.setId(11L); artifact.setArtifactUuid(artifactId); artifact.setRun(run);
        artifact.setArtifactType(NovelContentArtifactService.ARTIFACT_TYPE); artifact.setStatus("VALIDATED");
        artifact.setPayload(mapper.writeValueAsString(Map.of("content", "AI修改", "content_hash", hash, "base_version", 6L, "base_content", "原文")));
        chapter.setId(1L); chapter.setVersion(6L); chapter.setNovelContent("原文");
        Story story = new Story(); story.setId(2L); chapter.setStory(story);
        when(artifacts.findForCommit(artifactId, 3L, 1L)).thenReturn(Optional.of(artifact));
        when(artifacts.findByArtifactUuidAndRunUserIdAndRunChapterId(artifactId, 3L, 1L)).thenReturn(Optional.of(artifact));
        when(chapters.findByIdAndUserIdForUpdate(1L, 3L)).thenReturn(Optional.of(chapter));
        when(artifacts.findByRunIdOrderByCreatedAtAsc(8L)).thenReturn(List.of(artifact));
        when(artifacts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(revisions.findByAgentRunArtifactId(11L)).thenReturn(Optional.empty());
    }

    @Test
    void savesExactReviewedTextAndRetainsOriginalProposalWithoutCallingLeaseOrModel() throws Exception {
        when(contents.save(1L, 3L, "作者审阅后的正文", 6L, NovelContentRevisionSource.AI, null, artifact))
                .thenReturn(new NovelContentService.SaveResult(chapter, null, true));
        var result = service.review(3L, 1L, requestId, artifactId, "confirm", "作者审阅后的正文", 6L, hash);
        assertThat(result.changed()).isTrue();
        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.contentHash()).isEqualTo(ToolIdempotencyService.sha256("作者审阅后的正文"));
        var payload = mapper.readTree(artifact.getPayload());
        assertThat(payload.get("content").asText()).isEqualTo("AI修改");
        assertThat(payload.get("reviewed_content").asText()).isEqualTo("作者审阅后的正文");
        verifyNoInteractions(leases);
    }

    @Test
    void repeatedConfirmationDoesNotCreateAnotherRevisionButRejectsDifferentContent() throws Exception {
        artifact.setStatus("COMMITTED");
        artifact.setPayload(mapper.writeValueAsString(Map.of("content", "AI修改", "content_hash", hash,
                "base_version", 6L, "reviewed_chapter_version", 7L, "reviewed_content_hash", ToolIdempotencyService.sha256("作者正文"))));
        chapter.setVersion(12L);
        var replay = service.review(3L, 1L, requestId, artifactId, "confirm", "作者正文", 6L, hash);
        assertThat(replay.changed()).isFalse();
        assertThat(replay.chapterVersion()).isEqualTo(7L);
        assertThatThrownBy(() -> service.review(3L, 1L, requestId, artifactId, "confirm", "其他正文", 6L, hash))
                .hasMessageContaining("已经保存");
        verifyNoInteractions(contents);
    }

    @Test
    void rejectsWrongRequestHashVersionAndDiscardedDraftBeforeSaving() {
        assertThatThrownBy(() -> service.review(3L, 1L, UUID.randomUUID(), artifactId, "confirm", "正文", 6L, hash)).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.review(3L, 1L, requestId, artifactId, "confirm", "正文", 6L, "wrong")).hasMessageContaining("内容已变化");
        assertThatThrownBy(() -> service.review(3L, 1L, requestId, artifactId, "confirm", "正文", 7L, hash)).hasMessageContaining("基准版本");
        artifact.setStatus("REJECTED");
        assertThatThrownBy(() -> service.review(3L, 1L, requestId, artifactId, "confirm", "正文", 6L, hash)).hasMessageContaining("已失效");
        verifyNoInteractions(contents);
    }

    @Test
    void discardWorksEvenIfChapterChangedAndIsIdempotent() {
        chapter.setVersion(8L);
        assertThat(service.review(3L, 1L, requestId, artifactId, "discard", null, 6L, hash).status()).isEqualTo("REJECTED");
        chapter.setVersion(12L);
        assertThat(service.review(3L, 1L, requestId, artifactId, "discard", null, 6L, hash).chapterVersion()).isEqualTo(8L);
        verifyNoInteractions(contents);
    }

    @Test
    void originalReviewedContentDoesNotFollowLaterCanonicalEditsAndMissingHashIsABusinessError() throws Exception {
        when(contents.save(1L, 3L, "作者最终稿", 6L, NovelContentRevisionSource.AI, null, artifact))
                .thenReturn(new NovelContentService.SaveResult(chapter, null, false));
        service.review(3L, 1L, requestId, artifactId, "confirm", "作者最终稿", 6L, hash);
        chapter.setNovelContent("后来的人工版本"); chapter.setVersion(10L);
        assertThat(service.reviewedContent(3L, 1L, requestId, artifactId, ToolIdempotencyService.sha256("作者最终稿")))
                .isEqualTo("作者最终稿");
        assertThat(service.reviewReceipt(3L, 1L, requestId, artifactId))
                .containsEntry("chapter_version", 6L).containsEntry("changed", false).doesNotContainKey("revision_id");
        assertThatThrownBy(() -> service.reviewedContent(3L, 1L, requestId, artifactId, null))
                .isInstanceOf(com.artverse.common.BusinessException.class).hasMessageContaining("哈希不能为空");
    }

    @Test
    void incompleteLegacyReceiptCannotBorrowTodaysChapterVersion() throws Exception {
        artifact.setStatus("COMMITTED");
        artifact.setPayload(mapper.writeValueAsString(Map.of("content", "AI修改", "content_hash", hash,
                "base_version", 6L, "reviewed_content_hash", ToolIdempotencyService.sha256("作者正文"))));
        chapter.setVersion(12L);
        assertThatThrownBy(() -> service.review(3L, 1L, requestId, artifactId, "confirm", "作者正文", 6L, hash))
                .isInstanceOf(com.artverse.common.BusinessException.class).hasMessageContaining("证据不完整");
        verifyNoInteractions(contents);
    }

    @Test
    void changedChapterStillGoesThroughCanonicalVersionConflictCheck() {
        when(contents.save(1L, 3L, "正文", 6L, NovelContentRevisionSource.AI, null, artifact))
                .thenThrow(new com.artverse.common.BusinessException(409, "正文版本已变化"));
        assertThatThrownBy(() -> service.review(3L, 1L, requestId, artifactId, "confirm", "正文", 6L, hash)).hasMessageContaining("版本已变化");
        assertThat(artifact.getStatus()).isEqualTo("VALIDATED");
    }

    @Test
    void generationRejectsVersionDriftBeforePersistingArtifact() {
        when(runs.findByUserIdAndChapterIdAndRequestId(3L, 1L, requestId)).thenReturn(Optional.of(run));
        var context = new MangaAgentRuntimeContext(3L, 2L, 1L, UUID.randomUUID(), requestId, null);
        assertThatThrownBy(() -> service.draft("AI修改", context, 5L)).hasMessageContaining("生成建议期间正文已变化");
        verify(artifacts, never()).save(any());
    }
}
