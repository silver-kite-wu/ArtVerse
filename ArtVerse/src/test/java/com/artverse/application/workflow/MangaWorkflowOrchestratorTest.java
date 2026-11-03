package com.artverse.application.workflow;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.MangaAgentRunSnapshots;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.UserProviderConfig;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaWorkflowOrchestratorTest {

    @Test
    void cachedStreamReplyDoesNotCreateSyntheticRun() {
        TestFixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentMessage cachedReply = new MangaAgentMessage();
        cachedReply.setContent("cached");
        when(fixture.conversations.findAssistantReply(fixture.conversation, requestId))
                .thenReturn(Optional.of(cachedReply));
        when(fixture.runs.reconcileCachedReply(fixture.conversation, requestId, "cached"))
                .thenReturn(Optional.empty());

        fixture.orchestrator.runStreamLeader(
                fixture.conversation, "rewrite", requestId, null, mock(AgentRunToolStatus.RunState.class),
                fixture.sink, new AtomicReference<>(), fixture.config);

        verify(fixture.runs, never()).startOrReuse(
                any(MangaAgentConversation.class), any(UUID.class), any(String.class), any(MangaWorkflowRoute.class));
        verify(fixture.sink).sendDone(null, "cached", requestId);
        verify(fixture.sink, never()).sendStatus(any(), any(), any());
    }

    @Test
    void cancelledRunWinsOverReplyPersistedJustBeforeCancellation() {
        TestFixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentMessage cachedReply = new MangaAgentMessage();
        cachedReply.setContent("late reply");
        MangaAgentRun cancelled = new MangaAgentRun();
        cancelled.setStatus(MangaAgentRunStatus.CANCELLED);
        when(fixture.conversations.findAssistantReply(fixture.conversation, requestId))
                .thenReturn(Optional.of(cachedReply));
        when(fixture.runs.reconcileCachedReply(fixture.conversation, requestId, "late reply"))
                .thenReturn(Optional.of(cancelled));

        fixture.orchestrator.runStreamLeader(
                fixture.conversation, "rewrite", requestId, null, mock(AgentRunToolStatus.RunState.class),
                fixture.sink, new AtomicReference<>(), fixture.config);

        verify(fixture.sink).complete();
        verify(fixture.sink, never()).sendDone(any(), any(), any());
        verify(fixture.runs, never()).startOrReuse(
                any(MangaAgentConversation.class), any(UUID.class), any(String.class), any(MangaWorkflowRoute.class));
    }

    @Test
    void createsRunBeforeRouterCanRequestClarification() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        AgentModelSpecFactory modelSpecs = mock(AgentModelSpecFactory.class);
        ApiKeyService keys = mock(ApiKeyService.class);
        GenerationGuardService guard = mock(GenerationGuardService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        MangaWorkflowContextAssembler assembler = mock(MangaWorkflowContextAssembler.class);
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaWorkflowRouter router = mock(MangaWorkflowRouter.class);
        MangaWorkflowOrchestrator orchestrator = new MangaWorkflowOrchestrator(
                conversations, modelSpecs, keys, guard, runs, assembler, mock(MangaWorkflowContextPolicy.class), registry, router,
                mock(MangaRoutingMetrics.class), resultFinalizer());

        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setUser(user);
        conversation.setStory(story);
        conversation.setChapter(chapter);
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = new MangaAgentRun();
        run.setId(99L);
        run.setConversation(conversation);
        AgentModelSpec modelSpec = new AgentModelSpec("deepseek", "https://api.deepseek.com", "model", "hash");
        UserProviderConfig config = new UserProviderConfig(
                "llm", "deepseek", "DeepSeek", "key", "https://api.deepseek.com", "model");
        when(conversations.findAssistantReply(conversation, requestId)).thenReturn(java.util.Optional.empty());
        when(modelSpecs.fromProviderConfig(config)).thenReturn(modelSpec);
        when(runs.startOrReuse(conversation, requestId, "rewrite", MangaWorkflowRoute.DIRECTOR)).thenReturn(run);
        when(runs.markRouting(run)).thenReturn(run);
        AgentUserInputRequiredException clarification = new AgentUserInputRequiredException(
                new AgentUserInputRequest("confirm", List.of(), true, "routing", "ROUTING"));
        when(router.route(conversation, "rewrite", requestId, modelSpec, "key")).thenThrow(clarification);

        assertThatThrownBy(() -> orchestrator.runWithToolState(
                conversation, "rewrite", requestId, null, mock(AgentRunToolStatus.RunState.class), config))
                .isSameAs(clarification);

        var order = inOrder(runs, router);
        order.verify(runs).startOrReuse(conversation, requestId, "rewrite", MangaWorkflowRoute.DIRECTOR);
        order.verify(runs).markRouting(run);
        order.verify(router).route(conversation, "rewrite", requestId, modelSpec, "key");
    }

    @Test
    void missingWriteContextTurnsIntoHitlBeforeNodeDispatch() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        AgentModelSpecFactory modelSpecs = mock(AgentModelSpecFactory.class);
        ApiKeyService keys = mock(ApiKeyService.class);
        GenerationGuardService guard = mock(GenerationGuardService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        MangaWorkflowContextAssembler assembler = mock(MangaWorkflowContextAssembler.class);
        MangaWorkflowContextPolicy contextPolicy = mock(MangaWorkflowContextPolicy.class);
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaWorkflowRouter router = mock(MangaWorkflowRouter.class);
        MangaWorkflowOrchestrator orchestrator = new MangaWorkflowOrchestrator(
                conversations, modelSpecs, keys, guard, runs, assembler, contextPolicy, registry, router,
                mock(MangaRoutingMetrics.class), resultFinalizer());

        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(9L);
        conversation.setUser(user);
        conversation.setStory(story);
        conversation.setChapter(chapter);
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = new MangaAgentRun();
        run.setId(99L);
        run.setConversation(conversation);
        AgentModelSpec modelSpec = new AgentModelSpec("deepseek", "https://api.deepseek.com", "model", "hash");
        UserProviderConfig config = new UserProviderConfig(
                "llm", "deepseek", "DeepSeek", "key", "https://api.deepseek.com", "model");
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.STORYBOARD, 0.9, List.of("storyboard"), true, false, "storyboard",
                List.of(MangaWorkflowRoute.STORYBOARD), RoutingDecision.CURRENT_VERSION,
                java.util.Set.of(MangaWorkflowCapability.STORYBOARD_WRITE));
        MangaWorkflowContextSnapshot snapshot = new MangaWorkflowContextSnapshot(
                3L, 7L, "Story", "Chapter 1", "ink", 0, 0, "", "",
                "", "", MangaWorkflowRoute.STORYBOARD, "ctx", List.of("chapter_source_excerpt"),
                List.of("required_context_missing:chapter_source_excerpt"));
        AgentUserInputRequiredException expected = new AgentUserInputRequiredException(
                new AgentUserInputRequest("question", List.of(), false, "missing", "CONTEXT_MISSING"));

        when(conversations.findAssistantReply(conversation, requestId)).thenReturn(Optional.empty());
        when(modelSpecs.fromProviderConfig(config)).thenReturn(modelSpec);
        when(runs.startOrReuse(conversation, requestId, "rewrite", MangaWorkflowRoute.DIRECTOR)).thenReturn(run);
        when(runs.recordModelConfig(run, config.configId())).thenReturn(run);
        when(runs.markRouting(run)).thenReturn(run);
        when(router.route(conversation, "rewrite", requestId, modelSpec, "key")).thenReturn(decision);
        when(runs.updateRoutingDecision(run, decision, MangaRouteSource.AUTO)).thenReturn(run);
        when(guard.executeMangaAgentRun(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.concurrent.Callable<Map<String, Object>> callback =
                    (java.util.concurrent.Callable<Map<String, Object>>) invocation.getArgument(7);
            return callback.call();
        });
        when(assembler.assemble(conversation, "rewrite", decision)).thenReturn(snapshot);
        when(contextPolicy.missingRequiredFields(snapshot)).thenReturn(List.of("chapter_source_excerpt"));
        when(contextPolicy.blocksWrite(decision)).thenReturn(true);
        when(contextPolicy.missingContextHitl(snapshot, decision)).thenReturn(expected);

        assertThatThrownBy(() -> orchestrator.runWithToolState(
                conversation, "rewrite", requestId, null, mock(AgentRunToolStatus.RunState.class), config))
                .isSameAs(expected);

        verify(registry, never()).handlerFor(any());
        verify(runs).recordContextSnapshot(any(MangaAgentRun.class), any(MangaAgentRunSnapshots.RunContextSnapshot.class));
    }

    @Test
    void missingReadOnlyContextReturnsStaticExplanationWithoutNodeDispatch() throws Exception {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        AgentModelSpecFactory modelSpecs = mock(AgentModelSpecFactory.class);
        ApiKeyService keys = mock(ApiKeyService.class);
        GenerationGuardService guard = mock(GenerationGuardService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        MangaWorkflowContextAssembler assembler = mock(MangaWorkflowContextAssembler.class);
        MangaWorkflowContextPolicy contextPolicy = mock(MangaWorkflowContextPolicy.class);
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaWorkflowRouter router = mock(MangaWorkflowRouter.class);
        MangaWorkflowOrchestrator orchestrator = new MangaWorkflowOrchestrator(
                conversations, modelSpecs, keys, guard, runs, assembler, contextPolicy, registry, router,
                mock(MangaRoutingMetrics.class), resultFinalizer());

        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(9L);
        conversation.setUser(user);
        conversation.setStory(story);
        conversation.setChapter(chapter);
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = new MangaAgentRun();
        run.setId(99L);
        run.setConversation(conversation);
        AgentModelSpec modelSpec = new AgentModelSpec("deepseek", "https://api.deepseek.com", "model", "hash");
        UserProviderConfig config = new UserProviderConfig(
                "llm", "deepseek", "DeepSeek", "key", "https://api.deepseek.com", "model");
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.REVIEW, 0.9, List.of("review"), false, false, "review",
                List.of(MangaWorkflowRoute.REVIEW), RoutingDecision.CURRENT_VERSION,
                java.util.Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW));
        MangaWorkflowContextSnapshot snapshot = new MangaWorkflowContextSnapshot(
                3L, 7L, "Story", "Chapter 1", "ink", 0, 0, "source", "",
                "", "", MangaWorkflowRoute.REVIEW, "ctx", List.of("storyboard_excerpt"),
                List.of("required_context_missing:storyboard_excerpt"));

        when(conversations.findAssistantReply(conversation, requestId)).thenReturn(Optional.empty());
        when(modelSpecs.fromProviderConfig(config)).thenReturn(modelSpec);
        when(runs.startOrReuse(conversation, requestId, "review", MangaWorkflowRoute.DIRECTOR)).thenReturn(run);
        when(runs.recordModelConfig(run, config.configId())).thenReturn(run);
        when(runs.markRouting(run)).thenReturn(run);
        when(router.route(conversation, "review", requestId, modelSpec, "key")).thenReturn(decision);
        when(runs.updateRoutingDecision(run, decision, MangaRouteSource.AUTO)).thenReturn(run);
        when(guard.executeMangaAgentRun(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.concurrent.Callable<Map<String, Object>> callback =
                    (java.util.concurrent.Callable<Map<String, Object>>) invocation.getArgument(7);
            return callback.call();
        });
        when(assembler.assemble(conversation, "review", decision)).thenReturn(snapshot);
        when(contextPolicy.missingRequiredFields(snapshot)).thenReturn(List.of("storyboard_excerpt"));
        when(contextPolicy.blocksWrite(decision)).thenReturn(false);
        when(contextPolicy.readOnlyExplanation(snapshot, decision)).thenReturn("缺少分镜，先不评审。");

        Map<String, Object> result = orchestrator.runWithToolState(
                conversation, "review", requestId, null, mock(AgentRunToolStatus.RunState.class), config);

        assertThat(result).containsEntry("reply", "缺少分镜，先不评审。");
        verify(conversations).saveMessage(conversation, com.artverse.domain.MessageRole.USER, "review", requestId);
        verify(registry, never()).handlerFor(any());
    }

    private TestFixture fixture() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        MangaWorkflowOrchestrator orchestrator = new MangaWorkflowOrchestrator(
                conversations,
                mock(AgentModelSpecFactory.class),
                mock(ApiKeyService.class),
                mock(GenerationGuardService.class),
                runs,
                mock(MangaWorkflowContextAssembler.class),
                mock(MangaWorkflowContextPolicy.class),
                mock(MangaWorkflowNodeRegistry.class),
                mock(MangaWorkflowRouter.class),
                mock(MangaRoutingMetrics.class),
                resultFinalizer());
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(9L);
        conversation.setUser(user);
        conversation.setStory(story);
        conversation.setChapter(chapter);
        UserProviderConfig config = new UserProviderConfig(
                "llm", "deepseek", "DeepSeek", "key", "https://api.deepseek.com", "model");
        return new TestFixture(orchestrator, conversations, runs, conversation,
                mock(MangaAgentRunEventPublisher.RunEventSink.class), config);
    }

    private record TestFixture(MangaWorkflowOrchestrator orchestrator,
                               MangaAgentConversationService conversations,
                               MangaAgentRunService runs,
                               MangaAgentConversation conversation,
                               MangaAgentRunEventPublisher.RunEventSink sink,
                               UserProviderConfig config) {
    }

    private ResultFinalizer resultFinalizer() {
        ResultFinalizer finalizer = mock(ResultFinalizer.class);
        when(finalizer.finalizeResult(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        return finalizer;
    }
}
