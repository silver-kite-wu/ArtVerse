package com.artverse.application;

import com.artverse.domain.*;
import com.artverse.guard.AgentConcurrencyGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final StoryChatPreparationService preparation = mock(StoryChatPreparationService.class);
    private final com.artverse.agent.gateway.AgentScopeHarnessAgentGateway gateway = mock(com.artverse.agent.gateway.AgentScopeHarnessAgentGateway.class);
    private final UUID requestId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final MangaAgentConversation conversation = new MangaAgentConversation();
    private final MangaAgentRun run = new MangaAgentRun();
    private final StoryChatAgentService service = new StoryChatAgentService(
            null, runs, publisher, tools, null, null, gateway, null,
            null, null, null, gate, artifacts, null, new ObjectMapper(), null, preparation, mock(NovelDraftReviewService.class));

    @BeforeEach
    void setup() {
        User user = new User(); user.setId(3L);
        Chapter chapter = new Chapter(); chapter.setId(159L);
        Story story = new Story(); story.setId(7L); chapter.setStory(story); conversation.setStory(story);
        conversation.setConversationUuid(UUID.randomUUID());
        conversation.setUser(user); conversation.setChapter(chapter);
        run.setRunType(AgentRunType.STORY_CHAT); run.setRouteKey("READ");
        run.setRunAttributesJson("{\"story_chat_execution\":{\"version\":1,\"profile\":\"local-tools-v1\",\"route\":\"READ\",\"task_type\":\"STORY_CHAT_READ\",\"step_id\":\"story-chat:READ\",\"skills\":[]}}");
        when(publisher.newPersistentSink()).thenReturn(sink);
        when(runs.findRun(conversation, requestId)).thenReturn(Optional.of(run));
        when(runs.snapshot(run)).thenReturn(new MangaAgentRunSnapshots.RunSnapshot(
                requestId, MangaAgentRunStatus.WAITING_USER, "", "", null, null,
                new AgentUserInputRequest("请确认草稿 " + artifactId, List.of(), true, "需要确认"),
                List.of(new MangaAgentRunSnapshots.RunEventSnapshot(1L, "run_event", java.util.Map.of(
                        "type", "external_exec_required", "data", java.util.Map.of("toolCalls", List.of(
                                java.util.Map.of("id", "original-question", "name", "ask_user")))), null)),
                null, null, null, null, null));
        // Stop at the execution boundary: these consent tests must never call a model.
        when(gateway.acquireSessionTurn(any())).thenReturn(mock(com.artverse.agent.gateway.AgentSessionTurns.Lease.class));
        when(runs.acceptAnswer(eq(conversation), eq(requestId), eq("original-question"), eq(1L), anyString(), isNull()))
                .thenAnswer(invocation -> new MangaAgentRunService.AcceptedAnswer(run, "original-question", 1L,
                        invocation.getArgument(4), "hash", UUID.randomUUID(), "{}"));
        when(runs.claimAnswerDelivery(eq(conversation), eq(requestId), any()))
                .thenAnswer(invocation -> {
                    MangaAgentRunService.AcceptedAnswer accepted = invocation.getArgument(2);
                    return new MangaAgentRunService.AnswerContinuation(run, accepted.callId(), accepted.answer(), true,
                            new com.artverse.agent.AgentAnswerDelivery(accepted.actionId(), accepted.callId(), 1L,
                                    accepted.answerHash(), accepted.canonicalResult(), run.getFencingToken()));
                });
        doThrow(new IllegalStateException("fixture execution boundary")).when(sink).recordProgress(run, "PREPARING_CONTEXT");
        when(runs.startOrReuse(eq(conversation), eq(requestId), anyString(), eq(AgentRunType.STORY_CHAT), any(), anyString()))
                .thenThrow(new IllegalStateException("fixture execution boundary"));
    }

    @Test
    void freeTextContinuesWithoutGrantingMutationPermission() {
        service.resumeBackground(conversation, requestId, "answer", artifactId, "换成轻松的语气", "original-question", 1L, null);
        var order = inOrder(runs, gateway);
        order.verify(runs).acceptAnswer(conversation, requestId, "original-question", 1L, "换成轻松的语气", null);
        order.verify(gateway).acquireSessionTurn(any());
        order.verify(runs).claimAnswerDelivery(eq(conversation), eq(requestId), any());
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any(), any(), anyString());
        verifyNoInteractions(tools, artifacts);
        verify(sink).complete();
        verify(gate).release(any());
    }

    @Test
    void blankAnswerDoesNotStartExecutionOrAuthorizeAnything() {
        service.resumeBackground(conversation, requestId, "answer", null, "  ", null);
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any(), any(), anyString());
        verifyNoInteractions(tools, artifacts);
        verify(sink).sendError(isNull(), eq(requestId), eq("请填写 1 至 4000 字的补充说明"));
    }

    @Test
    void legacyDiscardRequiresTheOwnedReviewDecisionEndpoint() {
        service.resumeBackground(conversation, requestId, "discard", null, null, null);
        verifyNoInteractions(tools, artifacts);
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any(), any(), anyString());
        verify(sink).sendError(isNull(), eq(requestId), contains("审阅页"));
    }

    @Test
    void bareConfirmationCannotSaveTheDraftOrResumeAModel() {
        UUID selected = UUID.randomUUID();
        service.resumeBackground(conversation, requestId, "confirm", selected, null, null);
        verifyNoInteractions(tools, artifacts);
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any(), any(), anyString());
        verify(sink).sendError(isNull(), eq(requestId), contains("审阅页"));
    }

    @Test
    void completedReviewedContinuationReturnsItsReceiptWithoutRunningAgain() {
        var done = new MangaAgentRun();
        done.setStatus(MangaAgentRunStatus.SUCCEEDED); done.setFinalReply("已按作者最终稿保存。");
        when(runs.claimReviewedContinuation(conversation, requestId, artifactId)).thenReturn(done);
        service.resumeBackground(conversation, requestId, "reviewed", artifactId, null, null);
        verify(sink).sendDone(done, done.getFinalReply(), requestId);
        verifyNoInteractions(tools, artifacts);
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any(), any(), anyString());
    }
}
