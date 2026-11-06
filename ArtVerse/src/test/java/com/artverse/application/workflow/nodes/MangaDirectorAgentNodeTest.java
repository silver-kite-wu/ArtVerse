package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.ExecutionPlanValidator;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowNodeRegistry;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.RoutingDecision;
import com.artverse.application.workflow.ToolContractViolationException;
import com.artverse.common.BusinessException;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class MangaDirectorAgentNodeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void composesStepsThroughTypedResultContract() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaWorkflowNodeHandler storyboard = mock(MangaWorkflowNodeHandler.class);
        MangaWorkflowNodeHandler review = mock(MangaWorkflowNodeHandler.class);
        MangaAgentExecutionSupport support = mock(MangaAgentExecutionSupport.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(registry, runService, objectMapper, support,
                new ExecutionPlanValidator());

        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("multi"),
                true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW),
                RoutingDecision.CURRENT_VERSION
        );
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);
        run.setOwnerInstanceId("original-worker");
        run.setFencingToken(11L);

        when(runService.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(storyboard);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(review);
        when(storyboard.run(any())).thenReturn(MangaWorkflowResult.degraded(
                "Storyboard user reply", "Storyboard contract summary", "Storyboard handoff context"));
        when(review.run(any())).thenReturn(MangaWorkflowResult.success(
                "Review user reply", "Review contract summary", "Review handoff context"));

        var originalContext = TestContexts.context(run.getRequestId(), run);
        run.setFencingToken(12L);
        MangaWorkflowResult result = node.run(originalContext);

        assertThat(result.reply())
                .contains("Storyboard contract summary")
                .contains("Review contract summary");
        assertThat(result.degraded()).isTrue();
        ArgumentCaptor<MangaWorkflowExecutionContext> contextCaptor = forClass(MangaWorkflowExecutionContext.class);
        verify(review).run(contextCaptor.capture());
        assertThat(contextCaptor.getValue().message()).contains("Storyboard handoff context");
        assertThat(contextCaptor.getValue().executionClaim()).isSameAs(originalContext.executionClaim());
        assertThat(contextCaptor.getValue().executionClaim().fencingToken()).isEqualTo(11L);
        ArgumentCaptor<MangaWorkflowExecutionContext> firstStep = forClass(MangaWorkflowExecutionContext.class);
        verify(storyboard).run(firstStep.capture());
        assertThat(firstStep.getValue().executionClaim()).isSameAs(originalContext.executionClaim());
        verify(support, never()).saveReply(any(), org.mockito.ArgumentMatchers.contains("Review contract summary"));
    }

    @Test
    void streamingDirectorUsesChildStreamsWithStepContext() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaWorkflowNodeHandler storyboard = mock(MangaWorkflowNodeHandler.class);
        MangaWorkflowNodeHandler review = mock(MangaWorkflowNodeHandler.class);
        MangaAgentExecutionSupport support = mock(MangaAgentExecutionSupport.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(registry, runService, objectMapper, support,
                new ExecutionPlanValidator());
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(requestId);
        run.setStatus(MangaAgentRunStatus.RUNNING);
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.95, List.of("write", "review"), true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW),
                RoutingDecision.CURRENT_VERSION,
                java.util.Set.of(com.artverse.application.workflow.MangaWorkflowCapability.STORYBOARD_WRITE,
                        com.artverse.application.workflow.MangaWorkflowCapability.STORYBOARD_REVIEW));
        when(runService.findRun(1L, 7L, requestId)).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(storyboard);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(review);
        when(storyboard.stream(any(), any())).thenReturn(MangaWorkflowResult.success("storyboard"));
        when(review.stream(any(), any())).thenReturn(MangaWorkflowResult.success("review"));
        MangaAgentRunEventPublisher.RunEventSink sink = mock(MangaAgentRunEventPublisher.RunEventSink.class);

        run.setOwnerInstanceId("stream-original-worker");
        run.setFencingToken(21L);
        var originalContext = TestContexts.context(requestId, run);
        run.setOwnerInstanceId("stream-replacement-worker");
        run.setFencingToken(22L);
        node.stream(originalContext, new MangaWorkflowStreamContext(run, sink));

        ArgumentCaptor<MangaWorkflowStreamContext> streamCaptor = forClass(MangaWorkflowStreamContext.class);
        ArgumentCaptor<MangaWorkflowExecutionContext> childContext = forClass(MangaWorkflowExecutionContext.class);
        verify(storyboard).stream(childContext.capture(), streamCaptor.capture());
        assertThat(childContext.getValue().executionClaim()).isSameAs(originalContext.executionClaim());
        assertThat(childContext.getValue().executionClaim().owner()).isEqualTo("stream-original-worker");
        assertThat(childContext.getValue().executionClaim().fencingToken()).isEqualTo(21L);
        assertThat(streamCaptor.getValue().suppressTextDeltas()).isTrue();
        assertThat(streamCaptor.getValue().eventContext())
                .containsEntry("step", 0)
                .containsEntry("route", "STORYBOARD")
                .containsEntry("agentName", "storyboard-agent");
        verify(storyboard, never()).run(any());
        verify(review, never()).run(any());
    }

    @Test
    void nativeAnswerRestoresTheOriginalPlanAndSkipsCompletedNodes() {
        var registry = mock(MangaWorkflowNodeRegistry.class);
        var runs = mock(MangaAgentRunService.class);
        var storyboard = mock(MangaWorkflowNodeHandler.class);
        var review = mock(MangaWorkflowNodeHandler.class);
        var support = mock(MangaAgentExecutionSupport.class);
        var node = new MangaDirectorAgentNode(registry, runs, objectMapper, support, new ExecutionPlanValidator());
        var run = new MangaAgentRun(); run.setRequestId(UUID.randomUUID());
        run.setOwnerInstanceId("original-worker"); run.setFencingToken(11L);
        var decision = new RoutingDecision(MangaWorkflowRoute.DIRECTOR, .9, List.of("resume"), true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW), RoutingDecision.CURRENT_VERSION);
        var first = new com.artverse.application.workflow.ExecutionStep(0, MangaWorkflowRoute.STORYBOARD, "storyboard", true);
        first.markCompleted("saved storyboard", "original storyboard handoff");
        var second = new com.artverse.application.workflow.ExecutionStep(1, MangaWorkflowRoute.REVIEW, "review", false);
        second.markRunning("original storyboard handoff");
        var plan = new com.artverse.application.workflow.ExecutionPlan("original-plan", List.of(first, second),
                RoutingDecision.CURRENT_VERSION, java.time.OffsetDateTime.now());
        when(runs.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runs.routingDecision(run)).thenReturn(decision); when(runs.executionPlan(run)).thenReturn(plan);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(storyboard);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(review);
        when(review.run(any())).thenReturn(MangaWorkflowResult.success("review finished"));
        var resume = new MangaWorkflowExecutionContext.AnswerResume(new com.artverse.agent.AgentAnswerDelivery(
                UUID.randomUUID(), "original-question", 3L, "hash", "{}", 11L),
                mock(com.artverse.agent.gateway.AgentSessionTurns.Lease.class), com.artverse.agent.AgentTaskType.MANGA_REVIEW, "original-plan:1");
        var context = withAnswer(TestContexts.context(run.getRequestId(), run), resume);

        node.run(context);

        verify(storyboard, never()).run(any());
        var captured = forClass(MangaWorkflowExecutionContext.class);
        verify(review).run(captured.capture());
        assertThat(captured.getValue().stepId()).isEqualTo("original-plan:1");
        assertThat(captured.getValue().answerResume()).isSameAs(resume);
        assertThat(captured.getValue().message()).contains("original storyboard handoff");
        assertThat(captured.getValue().executionClaim()).isSameAs(context.executionClaim());
    }

    @Test
    void anAlreadyCompletedAnswerNodeRequiresItsCompletionReceiptBeforeContinuing() {
        var registry = mock(MangaWorkflowNodeRegistry.class);
        var runs = mock(MangaAgentRunService.class);
        var review = mock(MangaWorkflowNodeHandler.class);
        var node = new MangaDirectorAgentNode(registry, runs, objectMapper, mock(MangaAgentExecutionSupport.class), new ExecutionPlanValidator());
        var run = new MangaAgentRun(); run.setRequestId(UUID.randomUUID());
        var first = new com.artverse.application.workflow.ExecutionStep(0, MangaWorkflowRoute.STORYBOARD, "storyboard", true);
        first.markCompleted("saved storyboard", "original handoff");
        var second = new com.artverse.application.workflow.ExecutionStep(1, MangaWorkflowRoute.REVIEW, "review", false);
        var plan = new com.artverse.application.workflow.ExecutionPlan("original-plan", List.of(first, second),
                RoutingDecision.CURRENT_VERSION, java.time.OffsetDateTime.now());
        when(runs.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runs.routingDecision(run)).thenReturn(new RoutingDecision(MangaWorkflowRoute.DIRECTOR, .9, List.of("resume"), true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW), RoutingDecision.CURRENT_VERSION));
        when(runs.executionPlan(run)).thenReturn(plan);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(review);
        when(review.run(any())).thenReturn(MangaWorkflowResult.success("review finished"));
        var turn = mock(com.artverse.agent.gateway.AgentSessionTurns.Lease.class);
        var resume = new MangaWorkflowExecutionContext.AnswerResume(new com.artverse.agent.AgentAnswerDelivery(
                UUID.randomUUID(), "original-question", 3L, "hash", "{}", 11L), turn,
                com.artverse.agent.AgentTaskType.MANGA_STORYBOARD, "original-plan:0");
        var context = withAnswer(TestContexts.context(run.getRequestId(), run), resume);

        node.run(context);

        var order = org.mockito.Mockito.inOrder(runs, turn, review);
        order.verify(runs).requireCompletedAnswerStep(context);
        order.verify(turn).close();
        order.verify(review).run(any());
    }

    private MangaWorkflowExecutionContext withAnswer(MangaWorkflowExecutionContext base, MangaWorkflowExecutionContext.AnswerResume resume) {
        return new MangaWorkflowExecutionContext(base.conversation(), base.message(), base.requestId(), base.llmApiKey(), base.modelSpec(),
                base.toolState(), base.user(), base.chapter(), base.workflowContext(), false, resume.stepId(), base.executionClaim(), resume);
    }

    @Test
    void executionPlanRoundTripsWithStepState() throws Exception {
        com.artverse.application.workflow.ExecutionStep step =
                new com.artverse.application.workflow.ExecutionStep(0, MangaWorkflowRoute.REVIEW, "review", false);
        step.markRunning("input");
        step.markCompleted("summary", "full handoff");
        com.artverse.application.workflow.ExecutionPlan plan = new com.artverse.application.workflow.ExecutionPlan(
                "plan-1", List.of(step), RoutingDecision.CURRENT_VERSION, java.time.OffsetDateTime.now());

        String json = objectMapper.writeValueAsString(plan);
        com.artverse.application.workflow.ExecutionPlan restored =
                objectMapper.readValue(json, com.artverse.application.workflow.ExecutionPlan.class);

        assertThat(restored.steps()).hasSize(1);
        assertThat(restored.steps().getFirst().status()).isEqualTo("COMPLETED");
        assertThat(restored.steps().getFirst().handoffContext()).isEqualTo("full handoff");
    }

    @Test
    void mutatingStepFailureFailsDirectorRun() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaWorkflowNodeHandler storyboard = mock(MangaWorkflowNodeHandler.class);
        MangaAgentExecutionSupport support = mock(MangaAgentExecutionSupport.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(registry, runService, objectMapper, support,
                new ExecutionPlanValidator());
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(requestId);
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.95, List.of("write", "review"), true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW), RoutingDecision.CURRENT_VERSION);
        when(runService.findRun(1L, 7L, requestId)).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(storyboard);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(mock(MangaWorkflowNodeHandler.class));
        when(storyboard.run(any())).thenThrow(new BusinessException(502, "write failed"));

        assertThatThrownBy(() -> node.run(TestContexts.context(requestId, run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Director plan failed");
    }

    @Test
    void readOnlyContractViolationAlsoFailsDirectorRun() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaWorkflowNodeHandler creative = mock(MangaWorkflowNodeHandler.class);
        MangaWorkflowNodeHandler review = mock(MangaWorkflowNodeHandler.class);
        MangaAgentExecutionSupport support = mock(MangaAgentExecutionSupport.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(registry, runService, objectMapper, support,
                new ExecutionPlanValidator());
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(requestId);
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.95, List.of("creative"), false, false, "test",
                List.of(MangaWorkflowRoute.CREATIVE, MangaWorkflowRoute.REVIEW), RoutingDecision.CURRENT_VERSION);
        when(runService.findRun(1L, 7L, requestId)).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);
        when(registry.handlerFor(MangaWorkflowRoute.CREATIVE)).thenReturn(creative);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(review);
        when(creative.run(any())).thenThrow(new ToolContractViolationException("forbidden_tool:commit_storyboard"));

        assertThatThrownBy(() -> node.run(TestContexts.context(requestId, run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Director plan failed");
    }

    @Test
    void rejectsPlanWithDirectorStep() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(
                registry, runService, objectMapper, mock(MangaAgentExecutionSupport.class),
                new ExecutionPlanValidator());

        // Build a RoutingDecision with DIRECTOR in suggestedSteps
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("director"),
                false, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.DIRECTOR),
                RoutingDecision.CURRENT_VERSION
        );
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);
        when(runService.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);

        // The plan validation should reject DIRECTOR in steps
        assertThatThrownBy(() -> node.run(TestContexts.context(run.getRequestId(), run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("recursive_director");
    }

    @Test
    void rejectsPlanWithTooManySteps() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(
                registry, runService, objectMapper, mock(MangaAgentExecutionSupport.class),
                new ExecutionPlanValidator());

        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("multi"),
                false, false, "test",
                List.of(MangaWorkflowRoute.CREATIVE, MangaWorkflowRoute.STORYBOARD,
                        MangaWorkflowRoute.REVIEW, MangaWorkflowRoute.CONVERSATION),
                RoutingDecision.CURRENT_VERSION
        );
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);
        when(runService.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);

        assertThatThrownBy(() -> node.run(TestContexts.context(run.getRequestId(), run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("too_many_steps");
    }

    @Test
    void rejectsPlanWithMultipleMutatingSteps() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(mock());
        when(registry.handlerFor(MangaWorkflowRoute.CONVERSATION)).thenReturn(mock());
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(
                registry, runService, objectMapper, mock(MangaAgentExecutionSupport.class),
                new ExecutionPlanValidator());

        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("write"),
                true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.STORYBOARD),
                RoutingDecision.CURRENT_VERSION
        );
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);
        when(runService.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);

        assertThatThrownBy(() -> node.run(TestContexts.context(run.getRequestId(), run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("duplicate_mutating_step");
    }

    private String toJson(RoutingDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision);
        } catch (Exception error) {
            throw new AssertionError("Failed to serialize routing decision fixture", error);
        }
    }

}
