package com.artverse.application;

import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AutomationExecutionClaim;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.agent.gateway.AgentSessionTurns;
import com.artverse.application.workflow.MangaRoutingMetrics;
import com.artverse.application.workflow.MangaWorkflowOrchestrator;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.guard.AgentConcurrencyGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MangaNativeAnswerResumeTest {
    final MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
    final MangaAgentRunService runs = mock(MangaAgentRunService.class);
    final MangaAgentRunEventPublisher publisher = mock(MangaAgentRunEventPublisher.class);
    final MangaWorkflowOrchestrator workflow = mock(MangaWorkflowOrchestrator.class);
    final AgentRunToolStatus tools = mock(AgentRunToolStatus.class);
    final AgentScopeHarnessAgentGateway gateway = mock(AgentScopeHarnessAgentGateway.class);
    final AgentSessionTurns.Lease turn = mock(AgentSessionTurns.Lease.class);
    final AgentConcurrencyGate gate = mock(AgentConcurrencyGate.class);
    final ExecutorService executor = mock(ExecutorService.class);
    final MangaAgentRunEventPublisher.RunEventSink sink = mock(MangaAgentRunEventPublisher.RunEventSink.class);
    final MangaAgentConversation conversation = new MangaAgentConversation();
    final UUID requestId = UUID.randomUUID();
    final User user = new User();
    final MangaAgentRun run = new MangaAgentRun();
    final UserProviderConfig config = new UserProviderConfig("llm", "test", "", "key", "https://example.test", "model");
    final MangaAgentRunService.AcceptedAnswer accepted = new MangaAgentRunService.AcceptedAnswer(
            run, "original-question", 3L, "明亮", "answer-hash", UUID.randomUUID(), "{\"answer\":\"明亮\"}");
    final MangaAgentRunService.AnswerExecution execution = new MangaAgentRunService.AnswerExecution(AgentTaskType.MANGA_STORYBOARD, "original-plan:1");
    final MangaAgentService service = new MangaAgentService(conversations, runs, publisher, workflow, tools,
            mock(ChapterAccessService.class), new ArtVerseProperties(), gate, gateway,
            mock(StoryboardArtifactService.class), executor, mock(MangaRoutingMetrics.class));

    @BeforeEach void setup() {
        user.setId(1L);
        Story story = new Story(); story.setId(2L);
        Chapter chapter = new Chapter(); chapter.setId(3L); chapter.setStory(story);
        conversation.setUser(user); conversation.setStory(story); conversation.setChapter(chapter);
        conversation.setConversationUuid(UUID.randomUUID());
        when(conversations.activeOrCreate(3L, user)).thenReturn(conversation);
        when(publisher.newSink(any())).thenReturn(sink);
        when(publisher.newPersistentSink()).thenReturn(sink);
        when(runs.acceptAnswer(conversation, requestId, "original-question", 3L, null, "bright")).thenReturn(accepted);
        when(runs.answerExecution(conversation, requestId, accepted)).thenReturn(execution);
        when(gateway.acquireSessionTurn(any())).thenReturn(turn);
        var scope = mock(AgentRunToolStatus.RunScope.class);
        when(scope.state()).thenReturn(mock(AgentRunToolStatus.RunState.class));
        when(tools.start(eq(1L), eq(3L), eq(requestId), any())).thenReturn(scope);
        when(workflow.resumeAnswerWithToolState(eq(conversation), eq(requestId), eq(accepted), eq(execution),
                eq(turn), any(), eq(config), nullable(MangaAgentRunEventPublisher.RunEventSink.class), any(), nullable(AutomationExecutionClaim.class)))
                .thenReturn(Map.of("reply", "原节点已继续"));
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; }).when(executor).submit(any(Runnable.class));
    }

    @Test void syncAcceptsThenAcquiresOriginalSessionAndDeliversWithoutNewRun() {
        var result = service.resume(3L, requestId, null, "original-question", 3L, "bright", user, config);
        assertThat(result.reply()).isEqualTo("原节点已继续");
        var order = inOrder(runs, gateway, workflow);
        order.verify(runs).acceptAnswer(conversation, requestId, "original-question", 3L, null, "bright");
        order.verify(runs).answerExecution(conversation, requestId, accepted);
        order.verify(gateway).acquireSessionTurn(argThat(request -> request.taskType() == AgentTaskType.MANGA_STORYBOARD
                && "original-plan:1".equals(request.variables().get("step_id"))
                && request.conversationId().equals(conversation.getConversationUuid())));
        order.verify(workflow).resumeAnswerWithToolState(eq(conversation), eq(requestId), eq(accepted), eq(execution),
                eq(turn), any(), eq(config), isNull(), any(), isNull());
        verify(workflow, never()).runWithToolState(any(), any(), any(), any(), any(), any(), any());
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any());
        verify(turn).close();
        verify(gate).release(any());
    }

    @Test void streamUsesTheSameAcceptedOriginalCall() {
        assertThat(service.resumeAgUiStream(3L, requestId, null, "original-question", 3L, "bright", user, config)).isNotNull();
        verify(workflow).resumeAnswerWithToolState(eq(conversation), eq(requestId), eq(accepted), eq(execution),
                eq(turn), any(), eq(config), eq(sink), any(), isNull());
        verify(sink).complete();
        verify(gate).release(any());
    }

    @Test void backgroundCarriesTheCapturedParentClaimThroughTheNativeResume() {
        var parent = new AutomationExecutionClaim(UUID.randomUUID(), "parent-original", 19L);
        service.resumeBackground(conversation, requestId, null, "original-question", 3L, "bright", config, parent);
        verify(workflow).resumeAnswerWithToolState(eq(conversation), eq(requestId), eq(accepted), eq(execution),
                eq(turn), any(), eq(config), eq(sink), any(), same(parent));
    }

    @Test void aMissingOriginalCallCannotBecomeANewPrompt() {
        var snapshot = mock(MangaAgentRunSnapshots.RunSnapshot.class);
        when(snapshot.status()).thenReturn(MangaAgentRunStatus.WAITING_USER);
        when(snapshot.userInputRequest()).thenReturn(new AgentUserInputRequest("which?", java.util.List.of(), true, "", "USER_QUESTION"));
        when(runs.findRun(conversation, requestId)).thenReturn(java.util.Optional.of(run));
        when(runs.snapshot(run)).thenReturn(snapshot);
        assertThatThrownBy(() -> service.resume(3L, requestId, "new instruction", user, config)).hasMessageContaining("toolCallId");
        verifyNoInteractions(workflow, gateway);
    }
}
