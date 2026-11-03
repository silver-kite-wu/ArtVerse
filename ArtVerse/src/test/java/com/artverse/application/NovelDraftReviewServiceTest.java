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
    void explicitReviewRecoversValidDraftFromCompletedOrFailedRunsWithoutResumingModel() {
        when(preparation.resolve(3L, 1L, conversation)).thenReturn(new StoryChatPreparationService.Scope(4L, conversation, 3L, 2L, 1L));
        for (var status : new MangaAgentRunStatus[]{MangaAgentRunStatus.WAITING_USER, MangaAgentRunStatus.SUCCEEDED, MangaAgentRunStatus.FAILED}) {
            var run = new MangaAgentRun(); run.setStatus(status);
            when(runs.findForUpdate(4L, request)).thenReturn(Optional.of(run));
            service.decide(3L, 1L, conversation, request, artifact, "confirm", "审阅正文", 6L, "hash");
        }
        verify(artifacts, times(3)).review(3L, 1L, request, artifact, "confirm", "审阅正文", 6L, "hash");
        verify(lifecycle, times(1)).markSucceeded(any(MangaAgentConversation.class), eq(request), anyString());
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
        verifyNoInteractions(artifacts, lifecycle);
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
