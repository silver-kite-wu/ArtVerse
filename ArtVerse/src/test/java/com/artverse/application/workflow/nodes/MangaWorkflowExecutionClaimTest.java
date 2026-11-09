package com.artverse.application.workflow.nodes;

import com.artverse.agent.*;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.*;
import com.artverse.application.workflow.MangaReviewMetrics;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.MangaAgentRunEventRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.artverse.persistence.MangaAgentRunStepRepository;
import com.artverse.application.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MangaWorkflowExecutionClaimTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final MangaAgentRunRepository repository = mock(MangaAgentRunRepository.class);
    final AgentRunLeaseService leases = mock(AgentRunLeaseService.class);
    final MangaAgentRunStepRepository steps = mock(MangaAgentRunStepRepository.class);
    final MangaAgentRunService runs = new MangaAgentRunService(repository,
            mock(MangaAgentRunEventRepository.class), mapper, steps, null, leases);
    final AgentScopeHarnessAgentGateway gateway = mock(AgentScopeHarnessAgentGateway.class);
    final ApiKeyService keys = mock(ApiKeyService.class);
    final MangaAgentExecutionSupport support = new MangaAgentExecutionSupport(
            mock(MangaAgentConversationService.class), mock(AgentWorkspaceSyncService.class), keys, gateway,
            new ArtVerseProperties(), mock(MangaReviewMetrics.class), null, runs, mapper);

    @Test void workflowKeepsTheOriginalClaimAndCannotAdoptANewDatabaseFence() {
        MangaAgentRun run = run();
        var original = MangaWorkflowExecutionContext.ExecutionClaim.from(run);
        var context = context(run, original);
        var first = support.buildRunRequest(context, List.of(), AgentTaskType.MANGA_STORYBOARD);
        assertThat(first.variables()).containsEntry("fencing_token", 1L).containsEntry("execution_owner", "worker-original");

        run.setFencingToken(2L); run.setOwnerInstanceId("worker-replacement");
        when(leases.owns(run, 2L)).thenReturn(true);
        assertThatThrownBy(() -> support.buildRunRequest(context, List.of(), AgentTaskType.MANGA_STORYBOARD))
                .hasMessageContaining("RUN_NOT_ACTIVE");
        assertThat(original.owner()).isEqualTo("worker-original");
        assertThat(original.fencingToken()).isEqualTo(1L);

        var replacement = support.buildRunRequest(context(run, MangaWorkflowExecutionContext.ExecutionClaim.from(run)),
                List.of(), AgentTaskType.MANGA_STORYBOARD);
        assertThat(replacement.variables()).containsEntry("fencing_token", 2L).containsEntry("execution_owner", "worker-replacement");
        verifyNoInteractions(gateway);
    }

    @Test void aWorkflowCannotBorrowADifferentOwnerEvenAtTheSameFence() {
        MangaAgentRun run = run();
        var original = MangaWorkflowExecutionContext.ExecutionClaim.from(run);
        run.setOwnerInstanceId("different-owner");
        assertThatThrownBy(() -> support.buildRunRequest(context(run, original), List.of(), AgentTaskType.MANGA_REVIEW))
                .hasMessageContaining("original workflow owner changed");
        verifyNoInteractions(gateway);
    }

    @Test void missingClaimIsRejectedBeforeReadingCredentialsOrDispatching() {
        MangaAgentRun run = run();
        clearInvocations(keys, repository);
        assertThatThrownBy(() -> support.buildRunRequest(context(run, null), List.of(), AgentTaskType.MANGA_STORYBOARD))
                .hasMessageContaining("original execution claim");
        verifyNoInteractions(keys, repository, gateway);
    }

    @Test void toolExecutionUsesTheDatabaseLeaseVerdictInsteadOfTheJvmTimestamp() {
        MangaAgentRun run = run();
        runs.bindMangaExecution(context(run, MangaWorkflowExecutionContext.ExecutionClaim.from(run)), AgentTaskType.MANGA_STORYBOARD);
        clearInvocations(leases);
        var scope = scope(run);
        // The entity's JVM timestamp is deliberately old; the authoritative SQL lease verdict permits it.
        run.setLeaseUntil(OffsetDateTime.now().minusYears(1));
        runs.requireToolExecution(scope, false);
        verify(leases).owns(run, 1L);
        run.setLeaseUntil(OffsetDateTime.now().plusYears(1));
        when(leases.owns(run, 1L)).thenReturn(false);
        assertThatThrownBy(() -> runs.requireToolExecution(scope, false)).hasMessageContaining("RUN_NOT_ACTIVE");
    }

    @Test void summaryRequiresTheReceiptMatchingTheCurrentClaimedContinuation() throws Exception {
        MangaAgentRun run = run(); run.setRunType(AgentRunType.STORY_CHAT); run.setRouteKey("WRITE");
        var artifact = UUID.randomUUID().toString();
        var receipt = Map.of("artifact_id", artifact, "status", "COMMITTED", "chapter_id", 7L);
        var continuation = new LinkedHashMap<String, Object>(Map.of("artifact_id", artifact,
                "fencing_token", 1L, "id", UUID.randomUUID().toString()));
        var attributes = new LinkedHashMap<String, Object>(Map.of("review_result", receipt,
                "review_summary_continuation", continuation, "review_summary_status", "PENDING",
                "story_chat_execution", Map.of("version", 1, "profile", "local-tools-v1", "route", "WRITE",
                        "task_type", "STORY_CHAT_WRITE", "step_id", "story-chat:WRITE")));
        run.setRunAttributesJson(mapper.writeValueAsString(attributes));
        runs.requireToolExecution(scope(run), true);

        continuation.put("artifact_id", UUID.randomUUID().toString());
        run.setRunAttributesJson(mapper.writeValueAsString(attributes));
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run), true)).hasMessageContaining("review continuation");
        continuation.put("artifact_id", artifact); continuation.put("fencing_token", 2L);
        run.setRunAttributesJson(mapper.writeValueAsString(attributes));
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run), true)).hasMessageContaining("review continuation");
        continuation.put("fencing_token", 1L); attributes.put("review_summary_status", "COMPLETED");
        run.setRunAttributesJson(mapper.writeValueAsString(attributes));
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run), true)).hasMessageContaining("review continuation");
        attributes.remove("review_summary_continuation");
        run.setRunAttributesJson(mapper.writeValueAsString(attributes));
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run), true)).hasMessageContaining("review continuation");
    }

    @Test void toolContextCannotAdoptANewOwnerAtTheSameFence() {
        MangaAgentRun run = run();
        runs.bindMangaExecution(context(run, MangaWorkflowExecutionContext.ExecutionClaim.from(run)), AgentTaskType.MANGA_STORYBOARD);
        var original = scope(run);
        run.setOwnerInstanceId("replacement-owner");
        assertThatThrownBy(() -> runs.requireToolExecution(original, false)).hasMessageContaining("execution owner changed");
        assertThatThrownBy(() -> runs.requireToolExecution(new MangaAgentRuntimeContext(1L, 3L, 7L,
                run.getConversation().getConversationUuid(), run.getRequestId(), null, AgentTaskType.MANGA_STORYBOARD,
                "manga-storyboard", 1L), false)).hasMessageContaining("execution owner changed");
    }

    @Test void aDirectWorkflowRequiresItsPersistedTaskStepAndProfile() throws Exception {
        MangaAgentRun run = run();
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run), false)).hasMessageContaining("binding missing");
        var workflow = context(run, MangaWorkflowExecutionContext.ExecutionClaim.from(run));
        runs.bindMangaExecution(workflow, AgentTaskType.MANGA_STORYBOARD);
        runs.requireToolExecution(scope(run), false);
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run, AgentTaskType.MANGA_CONVERSATION, "manga-storyboard"), false))
                .hasMessageContaining("task differs");
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run, AgentTaskType.MANGA_STORYBOARD, "unrelated:0"), false))
                .hasMessageContaining("task differs");
        var attributes = mapper.readValue(run.getRunAttributesJson(), Map.class);
        @SuppressWarnings("unchecked") var bindings = (Map<String, Map<String, Object>>) attributes.get("manga_execution_bindings");
        bindings.get("manga-storyboard").put("profile", "old-tools-v0");
        run.setRunAttributesJson(mapper.writeValueAsString(attributes));
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run), false)).hasMessageContaining("binding missing or changed");
    }

    @Test void directorBindsOnlyTheRealRunningStepAndItsTask() throws Exception {
        MangaAgentRun run = run(); run.setRoute(MangaWorkflowRoute.DIRECTOR);
        var first = new ExecutionStep(0, MangaWorkflowRoute.STORYBOARD, "storyboard", true);
        var second = new ExecutionStep(1, MangaWorkflowRoute.REVIEW, "review", false);
        first.markRunning("source");
        var plan = new ExecutionPlan("real-plan", List.of(first, second), RoutingDecision.CURRENT_VERSION, OffsetDateTime.now());
        run.setExecutionPlanJson(mapper.writeValueAsString(plan));
        MangaAgentRunStep persisted = new MangaAgentRunStep();
        persisted.setRun(run); persisted.setPlanId("real-plan"); persisted.setStepSequence(0);
        persisted.setRoute(MangaWorkflowRoute.STORYBOARD); persisted.setStatus("RUNNING");
        when(steps.findByRunIdAndPlanIdAndStepSequence(run.getId(), "real-plan", 0)).thenReturn(Optional.of(persisted));
        var base = context(run, MangaWorkflowExecutionContext.ExecutionClaim.from(run));
        var child = new MangaWorkflowExecutionContext(base.conversation(), base.message(), base.requestId(), base.llmApiKey(),
                base.modelSpec(), base.toolState(), base.user(), base.chapter(), base.workflowContext(), false, "real-plan:0", base.executionClaim());
        runs.bindMangaExecution(child, AgentTaskType.MANGA_STORYBOARD);
        runs.requireToolExecution(scope(run, AgentTaskType.MANGA_STORYBOARD, "real-plan:0"), false);
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run, AgentTaskType.MANGA_REVIEW, "real-plan:0"), false))
                .hasMessageContaining("task differs");
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run, AgentTaskType.MANGA_STORYBOARD, "forged-plan:0"), false))
                .hasMessageContaining("unknown workflow step");
        persisted.setStatus("COMPLETED");
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run, AgentTaskType.MANGA_STORYBOARD, "real-plan:0"), false))
                .hasMessageContaining("step is not active");
        persisted.setStatus("RUNNING"); persisted.setRoute(MangaWorkflowRoute.REVIEW);
        assertThatThrownBy(() -> runs.requireToolExecution(scope(run, AgentTaskType.MANGA_STORYBOARD, "real-plan:0"), false))
                .hasMessageContaining("step is not active");
    }

    @Test void aSingleDirectorDelegationCannotInventAnotherRoute() throws Exception {
        MangaAgentRun run = run(); run.setRoute(MangaWorkflowRoute.DIRECTOR);
        var decision = new RoutingDecision(MangaWorkflowRoute.DIRECTOR, 1, List.of(), false, false, "test",
                List.of(MangaWorkflowRoute.REVIEW), RoutingDecision.CURRENT_VERSION);
        run.setRoutingDecisionJson(mapper.writeValueAsString(decision));
        var original = context(run, MangaWorkflowExecutionContext.ExecutionClaim.from(run));
        var delegated = new MangaWorkflowExecutionContext(original.conversation(), original.message(), original.requestId(),
                original.llmApiKey(), original.modelSpec(), original.toolState(), original.user(), original.chapter(),
                null, false, null, original.executionClaim());
        runs.bindMangaExecution(delegated, AgentTaskType.MANGA_REVIEW);
        runs.requireToolExecution(scope(run, AgentTaskType.MANGA_REVIEW, "manga-review"), false);
        assertThatThrownBy(() -> runs.bindMangaExecution(delegated, AgentTaskType.MANGA_STORYBOARD)).hasMessageContaining("task differs");
    }

    @Test void oldCallbacksCannotFailWaitFinalizeOrRewriteTheNewClaim() {
        MangaAgentRun run = run();
        var original = MangaWorkflowExecutionContext.ExecutionClaim.from(run);
        var workflow = context(run, original);
        run.setOwnerInstanceId("worker-new"); run.setFencingToken(2L);
        when(leases.owns(run, 2L)).thenReturn(true);
        var question = new AgentUserInputRequest("Q1", List.of(), true, "old", "ROUTING");
        assertThatThrownBy(() -> runs.markFailed(run.getConversation(), run.getRequestId(), "old failure", original)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThatThrownBy(() -> runs.markWaiting(run.getConversation(), run.getRequestId(), question, original)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThatThrownBy(() -> runs.requireFinalizable(run.getConversation(), run.getRequestId(), original)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThatThrownBy(() -> runs.completeVerified(run.getConversation(), run.getRequestId(), "old reply", false, "v1", Map.of(), original)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThatThrownBy(() -> runs.updateExecutionPlan(workflow, "{}")).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThatThrownBy(() -> runs.markRouting(run, original)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.RUNNING);
        assertThat(run.getFinalReply()).isNull();
        verify(repository, never()).save(run);
    }

    @Test void waitingReleasesTheClaimAndRejectsLateFailureAndSuccess() {
        MangaAgentRun run = run();
        var original = MangaWorkflowExecutionContext.ExecutionClaim.from(run);
        var question = new AgentUserInputRequest("Q2", List.of(), true, "current", "ROUTING");
        runs.markWaiting(run.getConversation(), run.getRequestId(), question, original);
        assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
        assertThat(run.getOwnerInstanceId()).isNull();
        assertThat(run.getLeaseUntil()).isNull();
        runs.markWaiting(run.getConversation(), run.getRequestId(), question, original);
        assertThatThrownBy(() -> runs.markFailed(run.getConversation(), run.getRequestId(), "late failure", original)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThatThrownBy(() -> runs.requireFinalizable(run.getConversation(), run.getRequestId(), original)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThat(run.getUserInputRequestJson()).contains("Q2");
    }

    @Test void aCachedReplyDoesNotCompleteAnActiveContinuation() {
        MangaAgentRun run = run();
        assertThat(runs.reconcileCachedReply(run.getConversation(), run.getRequestId(), "old cached reply")).contains(run);
        assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.RUNNING);
        assertThat(run.getFinalReply()).isNull();
        verify(repository, never()).save(run);
    }

    private MangaAgentRun run() {
        User user = new User(); user.setId(1L);
        Story story = new Story(); story.setId(3L); story.setUser(user);
        Chapter chapter = new Chapter(); chapter.setId(7L); chapter.setStory(story);
        MangaAgentConversation conversation = new MangaAgentConversation(); conversation.setId(9L);
        conversation.setConversationUuid(UUID.randomUUID()); conversation.setStory(story);
        conversation.setUser(user); conversation.setChapter(chapter);
        MangaAgentRun run = new MangaAgentRun(); run.setId(99L); run.setRequestId(UUID.randomUUID());
        run.setUser(user); run.setStory(story); run.setChapter(chapter); run.setConversation(conversation);
        run.setStatus(MangaAgentRunStatus.RUNNING); run.setOwnerInstanceId("worker-original"); run.setFencingToken(1L);
        run.setRoute(MangaWorkflowRoute.STORYBOARD);
        run.setLeaseUntil(OffsetDateTime.now().plusMinutes(1)); run.setRunAttributesJson("{}");
        when(repository.findByUserIdAndChapterIdAndRequestId(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(repository.findForUpdate(9L, run.getRequestId())).thenReturn(Optional.of(run));
        when(repository.findByConversationIdAndRequestId(9L, run.getRequestId())).thenReturn(Optional.of(run));
        when(repository.findById(run.getId())).thenReturn(Optional.of(run));
        when(repository.findForUpdateById(run.getId())).thenReturn(Optional.of(run));
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(contains("SELECT r.id"), any(Object[].class)))
                .thenAnswer(invocation -> List.of(Map.of("id", run.getId(), "attributes", run.getRunAttributesJson())));
        runs.setAutomationClaimJdbc(jdbc);
        when(leases.owns(run, 1L)).thenReturn(true);
        when(keys.activeUserProviderConfigOrBlank(user, ApiKeyService.SLOT_WORKFLOW))
                .thenReturn(new UserProviderConfig(ApiKeyService.SLOT_WORKFLOW, "", "", "", "", ""));
        return run;
    }

    private MangaWorkflowExecutionContext context(MangaAgentRun run, MangaWorkflowExecutionContext.ExecutionClaim claim) {
        return new MangaWorkflowExecutionContext(run.getConversation(), "storyboard", run.getRequestId(), "",
                new AgentModelSpec("fixture", "https://example.test", "fixture", "hash"),
                mock(AgentRunToolStatus.RunState.class), run.getUser(), run.getChapter(), null,
                true, "manga-storyboard", claim);
    }

    private MangaAgentRuntimeContext scope(MangaAgentRun run) {
        return scope(run, run.getRunType() == AgentRunType.STORY_CHAT ? AgentTaskType.STORY_CHAT_WRITE : AgentTaskType.MANGA_STORYBOARD,
                run.getRunType() == AgentRunType.STORY_CHAT ? "story-chat:WRITE" : "manga-storyboard");
    }

    private MangaAgentRuntimeContext scope(MangaAgentRun run, AgentTaskType type, String step) {
        return new MangaAgentRuntimeContext(1L, 3L, 7L, run.getConversation().getConversationUuid(), run.getRequestId(),
                null, type, step, run.getFencingToken(), null, run.getOwnerInstanceId());
    }
}
