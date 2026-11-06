package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowContextSnapshot;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaReviewMetrics;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MessageRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import java.util.Map;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MangaAgentExecutionSupportTest {

    private MangaAgentConversationService conversationService;
    private AgentScopeHarnessAgentGateway gateway;
    private MangaAgentExecutionSupport support;
    private MangaWorkflowExecutionContext context;
    private AgentRunToolStatus.RunState toolState;
    private AgentRunRequest request;
    private MangaAgentConversation conversation;
    private UUID requestId;
    private MangaReviewMetrics reviewMetrics;

    @BeforeEach
    void setUp() {
        conversationService = mock(MangaAgentConversationService.class);
        gateway = mock(AgentScopeHarnessAgentGateway.class);
        reviewMetrics = mock(MangaReviewMetrics.class);
        support = new MangaAgentExecutionSupport(
                conversationService,
                mock(AgentWorkspaceSyncService.class),
                mock(ApiKeyService.class),
                gateway,
                new ArtVerseProperties(),
                reviewMetrics,
                null,
                null,
                new ObjectMapper()
        );
        context = mock(MangaWorkflowExecutionContext.class);
        toolState = mock(AgentRunToolStatus.RunState.class);
        request = mock(AgentRunRequest.class);
        conversation = mock(MangaAgentConversation.class);
        requestId = UUID.randomUUID();

        when(context.toolState()).thenReturn(toolState);
        when(context.persistConversationMessages()).thenReturn(true);
        when(context.conversation()).thenReturn(conversation);
        when(context.requestId()).thenReturn(requestId);
    }

    @Test
    void executeRequestReturnsCandidateWithoutPersistingReply() {
        when(gateway.generateText(request)).thenReturn(Mono.just("finished"));

        MangaWorkflowResult result = support.executeRequest(context, request, false);

        assertThat(result.reply()).isEqualTo("finished");
        assertThat(result.degraded()).isFalse();
        verifyNoInteractions(conversationService);
    }

    @Test
    void matchingNativeAnswerDeliversTheOriginalResultAndPreservesTheSessionTurn() {
        var delivery = new com.artverse.agent.AgentAnswerDelivery(UUID.randomUUID(), "original-question", 3L, "hash", "{\"answer\":\"bright\"}", 7L);
        var turn = mock(com.artverse.agent.gateway.AgentSessionTurns.Lease.class);
        when(context.answerResume()).thenReturn(new MangaWorkflowExecutionContext.AnswerResume(delivery, turn, AgentTaskType.MANGA_STORYBOARD, "original-plan:1"));
        when(request.taskType()).thenReturn(AgentTaskType.MANGA_STORYBOARD);
        when(request.variables()).thenReturn(Map.of("step_id", "original-plan:1"));
        var msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("original continuation finished").build();
        when(gateway.streamEvents(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(turn)))
                .thenReturn(Flux.just(new AgentResultEvent(msg)));

        assertThat(support.executeRequest(context, request, false).reply()).isEqualTo("original continuation finished");

        verify(gateway).streamEvents(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.argThat(results -> results.size() == 1
                && results.getFirst().getId().equals("original-question")
                && ((io.agentscope.core.message.TextBlock) results.getFirst().getOutput().getFirst()).getText().equals(delivery.canonicalResult())), org.mockito.ArgumentMatchers.eq(turn));
        verify(gateway, org.mockito.Mockito.never()).generateText(request);
        verifyNoInteractions(conversationService);
    }

    @Test
    void prepareAgentMessagesInjectsServerOwnedChapterSnapshotBeforeCurrentUserMessage() {
        MangaWorkflowContextSnapshot snapshot = new MangaWorkflowContextSnapshot(
                3L,
                7L,
                "Story",
                "Chapter 1",
                "ink",
                6,
                2,
                "source exists",
                "storyboard exists",
                "characters exist",
                "",
                MangaWorkflowRoute.REVIEW,
                "hash-123",
                List.of("storyboard_excerpt"),
                List.of()
        );
        when(context.workflowContext()).thenReturn(snapshot);
        String userMessage = "本章的漫画情况和小说质量怎么样，给我一份详细的分析报告";
        when(context.message()).thenReturn(userMessage);
        when(conversationService.listMessages(conversation)).thenReturn(List.of());
        when(conversationService.buildMessages(
                context.chapter(),
                context.user(),
                List.of(),
                userMessage,
                requestId
        )).thenReturn(List.of(
                new AgentMessage("system", "system prompt"),
                new AgentMessage("user", userMessage)
        ));

        List<AgentMessage> messages = support.prepareAgentMessages(context);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEmpty();
        assertThat(messages.get(1).dataBlock()).isNotNull();
        assertThat(messages.get(1).dataBlock().name()).isEqualTo("chapter_snapshot");
        assertThat(messages.get(1).dataBlock().payload())
                .containsEntry("context_hash", "hash-123")
                .containsKey("required_fields")
                .containsKey("data");
        assertThat(messages.get(2).role()).isEqualTo("user");
    }

    @Test
    void executeRequestPreservesBusinessExceptionForTheClaimedFinalizer() {
        BusinessException failure = new BusinessException(409, "conflict");
        when(gateway.generateText(request)).thenReturn(Mono.error(failure));

        assertThatThrownBy(() -> support.executeRequest(context, request, false))
                .isSameAs(failure);
        verify(conversationService, org.mockito.Mockito.never()).saveFailureMessage(conversation, "conflict", requestId);
    }

    @Test
    void executeRequestLeavesEmptyResponsePersistenceToTheClaimedFinalizer() {
        when(gateway.generateText(request)).thenReturn(Mono.just("   "));

        assertThatThrownBy(() -> support.executeRequest(context, request, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent returned empty response");
        verify(conversationService, org.mockito.Mockito.never()).saveFailureMessage(
                conversation, "Agent returned empty response", requestId);
    }

    @Test
    void executeRequestDegradesAfterSuccessfulMutatingTool() {
        when(gateway.generateText(request)).thenReturn(Mono.error(new IllegalStateException("model failed")));
        when(toolState.hasSuccessfulMutatingTool()).thenReturn(true);
        when(conversationService.fallbackAfterToolSuccess(
                conversation, requestId, toolState, "model failed", false))
                .thenReturn(Map.of(
                        "reply", "Storyboard was saved",
                        "agent_final_response_degraded", true
                ));

        MangaWorkflowResult result = support.executeRequest(context, request, true);

        assertThat(result.reply()).isEqualTo("Storyboard was saved");
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void reviewRequestRequiresAllDeclaredSubagentsToComplete() {
        when(request.taskType()).thenReturn(AgentTaskType.MANGA_REVIEW);
        when(gateway.streamEvents(request)).thenReturn(Flux.fromIterable(reviewEvents(true)));

        MangaWorkflowResult result = support.executeRequest(context, request, false);

        assertThat(result.reply()).isEqualTo("authoritative final review");
        assertThat(result.degraded()).isFalse();
        assertThat(result.attributes()).containsEntry("review_subagents_started", 4)
                .containsEntry("review_subagents_completed", 4)
                .containsEntry("review_subagents_max_concurrency", 1);
        verify(reviewMetrics).recordStarted(4);
        verify(reviewMetrics).recordCompleted(4);
    }

    @Test
    void reviewRequestDegradesWhenADeclaredSubagentIsMissing() {
        when(request.taskType()).thenReturn(AgentTaskType.MANGA_REVIEW);
        when(gateway.streamEvents(request)).thenReturn(Flux.fromIterable(reviewEvents(false)));

        MangaWorkflowResult result = support.executeRequest(context, request, false);

        assertThat(result.degraded()).isTrue();
        assertThat(result.attributes()).containsEntry("review_subagents_started", 3)
                .containsEntry("review_subagents_completed", 3);
        assertThat(result.attributes().get("review_subagents_missing"))
                .isEqualTo(java.util.List.of("continuity-reviewer"));
        verify(reviewMetrics).recordMissing(1);
    }

    private java.util.List<AgentEvent> reviewEvents(boolean includeContinuity) {
        java.util.List<String> reviewers = new java.util.ArrayList<>(java.util.List.of(
                "camera-reviewer", "character-reviewer", "pacing-reviewer"));
        if (includeContinuity) reviewers.add("continuity-reviewer");
        java.util.List<AgentEvent> events = new java.util.ArrayList<>();
        for (String reviewer : reviewers) {
            events.add(new AgentStartEvent("event", "main/" + reviewer, "session", "reply", reviewer, "assistant"));
            events.add(new AgentEndEvent("reply").withSource("main/" + reviewer));
        }
        events.add(new TextBlockDeltaEvent("event", "manga-review", "reply", "block", "review complete"));
        events.add(new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT)
                .textContent("authoritative final review").build()).withSource("manga-review"));
        return events;
    }
}
