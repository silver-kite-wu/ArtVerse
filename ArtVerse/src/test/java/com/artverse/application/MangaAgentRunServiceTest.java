package com.artverse.application;

import com.artverse.agent.BusinessSkillSelection;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunEventRecord;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.MangaAgentRunStep;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.RoutingDecision;
import com.artverse.persistence.MangaAgentRunEventRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.artverse.persistence.MangaAgentRunStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

class MangaAgentRunServiceTest {

    @Test
    void recordingAValidatedExternalQuestionAlsoEntersTheDurableWaitingState() {
        var fixture = fixture();
        var leases = mock(AgentRunLeaseService.class);
        var service = new MangaAgentRunService(fixture.runRepository, fixture.eventRepository,
                fixture.objectMapper, null, null, leases);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT r.id,r.run_attributes_json"),
                any(Object[].class))).thenReturn(List.of(Map.of("id", 99L, "attributes", "{}")));
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("FROM automation_run a"),
                any(Object[].class))).thenReturn(List.of());
        service.setAutomationClaimJdbc(jdbc);

        var requestId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var run = run(fixture.user, fixture.chapter, requestId, "修改正文");
        var conversation = new com.artverse.domain.MangaAgentConversation();
        conversation.setId(12L);
        conversation.setConversationUuid(conversationId);
        conversation.setUser(fixture.user);
        conversation.setStory(fixture.chapter.getStory());
        conversation.setChapter(fixture.chapter);
        run.setConversation(conversation);
        run.setFencingToken(1L);
        run.setOwnerInstanceId("worker");
        run.setRunAttributesJson("{}");
        when(fixture.runRepository.findForUpdate(1L, 7L, requestId)).thenReturn(Optional.of(run));
        when(fixture.runRepository.findForUpdate(12L, requestId)).thenReturn(Optional.of(run));
        when(fixture.runRepository.saveAndFlush(run)).thenReturn(run);
        when(leases.owns(run, 1L)).thenReturn(true);
        var context = new com.artverse.agent.MangaAgentRuntimeContext(
                1L, 3L, 7L, conversationId, requestId, null,
                com.artverse.agent.AgentTaskType.STORY_CHAT_WRITE, "story-chat:WRITE", 1L, null, "worker");

        var recorded = service.recordExternalQuestion(context, "question-1",
                new AgentUserInputRequest("确认草稿？", List.of(), true, ""));

        assertThat(recorded.created()).isTrue();
        service.markWaiting(conversation, requestId, recorded.request(),
                new com.artverse.application.workflow.MangaWorkflowExecutionContext.ExecutionClaim("worker", 1L));
        service.requireWaitingExecutionClaim(conversation, requestId,
                new com.artverse.application.workflow.MangaWorkflowExecutionContext.ExecutionClaim("worker", 1L));
        assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
        assertThat(run.getUserInputRequestJson()).contains("question-1");
        assertThat(run.getRunAttributesJson()).contains("external_questions", "WAITING");
        assertThat(run.getOwnerInstanceId()).isNull();
    }

    @Test
    void resumedExecutionCannotChangeThePersistedModelCapabilityDeclaration() throws Exception {
        Fixture fixture = fixture();
        var conversation = new com.artverse.domain.MangaAgentConversation();
        conversation.setConversationUuid(UUID.randomUUID());
        var run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "fixture");
        run.setConversation(conversation); run.setFencingToken(1L); run.setOwnerInstanceId("original-worker");
        run.setRunAttributesJson("{}");
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT r.id"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 1L, "attributes", "{}")));
        fixture.service.setAutomationClaimJdbc(jdbc);
        when(fixture.runRepository.findForUpdate(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(fixture.runRepository.saveAndFlush(run)).thenReturn(run);
        var first = new com.artverse.agent.AgentModelSpec("fixture", "https://provider.example.test/v1", "fixture-model", "hash", null,
                new com.artverse.agent.AgentModelCapabilities(true, false, false, "v1"));
        var request = new com.artverse.agent.AgentRunRequest("1", fixture.chapter.getStory().getId(), 7L,
                com.artverse.agent.AgentTaskType.STORY_CHAT_WRITE, List.of(), Map.of("fencing_token", 1L, "execution_owner", "original-worker", "step_id", "story-chat:WRITE"), first,
                "fixture-secret-must-not-be-persisted", run.getRequestId(), conversation.getConversationUuid());
        fixture.service.bindModelCapabilities(request);
        fixture.service.bindModelCapabilities(request);
        assertThat(run.getRunAttributesJson()).contains("model_capability_bindings", "v1")
                .doesNotContain("fixture-secret-must-not-be-persisted");
        var changed = new com.artverse.agent.AgentModelSpec(first.provider(), first.baseUrl(), first.model(), first.apiKeyHash(), null,
                new com.artverse.agent.AgentModelCapabilities(true, true, true, "v2"));
        var resume = new com.artverse.agent.AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                request.messages(), request.variables(), changed, request.llmApiKey(), request.requestId(), request.conversationId());
        assertThatThrownBy(() -> fixture.service.bindModelCapabilities(resume)).hasMessageContaining("能力声明已变化");
        verify(fixture.runRepository, times(1)).saveAndFlush(run);
    }

    @Test
    void resumeStartKeepsOriginalInputAndClearsWaitingRequest() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun existing = run(fixture.user, fixture.chapter, requestId, "原始任务");
        existing.setStatus(MangaAgentRunStatus.WAITING_USER);
        existing.setUserInputRequestJson("{\"question\":\"选择方案\",\"options\":[],\"allowFreeText\":true,\"reason\":\"\"}");

        when(fixture.runRepository.findForUpdate(1L, 7L, requestId))
                .thenReturn(Optional.of(existing));
        when(fixture.runRepository.saveAndFlush(any(MangaAgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MangaAgentRun result = fixture.service.startOrReuse(fixture.user, fixture.chapter, requestId, "恢复提示");

        assertThat(result.getStatus()).isEqualTo(MangaAgentRunStatus.RUNNING);
        assertThat(result.getInputMessage()).isEqualTo("原始任务");
        assertThat(result.getUserInputRequestJson()).isNull();
    }

    @Test
    void answerContinuationKeepsOriginalRouteAndClaimsExactlyOnce() throws Exception {
        Fixture fixture = fixture();
        var leases = mock(AgentRunLeaseService.class);
        var service = new MangaAgentRunService(fixture.runRepository, fixture.eventRepository,
                fixture.objectMapper, null, null, leases);
        var conversation = new com.artverse.domain.MangaAgentConversation(); conversation.setId(12L);
        var run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "原始审核任务");
        run.setRunType(com.artverse.domain.AgentRunType.STORY_CHAT);
        run.setRouteKey("REVIEW"); run.setStatus(MangaAgentRunStatus.WAITING_USER);
        run.setUserInputRequestJson(fixture.objectMapper.writeValueAsString(new AgentUserInputRequest(
                "说明关注范围", List.of(), true, "", "USER_QUESTION", "question-1", 1L)));
        run.setRunAttributesJson(fixture.objectMapper.writeValueAsString(Map.of("external_questions",
                Map.of("question-1", Map.of("pending_version", 1L, "status", "WAITING")))));
        when(fixture.runRepository.findForUpdate(12L, run.getRequestId())).thenReturn(Optional.of(run));
        when(fixture.runRepository.saveAndFlush(run)).thenReturn(run);
        when(leases.claim(run)).thenReturn(run);
        when(leases.hasActiveLease(run)).thenAnswer(ignored -> run.getStatus() == MangaAgentRunStatus.RUNNING);

        var claimed = answer(service, conversation, run.getRequestId(), "question-1", 1L, "focus");
        assertThat(claimed.run()).isSameAs(run);
        assertThat(claimed.claimed()).isTrue();
        assertThat(run.getRouteKey()).isEqualTo("REVIEW");
        assertThat(run.getInputMessage()).isEqualTo("原始审核任务");
        assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.RUNNING);
        assertThat(answer(service, conversation, run.getRequestId(), "question-1", 1L, "focus").claimed()).isFalse();
        assertThatThrownBy(() -> answer(service, conversation, run.getRequestId(), "question-1", 1L, "changed"))
                .hasMessageContaining("另一份答复");
        verify(leases, times(1)).claim(run);
    }

    @Test
    void sameLabelOptionsRemainDifferentAnswersAndInvalidBranchesDoNotClaim() throws Exception {
        Fixture fixture = fixture();
        var leases = mock(AgentRunLeaseService.class);
        var service = new MangaAgentRunService(fixture.runRepository, fixture.eventRepository,
                fixture.objectMapper, null, null, leases);
        var conversation = new com.artverse.domain.MangaAgentConversation(); conversation.setId(12L);
        var run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "Choose");
        run.setRunType(com.artverse.domain.AgentRunType.STORY_CHAT);
        run.setStatus(MangaAgentRunStatus.WAITING_USER);
        var pending = new AgentUserInputRequest("Choose", List.of(
                new AgentUserInputRequest.Option("a", "Same label", "First", false),
                new AgentUserInputRequest.Option("b", "Same label", "Second", true)),
                false, "", "USER_QUESTION", "question-1", 1L);
        run.setUserInputRequestJson(fixture.objectMapper.writeValueAsString(pending));
        run.setRunAttributesJson(fixture.objectMapper.writeValueAsString(Map.of("external_questions",
                Map.of("question-1", Map.of("pending_version", 1L, "status", "WAITING", "request", pending)))));
        when(fixture.runRepository.findForUpdate(12L, run.getRequestId())).thenReturn(Optional.of(run));
        when(fixture.runRepository.saveAndFlush(run)).thenReturn(run);
        when(leases.claim(run)).thenReturn(run);
        when(leases.hasActiveLease(run)).thenAnswer(ignored -> run.getStatus() == MangaAgentRunStatus.RUNNING);
        assertThatThrownBy(() -> answer(service, conversation, run.getRequestId(), "question-1", 1L, "Same label", null))
                .hasMessageContaining("仅接受原选项");
        assertThatThrownBy(() -> answer(service, conversation, run.getRequestId(), "question-1", 1L, "", "b"))
                .hasMessageContaining("一项选择");
        verifyNoInteractions(leases);

        var accepted = answer(service, conversation, run.getRequestId(), "question-1", 1L, null, "b");
        assertThat(accepted.claimed()).isTrue();
        assertThat(accepted.answer()).isEqualTo("Same label");
        assertThat(answer(service, conversation, run.getRequestId(), "question-1", 1L, null, "b").claimed()).isFalse();
        assertThatThrownBy(() -> answer(service, conversation, run.getRequestId(), "question-1", 1L, null, "a"))
                .hasMessageContaining("另一份答复");
        verify(leases, times(1)).claim(run);
    }

    @Test
    void ordinaryAnswerCannotConsumeAReviewDecision() throws Exception {
        Fixture fixture = fixture();
        var leases = mock(AgentRunLeaseService.class);
        var service = new MangaAgentRunService(fixture.runRepository, fixture.eventRepository,
                fixture.objectMapper, null, null, leases);
        var conversation = new com.artverse.domain.MangaAgentConversation(); conversation.setId(12L);
        var run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "润色正文");
        run.setRunType(com.artverse.domain.AgentRunType.STORY_CHAT);
        run.setStatus(MangaAgentRunStatus.WAITING_USER);
        run.setUserInputRequestJson(fixture.objectMapper.writeValueAsString(new AgentUserInputRequest(
                "审阅草稿", List.of(), false, "", "BUSINESS_CONFIRMATION")));
        when(fixture.runRepository.findForUpdate(12L, run.getRequestId())).thenReturn(Optional.of(run));
        assertThatThrownBy(() -> answer(service, conversation, run.getRequestId(), "question-1", 1L, "focus"))
                .isInstanceOf(com.artverse.common.BusinessException.class);
        assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
        verifyNoInteractions(leases);
    }

    private MangaAgentRunService.AnswerContinuation answer(MangaAgentRunService service,
            com.artverse.domain.MangaAgentConversation conversation, UUID requestId, String call, Long version, String text) {
        return answer(service, conversation, requestId, call, version, text, null);
    }

    private MangaAgentRunService.AnswerContinuation answer(MangaAgentRunService service,
            com.artverse.domain.MangaAgentConversation conversation, UUID requestId, String call, Long version, String text, String option) {
        return service.claimAnswerDelivery(conversation, requestId,
                service.acceptAnswer(conversation, requestId, call, version, text, option));
    }

    @Test
    void repeatedStartCannotTakeOverAnAlreadyRunningTask() {
        Fixture fixture = fixture();
        var run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "原始任务");
        when(fixture.runRepository.findForUpdate(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        assertThatThrownBy(() -> fixture.service.startOrReuse(fixture.user, fixture.chapter, run.getRequestId(), "重复请求"))
                .hasMessageContaining("正在执行");
        verify(fixture.runRepository, never()).saveAndFlush(any());
    }

    @Test
    void originalExecutionBindingCannotBeReplacedWithAWriteRoute() throws Exception {
        Fixture fixture = fixture();
        var conversation = new com.artverse.domain.MangaAgentConversation(); conversation.setId(12L);
        var run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "读取正文");
        run.setRunType(com.artverse.domain.AgentRunType.STORY_CHAT);
        when(fixture.runRepository.findForUpdate(12L, run.getRequestId())).thenReturn(Optional.of(run));
        fixture.service.bindStoryChatExecution(conversation, run.getRequestId(), Map.of("route", "READ"));
        fixture.service.bindStoryChatExecution(conversation, run.getRequestId(), Map.of("route", "READ"));
        assertThatThrownBy(() -> fixture.service.bindStoryChatExecution(conversation, run.getRequestId(), Map.of("route", "WRITE")))
                .hasMessageContaining("已经固定");
        assertThat(fixture.objectMapper.readTree(run.getRunAttributesJson()).path("story_chat_execution").path("route").asText())
                .isEqualTo("READ");
    }

    @Test
    void snapshotRestoresWaitingInputAndPersistedEvents() throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(fixture.user, fixture.chapter, requestId, "生成分镜");
        run.setStatus(MangaAgentRunStatus.WAITING_USER);
        AgentUserInputRequest waiting = new AgentUserInputRequest(
                "覆盖已有分镜吗？",
                List.of(new AgentUserInputRequest.Option("a", "覆盖", "使用新分镜", true)),
                true,
                "已有旧分镜"
        );
        run.setUserInputRequestJson(fixture.objectMapper.writeValueAsString(waiting));

        MangaAgentRunEventRecord event = new MangaAgentRunEventRecord();
        event.setId(10L);
        event.setRun(run);
        event.setEventName("run_event");
        event.setEventType("tool_call_started");
        event.setPhase("tool");
        event.setLabel("准备调用：保存结构化分镜");
        event.setStatus("running");
        event.setPayloadJson(fixture.objectMapper.writeValueAsString(Map.of(
                "type", "tool_call_started",
                "phase", "tool",
                "label", "准备调用：保存结构化分镜",
                "status", "running",
                "data", Map.of()
        )));

        when(fixture.eventRepository.findByRunIdOrderByIdAsc(99L)).thenReturn(List.of(event));

        MangaAgentRunSnapshots.RunSnapshot snapshot = fixture.service.snapshot(run);

        assertThat(snapshot.status()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
        assertThat(snapshot.userInputRequest().question()).isEqualTo("覆盖已有分镜吗？");
        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.events().get(0).eventId()).isEqualTo(10L);
        assertThat(snapshot.events().get(0).eventName()).isEqualTo("run_event");
        assertThat(snapshot.events().get(0).data()).containsEntry("type", "tool_call_started");
    }

    @Test
    void cancelMarksRunTerminalAndTerminalStateIsNotOverwritten() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(fixture.user, fixture.chapter, requestId, "生成分镜");

        when(fixture.runRepository.findForUpdate(1L, 7L, requestId))
                .thenReturn(Optional.of(run));
        when(fixture.runRepository.save(any(MangaAgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MangaAgentRun cancelled = fixture.service.cancel(requestId, 1L, 7L, "用户停止");
        fixture.service.markSucceeded(requestId, 1L, 7L, "不应覆盖");

        assertThat(cancelled.getStatus()).isEqualTo(MangaAgentRunStatus.CANCELLED);
        assertThat(cancelled.getErrorMessage()).isEqualTo("用户停止");
        assertThat(cancelled.getFinalReply()).isNull();
    }

    @Test
    void replayEventsUsesDurableCursorAndOwnerScopedRun() throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(fixture.user, fixture.chapter, requestId, "generate storyboard");
        MangaAgentRunEventRecord event = new MangaAgentRunEventRecord();
        event.setId(42L);
        event.setRun(run);
        event.setEventName("plan_created");
        event.setPayloadJson(fixture.objectMapper.writeValueAsString(Map.of("steps", 2)));
        event.setCreatedAt(OffsetDateTime.now());

        when(fixture.runRepository.findByUserIdAndChapterIdAndRequestId(1L, 7L, requestId))
                .thenReturn(Optional.of(run));
        when(fixture.eventRepository.findTop200ByRunIdAndIdGreaterThanOrderByIdAsc(99L, 10L))
                .thenReturn(List.of(event));

        MangaAgentRunSnapshots.RunEventReplayPage page =
                fixture.service.replayEvents(1L, 7L, requestId, 10L);

        assertThat(page.status()).isEqualTo(MangaAgentRunStatus.RUNNING);
        assertThat(page.lastEventId()).isEqualTo(42L);
        assertThat(page.events()).singleElement().satisfies(replayed -> {
            assertThat(replayed.eventId()).isEqualTo(42L);
            assertThat(replayed.eventName()).isEqualTo("plan_created");
        });
    }

    @Test
    void recordsSelectedSkillOnTheExactDirectorStep() {
        MangaAgentRunRepository runRepository = mock(MangaAgentRunRepository.class);
        MangaAgentRunEventRepository eventRepository = mock(MangaAgentRunEventRepository.class);
        MangaAgentRunStepRepository stepRepository = mock(MangaAgentRunStepRepository.class);
        MangaAgentRunService service = new MangaAgentRunService(
                runRepository, eventRepository, new ObjectMapper(), stepRepository);
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(user, chapter, requestId, "multi step");
        MangaAgentRunStep step = new MangaAgentRunStep();

        when(runRepository.findByUserIdAndChapterIdAndRequestId(1L, 7L, requestId))
                .thenReturn(Optional.of(run));
        when(stepRepository.findByRunIdAndPlanIdAndStepSequence(99L, "plan-a", 1))
                .thenReturn(Optional.of(step));

        service.recordStepSkillSelection(
                1L, 7L, requestId, "plan-a:1", selection("manga.review", "1.0.0"));

        assertThat(step.getSkillKey()).isEqualTo("manga.review");
        assertThat(step.getSkillVersion()).isEqualTo("1.0.0");
        verify(stepRepository).save(step);
    }

    @Test
    void cachedReplyDoesNotOverrideCancelledRun() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun cancelled = run(fixture.user, fixture.chapter, requestId, "generate storyboard");
        cancelled.setStatus(MangaAgentRunStatus.CANCELLED);
        com.artverse.domain.MangaAgentConversation conversation = new com.artverse.domain.MangaAgentConversation();
        conversation.setId(12L);

        when(fixture.runRepository.findForUpdate(12L, requestId)).thenReturn(Optional.of(cancelled));

        Optional<MangaAgentRun> reconciled = fixture.service.reconcileCachedReply(
                conversation, requestId, "late reply");

        assertThat(reconciled).containsSame(cancelled);
        assertThat(cancelled.getStatus()).isEqualTo(MangaAgentRunStatus.CANCELLED);
        verify(fixture.runRepository, never()).save(cancelled);
    }

    @Test
    void appendsToolAuditEventWithPersistentAuditFields() throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(fixture.user, fixture.chapter, requestId, "generate storyboard");
        AgentRunToolStatus.ToolEvent event = new AgentRunToolStatus.ToolEvent(
                requestId, "plan-a:0", "commit_storyboard", "SUCCEEDED", true,
                25L, "hash-1", null, "audit-1", Map.of("saved", true), OffsetDateTime.now());

        when(fixture.runRepository.findByUserIdAndChapterIdAndRequestId(1L, 7L, requestId))
                .thenReturn(Optional.of(run));
        when(fixture.runRepository.getReferenceById(99L)).thenReturn(run);

        fixture.service.appendToolAuditEvent(1L, 7L, requestId, event);

        verify(fixture.eventRepository).save(any(MangaAgentRunEventRecord.class));
    }

    @Test
    void mergeRunAttributesKeepsStrongerToolContractStatus() throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(fixture.user, fixture.chapter, requestId, "generate storyboard");
        com.artverse.domain.MangaAgentConversation conversation = new com.artverse.domain.MangaAgentConversation();
        conversation.setId(12L);
        run.setConversation(conversation);
        run.setRunAttributesJson(fixture.objectMapper.writeValueAsString(Map.of("tool_contract_status", "FAILED")));

        when(fixture.runRepository.findForUpdate(12L, requestId)).thenReturn(Optional.of(run));
        when(fixture.runRepository.save(any(MangaAgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.mergeRunAttributes(conversation, requestId, Map.of("tool_contract_status", "DEGRADED"));

        assertThat(run.getRunAttributesJson()).contains("FAILED");
    }

    @Test
    void interruptStalledRunningRunsUsesLastRealProgressInsteadOfUpdatedAt() {
        Fixture fixture = fixture();
        MangaAgentRun run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "生成分镜");
        OffsetDateTime staleBefore = OffsetDateTime.now().minusMinutes(10);

        run.setUpdatedAt(OffsetDateTime.now());
        run.setLastProgressAt(staleBefore.minusSeconds(1));
        when(fixture.runRepository.findStalledCandidateIds(eq(MangaAgentRunStatus.RUNNING), eq(staleBefore)))
                .thenReturn(List.of(42L));
        when(fixture.runRepository.findForUpdateById(42L)).thenReturn(java.util.Optional.of(run));
        when(fixture.runRepository.save(any(MangaAgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int interrupted = fixture.service.interruptStalledRunningRuns(staleBefore, staleBefore.minusMinutes(5));

        assertThat(interrupted).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.INTERRUPTED);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getErrorMessage()).contains("interrupted");
    }

    @Test
    void waitingInputReturnsNullForCorruptedJsonInsteadOfThrowing() {
        Fixture fixture = fixture();
        MangaAgentRun run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "生成分镜");
        run.setUserInputRequestJson("{not valid json!@#}");

        AgentUserInputRequest result = fixture.service.waitingInput(run);

        assertThat(result).isNull();
    }

    @Test
    void routingDecisionRestoresPersistedDecision() throws Exception {
        Fixture fixture = fixture();
        MangaAgentRun run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "generate storyboard");
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("multi_step"), false, false, "test",
                List.of(MangaWorkflowRoute.CREATIVE, MangaWorkflowRoute.REVIEW),
                RoutingDecision.CURRENT_VERSION
        );
        run.setRoutingDecisionJson(fixture.objectMapper.writeValueAsString(decision));

        RoutingDecision restored = fixture.service.routingDecision(run);

        assertThat(restored).isEqualTo(decision);
    }

    @Test
    void routingDecisionReturnsNullForCorruptedJsonInsteadOfThrowing() {
        Fixture fixture = fixture();
        MangaAgentRun run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "generate storyboard");
        run.setRoutingDecisionJson("{not valid json!@#}");

        RoutingDecision result = fixture.service.routingDecision(run);

        assertThat(result).isNull();
    }

    @Test
    void recordKnowledgeSnapshotUpdatesPersistedContextSummaryHash() throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(fixture.user, fixture.chapter, requestId, "generate storyboard");
        run.setContextSnapshotJson(fixture.objectMapper.writeValueAsString(new MangaAgentRunSnapshots.RunContextSnapshot(
                3L, 7L, "Story", "Chapter 1", 4, 1, "ctx-hash", null,
                List.of("chapter_source_excerpt"), List.of("knowledge_recall_missing"))));
        KnowledgeService.RecallPreview preview = new KnowledgeService.RecallPreview(
                List.of(new KnowledgeService.RecallItem(11L, 2, "CHARACTER_CARD", "Hero", "content", 0.8)),
                "context",
                "knowledge-hash",
                19L,
                25L
        );

        when(fixture.runRepository.findByUserIdAndChapterIdAndRequestId(1L, 7L, requestId))
                .thenReturn(Optional.of(run));
        when(fixture.runRepository.save(any(MangaAgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.recordKnowledgeSnapshot(1L, 7L, requestId, preview);

        MangaAgentRunSnapshots.RunContextSnapshot updated = fixture.objectMapper.readValue(
                run.getContextSnapshotJson(), MangaAgentRunSnapshots.RunContextSnapshot.class);
        assertThat(run.getKnowledgeSnapshotId()).isEqualTo(25L);
        assertThat(updated.knowledgeRecallHash()).isEqualTo("knowledge-hash");
        assertThat(updated.warnings()).doesNotContain("knowledge_recall_missing");
    }

    private Fixture fixture() {
        MangaAgentRunRepository runRepository = mock(MangaAgentRunRepository.class);
        MangaAgentRunEventRepository eventRepository = mock(MangaAgentRunEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MangaAgentRunService service = new MangaAgentRunService(runRepository, eventRepository, objectMapper);
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        return new Fixture(service, runRepository, eventRepository, objectMapper, user, chapter);
    }

    private MangaAgentRun run(User user, Chapter chapter, UUID requestId, String input) {
        MangaAgentRun run = new MangaAgentRun();
        run.setId(99L);
        run.setUser(user);
        run.setStory(chapter.getStory());
        run.setChapter(chapter);
        run.setRequestId(requestId);
        run.setInputMessage(input);
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setCreatedAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        run.setLastProgressAt(OffsetDateTime.now());
        run.setCurrentPhase("MODEL");
        return run;
    }

    private BusinessSkillSelection selection(String skillKey, String version) {
        return new BusinessSkillSelection(List.of(new ArtVerseSkillRegistry.SkillManifest(
                skillKey,
                version,
                "checksum",
                "PUBLISHED",
                "manga",
                skillKey,
                "description",
                List.of(),
                List.of(),
                "content",
                version,
                List.of(),
                Map.of(),
                null,
                "NONE",
                false,
                Map.of()
        )));
    }

    private record Fixture(MangaAgentRunService service,
                           MangaAgentRunRepository runRepository,
                           MangaAgentRunEventRepository eventRepository,
                           ObjectMapper objectMapper,
                           User user,
                           Chapter chapter) {
    }
}
