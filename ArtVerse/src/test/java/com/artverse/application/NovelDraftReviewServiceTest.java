package com.artverse.application;

import com.artverse.domain.*;
import com.artverse.persistence.MangaAgentRunRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NovelDraftReviewServiceTest {
    private final StoryChatPreparationService preparation = mock(StoryChatPreparationService.class);
    private final MangaAgentRunRepository runs = mock(MangaAgentRunRepository.class);
    private final MangaAgentRunService lifecycle = mock(MangaAgentRunService.class);
    private final NovelContentArtifactService artifacts = mock(NovelContentArtifactService.class);
    private final NovelDraftReviewService service = new NovelDraftReviewService(preparation, runs, lifecycle, artifacts);
    private final UUID conversation = UUID.randomUUID(), request = UUID.randomUUID(), artifact = UUID.randomUUID();

    @Test
    void confirmedReviewCommitsBeforeModelContinuationAndKeepsTheSameRunOpen() {
        var scope = new StoryChatPreparationService.Scope(4L, conversation, 3L, 2L, 6L);
        when(preparation.resolve(3L, 6L, conversation)).thenReturn(scope);
        var run = new MangaAgentRun(); run.setStatus(MangaAgentRunStatus.WAITING_USER);
        when(runs.findForUpdate(4L, request)).thenReturn(Optional.of(run));
        when(artifacts.review(3L, 6L, request, artifact, "confirm", "人工审阅后的最终正文", 8L, "draft-hash"))
                .thenReturn(new NovelContentArtifactService.CommitView(artifact, true, 9L, 10L, 3, "reviewed-hash", "COMMITTED"));
        when(artifacts.reviewReceipt(3L, 6L, request, artifact)).thenReturn(java.util.Map.of(
                "artifact_id", artifact.toString(), "status", "COMMITTED", "content_hash", "reviewed-hash"));
        var saved = service.decideForContinuation(3L, 6L, conversation, request, artifact, "confirm", "人工审阅后的最终正文", 8L, "draft-hash");
        assertThat(saved.chapterVersion()).isEqualTo(9L);
        var order = inOrder(artifacts, lifecycle);
        order.verify(artifacts).review(3L, 6L, request, artifact, "confirm", "人工审阅后的最终正文", 8L, "draft-hash");
        order.verify(lifecycle).recordReviewReceipt(any(), eq(request), argThat(receipt -> {
            return "reviewed-hash".equals(receipt.get("content_hash")) && artifact.toString().equals(receipt.get("artifact_id"));
        }), eq(true));
        verify(lifecycle, never()).markSucceeded(any(MangaAgentConversation.class), any(), anyString());
    }

    @Test
    void explicitReviewRecoversValidDraftFromCompletedOrFailedRunsWithoutResumingModel() {
        when(preparation.resolve(3L, 1L, conversation)).thenReturn(new StoryChatPreparationService.Scope(4L, conversation, 3L, 2L, 1L));
        when(artifacts.review(3L, 1L, request, artifact, "confirm", "审阅正文", 6L, "hash"))
                .thenReturn(new NovelContentArtifactService.CommitView(artifact, true, 7L, 10L, 1, "final-hash", "COMMITTED"));
        when(artifacts.reviewReceipt(3L, 1L, request, artifact)).thenReturn(java.util.Map.of(
                "artifact_id", artifact.toString(), "status", "COMMITTED", "content_hash", "final-hash"));
        for (var status : new MangaAgentRunStatus[]{MangaAgentRunStatus.WAITING_USER, MangaAgentRunStatus.SUCCEEDED, MangaAgentRunStatus.FAILED}) {
            var run = new MangaAgentRun(); run.setStatus(status);
            when(runs.findForUpdate(4L, request)).thenReturn(Optional.of(run));
            service.decide(3L, 1L, conversation, request, artifact, "confirm", "审阅正文", 6L, "hash");
        }
        verify(artifacts, times(3)).review(3L, 1L, request, artifact, "confirm", "审阅正文", 6L, "hash");
        verify(lifecycle, times(1)).markSucceeded(any(MangaAgentConversation.class), eq(request), anyString());
        verify(lifecycle, times(3)).recordReviewReceipt(any(), eq(request), anyMap(), eq(false));
    }

    @Test
    void reconstructingMissingReceiptNeverRepeatsTheAuthorDecision() {
        when(preparation.resolve(3L, 1L, conversation)).thenReturn(new StoryChatPreparationService.Scope(4L, conversation, 3L, 2L, 1L));
        var run = new MangaAgentRun(); run.setStatus(MangaAgentRunStatus.FAILED);
        when(runs.findForUpdate(4L, request)).thenReturn(Optional.of(run));
        var receipt = java.util.Map.<String, Object>of("artifact_id", artifact.toString(), "status", "COMMITTED", "content_hash", "final-hash");
        when(artifacts.reviewReceipt(3L, 1L, request, artifact)).thenReturn(receipt);
        when(lifecycle.recordReviewReceipt(any(), eq(request), eq(receipt), eq(true))).thenReturn(true, false);
        assertThat(service.reconcile(3L, 1L, conversation, request, artifact, true)).isEqualTo(receipt);
        assertThat(service.reconcile(3L, 1L, conversation, request, artifact, true)).isEqualTo(receipt);
        verify(artifacts, never()).review(any(), any(), any(), any(), any(), any(), any(), any());
        verify(lifecycle, times(1)).appendEvent(eq(run), eq("run_event"), any());
    }

    @Test
    void runningCancelledAndInterruptedRunsCannotWrite() {
        when(preparation.resolve(3L, 1L, conversation)).thenReturn(new StoryChatPreparationService.Scope(4L, conversation, 3L, 2L, 1L));
        for (var status : new MangaAgentRunStatus[]{MangaAgentRunStatus.RUNNING, MangaAgentRunStatus.CANCELLED, MangaAgentRunStatus.INTERRUPTED}) {
            var run = new MangaAgentRun(); run.setStatus(status);
            when(runs.findForUpdate(4L, request)).thenReturn(Optional.of(run));
            assertThatThrownBy(() -> service.decide(3L, 1L, conversation, request, artifact, "confirm", "正文", 6L, "hash"))
                    .hasMessageContaining("任务仍在执行或已停止");
        }
        verifyNoInteractions(artifacts);
        verify(lifecycle, times(3)).lockAutomationAuthorDecision(3L, 1L, request);
        verifyNoMoreInteractions(lifecycle);
    }

    @Test
    void foreignConversationRequestCannotReachArtifactWrite() {
        when(preparation.resolve(3L, 1L, conversation)).thenReturn(new StoryChatPreparationService.Scope(4L, conversation, 3L, 2L, 1L));
        when(runs.findForUpdate(4L, request)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.decide(3L, 1L, conversation, request, artifact, "confirm", "正文", 6L, "hash"))
                .hasMessageContaining("修改任务不存在");
        verifyNoInteractions(artifacts);
    }
}
