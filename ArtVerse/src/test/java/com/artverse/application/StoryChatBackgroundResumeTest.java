package com.artverse.application;

import com.artverse.domain.*;
import com.artverse.guard.AgentConcurrencyGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryChatBackgroundResumeTest {
    private final MangaAgentRunService runs = mock(MangaAgentRunService.class);
    private final MangaAgentRunEventPublisher publisher = mock(MangaAgentRunEventPublisher.class);
    private final MangaAgentRunEventPublisher.RunEventSink sink = mock(MangaAgentRunEventPublisher.RunEventSink.class);
    private final AgentRunToolStatus tools = mock(AgentRunToolStatus.class);
    private final NovelContentArtifactService artifacts = mock(NovelContentArtifactService.class);
    private final AgentConcurrencyGate gate = mock(AgentConcurrencyGate.class);
    private final UUID requestId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final MangaAgentConversation conversation = new MangaAgentConversation();
    private final StoryChatAgentService service = new StoryChatAgentService(
            null, runs, publisher, tools, null, null, null, null,
            null, null, null, gate, artifacts, null, null, null, null);

    @BeforeEach
    void setup() {
        User user = new User(); user.setId(3L);
        Chapter chapter = new Chapter(); chapter.setId(159L);
        conversation.setUser(user); conversation.setChapter(chapter);
        MangaAgentRun run = new MangaAgentRun();
        when(publisher.newPersistentSink()).thenReturn(sink);
        when(runs.findRun(conversation, requestId)).thenReturn(Optional.of(run));
        when(runs.snapshot(run)).thenReturn(new MangaAgentRunSnapshots.RunSnapshot(
                requestId, MangaAgentRunStatus.WAITING_USER, "", "", null, null,
                new AgentUserInputRequest("请确认草稿 " + artifactId, List.of(), true, "需要确认"),
                List.of(), null, null, null, null, null));
        // Stop at the execution boundary: these consent tests must never call a model.
        when(runs.startOrReuse(eq(conversation), eq(requestId), anyString(),
                eq(AgentRunType.STORY_CHAT), any(), anyString()))
                .thenThrow(new IllegalStateException("fixture execution boundary"));
    }

    @Test
    void freeTextContinuesWithoutGrantingMutationPermission() {
        service.resumeBackground(conversation, requestId, "answer", artifactId, "换成轻松的语气", null);
        verify(runs).startOrReuse(eq(conversation), eq(requestId),
                argThat(message -> message.contains("换成轻松的语气") && message.contains("不代表确认写入")),
                eq(AgentRunType.STORY_CHAT), any(), eq("WRITE"));
        verifyNoInteractions(tools, artifacts);
        verify(sink).complete();
        verify(gate).release(any());
    }

    @Test
    void blankAnswerDoesNotStartExecutionOrAuthorizeAnything() {
        service.resumeBackground(conversation, requestId, "answer", null, "  ", null);
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any(), any(), anyString());
        verifyNoInteractions(tools, artifacts);
        verify(sink).sendError(isNull(), eq(requestId), eq("请填写补充说明"));
    }

    @Test
    void discardFindsArtifactInQuestionWithoutAuthorizingWrite() {
        service.resumeBackground(conversation, requestId, "discard", null, null, null);
        verify(artifacts).reject(3L, 159L, requestId, artifactId);
        verify(runs).markSucceeded(conversation, requestId, "已放弃本次草稿。");
        verifyNoInteractions(tools);
    }

    @Test
    void explicitConfirmationAuthorizesOnlySelectedArtifact() {
        UUID selected = UUID.randomUUID();
        service.resumeBackground(conversation, requestId, "confirm", selected, null, null);
        verify(tools).authorizeMutationArtifact(3L, 159L, requestId, selected);
        verify(tools, times(1)).authorizeMutationArtifact(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(artifacts);
    }
}
