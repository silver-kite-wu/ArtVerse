package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.domain.*;
import com.artverse.guard.AgentConcurrencyGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryChatRequestPreparationTest {
    private final StoryChatPreparationService preparation = mock(StoryChatPreparationService.class);
    private final MangaAgentRunService runs = mock(MangaAgentRunService.class);
    private final MangaAgentRunEventPublisher publisher = mock(MangaAgentRunEventPublisher.class);
    private final MangaAgentRunEventPublisher.RunEventSink sink = mock(MangaAgentRunEventPublisher.RunEventSink.class);
    private final AgentRunToolStatus tools = mock(AgentRunToolStatus.class);
    private final AgentScopeHarnessAgentGateway gateway = mock(AgentScopeHarnessAgentGateway.class);
    private final StoryChatRuntimeContextAssembler assembler = mock(StoryChatRuntimeContextAssembler.class);
    private final AgentModelSpecFactory models = mock(AgentModelSpecFactory.class);
    private final ArtVerseSkillRegistry skills = mock(ArtVerseSkillRegistry.class);
    private final NovelContentArtifactService artifacts = mock(NovelContentArtifactService.class);
    private final StoryChatPreparationService.Scope scope = new StoryChatPreparationService.Scope(5L, UUID.randomUUID(), 3L, 7L, 9L);
    private final UUID requestId = UUID.randomUUID();
    private final MangaAgentRun run = new MangaAgentRun();
    private final AgentRunToolStatus.RunScope toolScope = mock(AgentRunToolStatus.RunScope.class);
    private final StoryChatContextSnapshot snapshot = new StoryChatContextSnapshot(3L, 7L, 9L,
            scope.conversationId(), requestId, 1, 12L, "章节正文", List.of());
    private final List<AgentMessage> input = List.of(new AgentMessage("system", "章节正文"), new AgentMessage("user", "审核本章创作质量"));
    private final StoryChatAgentService service = new StoryChatAgentService(null, runs, publisher, tools,
            new StoryChatRouter(), assembler, gateway, models, skills, new NovelBusinessSkillRouter(), null,
            mock(AgentConcurrencyGate.class), artifacts, gateway, new ObjectMapper(), null, preparation, mock(NovelDraftReviewService.class));

    @BeforeEach
    void setup() {
        run.setOwnerInstanceId("original-worker");
        run.setFencingToken(1L);
        when(publisher.newPersistentSink()).thenReturn(sink);
        when(runs.startOrReuse(any(), eq(requestId), anyString(), eq(AgentRunType.STORY_CHAT), any(), eq("REVIEW"))).thenReturn(run);
        when(tools.start(eq(3L), eq(9L), eq(requestId), any())).thenReturn(toolScope);
        when(toolScope.state()).thenReturn(mock(AgentRunToolStatus.RunState.class));
        when(preparation.snapshot(scope, requestId, "审核本章创作质量")).thenReturn(snapshot);
        when(assembler.assemble(snapshot, "审核本章创作质量", StoryChatRoute.REVIEW)).thenReturn(input);
        when(skills.selectionForNovelMode(NovelBusinessSkillMode.REVIEW)).thenReturn(BusinessSkillSelection.empty());
        when(models.fromProviderConfig(any())).thenReturn(new AgentModelSpec("test", "https://example.test", "model", "hash"));
        when(gateway.streamEvents(any())).thenReturn(Flux.empty());
    }

    @Test
    void executionScopePreservesStoryVersionForHibernateDetachedReferences() {
        var persistedScope = new StoryChatPreparationService.Scope(5L, UUID.randomUUID(), 3L, 7L, 17L, 9L);
        var conversation = persistedScope.reference();
        assertThat(conversation.getStory().getVersion()).isEqualTo(17L);
    }

    @Test
    void retryDoesNotResubmitAnAlreadyConsumedExternalReviewResult() {
        var snapshot = mock(MangaAgentRunSnapshots.RunSnapshot.class);
        var external = new MangaAgentRunSnapshots.RunEventSnapshot(1L, "run_event", java.util.Map.of(
                "type", "external_exec_required", "data", java.util.Map.of("toolCalls", List.of(
                        java.util.Map.of("id", "original-ask", "name", "ask_user")))), null);
        when(snapshot.events()).thenReturn(List.of(external));
        assertThat(service.reviewToolResults(snapshot, java.util.Map.of("status", "COMMITTED")))
                .extracting(io.agentscope.core.message.ToolResultBlock::getId).containsExactly("original-ask");
        when(snapshot.events()).thenReturn(List.of(external, new MangaAgentRunSnapshots.RunEventSnapshot(
                2L, "run_event", java.util.Map.of("type", "review_resumed"), null)));
        assertThat(service.reviewToolResults(snapshot, java.util.Map.of("status", "COMMITTED"))).isEmpty();
    }

    @Test
    void resumedExternalReviewResultContainsExactAuthorTextAndOriginalToolCallId() throws Exception {
        var pending = io.agentscope.core.message.ToolResultBlock.builder().id("original-ask-user")
                .name("ask_user").output(List.of(io.agentscope.core.message.TextBlock.builder().text("old draft").build())).build();
        var results = service.reviewedToolResults(List.of(pending), java.util.Map.of("status", "COMMITTED", "word_count", 12), "作者手动改过的最终稿");
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getId()).isEqualTo("original-ask-user");
        assertThat(results.getFirst().getState()).isEqualTo(io.agentscope.core.message.ToolResultState.SUCCESS);
        String text = ((io.agentscope.core.message.TextBlock) results.getFirst().getOutput().getFirst()).getText();
        assertThat(new ObjectMapper().readTree(text).path("reviewed_content").asText()).isEqualTo("作者手动改过的最终稿");
        assertThat(text).doesNotContain("old draft");
    }

    @Test
    void noModelCallWhenPreparationFailsAndErrorIsReadable() {
        when(preparation.snapshot(any(), any(), anyString())).thenThrow(new IllegalStateException("detached chapter"));
        service.runBackground(scope.reference(), "审核本章创作质量", requestId, config());
        verifyNoInteractions(gateway);
        verify(sink).sendError(run, requestId, "章节内容准备失败，请重试。");
        verify(sink).complete();
    }

    @Test
    void buildsCompleteRequestBeforeInvokingGateway() {
        service.runBackground(scope.reference(), "审核本章创作质量", requestId, config());
        var order = inOrder(preparation, assembler, sink, gateway);
        order.verify(preparation).snapshot(scope, requestId, "审核本章创作质量");
        order.verify(sink).sendPhase(run, requestId, "BUILDING_REQUEST", "正在整理创作上下文");
        order.verify(assembler).assemble(snapshot, "审核本章创作质量", StoryChatRoute.REVIEW);
        order.verify(sink).sendPhase(run, requestId, "WAITING_MODEL", "正在分析本章");
        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        order.verify(gateway).streamEvents(request.capture());
        assertThat(request.getValue().messages()).isEqualTo(input);
        assertThat(request.getValue().chapterId()).isEqualTo(9L);
        assertThat(request.getValue().conversationId()).isEqualTo(scope.conversationId());
        assertThat(request.getValue().variables()).containsEntry("chapter_version", 12L);
        assertThat(request.getValue().businessSkillSelection()).isNotNull();
    }

    @Test
    void cancellationDuringPreparationPreventsModelCall() {
        when(preparation.snapshot(any(), any(), anyString())).thenAnswer(invocation -> {
            when(runs.isTerminal(any(MangaAgentConversation.class), eq(requestId))).thenReturn(true);
            return snapshot;
        });
        service.runBackground(scope.reference(), "审核本章创作质量", requestId, config());
        verifyNoInteractions(gateway);
        verify(runs, never()).markFailed(any(MangaAgentConversation.class), any(), anyString());
        verify(sink).sendError(run, requestId, "任务已停止。");
    }

    private UserProviderConfig config() {
        return new UserProviderConfig("llm", "test", "fixture", "fixture-key", "https://example.test", "model");
    }

    @Test
    void polishUsesDraftToolsAndPersistsWaitingWhenModelOmitsAskUser() {
        String message = "将本章节进行优化一下吧，要求人物角色立体，语言逻辑清晰合理";
        when(runs.startOrReuse(any(), eq(requestId), eq(message), eq(AgentRunType.STORY_CHAT), any(), eq("POLISH"))).thenReturn(run);
        when(preparation.snapshot(scope, requestId, message)).thenReturn(snapshot);
        when(assembler.assemble(snapshot, message, StoryChatRoute.POLISH)).thenReturn(List.of(new AgentMessage("user", message)));
        when(skills.selectionForNovelMode(any())).thenReturn(BusinessSkillSelection.empty());
        UUID draftId = UUID.randomUUID();
        when(artifacts.list(3L, 9L, requestId)).thenReturn(List.of(new NovelContentArtifactService.ArtifactView(
                draftId, requestId, "NOVEL_CONTENT_DRAFT", "VALIDATED", "1", java.util.Map.of(), java.util.Map.of(), "hash")));
        service.runBackground(scope.reference(), message, requestId, config());
        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(gateway).streamEvents(request.capture());
        assertThat(request.getValue().taskType()).isEqualTo(AgentTaskType.STORY_CHAT_WRITE);
        verify(skills).selectionForNovelMode(NovelBusinessSkillMode.PROSE_POLISH);
        verify(runs).markWaiting(any(MangaAgentConversation.class), eq(requestId), argThat(input -> input.reason().contains(draftId.toString())),
                eq(new com.artverse.application.workflow.MangaWorkflowExecutionContext.ExecutionClaim("original-worker", 1L)));
        verify(runs, never()).markSucceeded(any(MangaAgentConversation.class), any(), anyString());
    }

    @Test
    void polishWithoutPersistedDraftDoesNotReportSuccess() {
        String message = "润色本章";
        when(runs.startOrReuse(any(), eq(requestId), eq(message), eq(AgentRunType.STORY_CHAT), any(), eq("POLISH"))).thenReturn(run);
        when(preparation.snapshot(scope, requestId, message)).thenReturn(snapshot);
        when(assembler.assemble(snapshot, message, StoryChatRoute.POLISH)).thenReturn(List.of(new AgentMessage("user", message)));
        when(skills.selectionForNovelMode(any())).thenReturn(BusinessSkillSelection.empty());
        when(artifacts.list(3L, 9L, requestId)).thenReturn(List.of());
        service.runBackground(scope.reference(), message, requestId, config());
        verify(runs, never()).markSucceeded(any(MangaAgentConversation.class), any(), anyString());
        verify(sink).sendError(eq(run), eq(requestId), contains("未生成可审阅的修改草稿"));
    }

    @ParameterizedTest
    @EnumSource(value = StoryChatRoute.class, names = {"READ", "POLISH"})
    void answerUsesOriginalRouteAndStepRatherThanReclassifyingTheAnswer(StoryChatRoute route) throws Exception {
        pending(route, Map.of());
        var turn = mock(com.artverse.agent.gateway.AgentSessionTurns.Lease.class);
        when(gateway.acquireSessionTurn(any())).thenReturn(turn);
        when(runs.acceptAnswer(any(), eq(requestId), eq("original-question"), eq(1L), anyString(), isNull()))
                .thenAnswer(invocation -> new MangaAgentRunService.AcceptedAnswer(run, "original-question", 1L,
                        invocation.getArgument(4), "hash", UUID.randomUUID(), "{}"));
        when(runs.claimAnswerDelivery(any(), eq(requestId), any()))
                .thenAnswer(invocation -> {
                    MangaAgentRunService.AcceptedAnswer accepted = invocation.getArgument(2);
                    return new MangaAgentRunService.AnswerContinuation(run, accepted.callId(), accepted.answer(), true,
                            new AgentAnswerDelivery(accepted.actionId(), accepted.callId(), 1L,
                                    accepted.answerHash(), accepted.canonicalResult(), run.getFencingToken()));
                });
        when(skills.selectionForKeys(List.of())).thenReturn(BusinessSkillSelection.empty());
        when(preparation.snapshot(eq(scope), eq(requestId), anyString())).thenReturn(snapshot);
        when(assembler.assemble(eq(snapshot), anyString(), eq(route))).thenReturn(input);
        when(gateway.streamEvents(any(), anyList(), eq(turn))).thenReturn(Flux.empty());

        service.resumeBackground(scope.reference(), requestId, "answer", null, "请保存并续写", "original-question", 1L, config());

        var request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(gateway).streamEvents(request.capture(), argThat(results -> results.size() == 1
                && results.getFirst().getId().equals("original-question")), eq(turn));
        assertThat(request.getValue().taskType()).isEqualTo(route == StoryChatRoute.POLISH
                ? AgentTaskType.STORY_CHAT_WRITE : AgentTaskType.STORY_CHAT_READ);
        assertThat(request.getValue().variables()).containsEntry("step_id", "story-chat:" + route.name());
        assertThat(request.getValue().conversationId()).isEqualTo(scope.conversationId());
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any(), any(), anyString());
        verify(skills, never()).selectionForNovelMode(any());
    }

    @Test
    void reviewedContinuationUsesItsAlreadyClaimedRunAndHasNoWritingSkill() throws Exception {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> receipt = Map.of("artifact_id", artifactId.toString(), "status", "COMMITTED", "content_hash", "final-hash");
        pending(StoryChatRoute.POLISH, receipt);
        when(runs.claimReviewedContinuation(any(), eq(requestId), eq(artifactId))).thenReturn(run);
        when(preparation.snapshot(eq(scope), eq(requestId), anyString())).thenReturn(snapshot);
        when(assembler.assemble(eq(snapshot), anyString(), eq(StoryChatRoute.POLISH))).thenReturn(input);
        when(gateway.streamEvents(any(), anyList())).thenReturn(Flux.empty());
        when(artifacts.reviewedContent(3L, 9L, requestId, artifactId, "final-hash")).thenReturn("原审阅最终稿");

        service.resumeBackground(scope.reference(), requestId, "reviewed", artifactId, null, config());

        var request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(gateway).streamEvents(request.capture(), anyList());
        assertThat(request.getValue().taskType()).isEqualTo(AgentTaskType.STORY_CHAT_WRITE);
        assertThat(request.getValue().variables()).containsEntry("review_completed", true)
                .containsEntry("step_id", "story-chat:POLISH");
        assertThat(request.getValue().businessSkillSelection().isEmpty()).isTrue();
        verify(runs, never()).startOrReuse(any(), any(), anyString(), any(), any(), anyString());
        verify(runs, times(1)).claimReviewedContinuation(any(), eq(requestId), eq(artifactId));
        verify(skills, never()).selectionForNovelMode(any());
        verify(preparation).completeAssistantResult(eq(scope), anyString(), eq(requestId), anyString(),
                eq(new com.artverse.application.workflow.MangaWorkflowExecutionContext.ExecutionClaim("original-worker", 1L)), eq(artifactId));
    }

    private void pending(StoryChatRoute route, Map<String, Object> receipt) throws Exception {
        run.setRunType(AgentRunType.STORY_CHAT); run.setRouteKey(route.name());
        AgentTaskType type = route == StoryChatRoute.POLISH || route == StoryChatRoute.WRITE
                ? AgentTaskType.STORY_CHAT_WRITE : AgentTaskType.STORY_CHAT_READ;
        run.setRunAttributesJson(new ObjectMapper().writeValueAsString(Map.of("story_chat_execution", Map.of(
                "version", 1, "profile", "local-tools-v1", "route", route.name(), "task_type", type.name(),
                "step_id", "story-chat:" + route.name(), "skills", List.of()))));
        var pending = mock(MangaAgentRunSnapshots.RunSnapshot.class);
        when(pending.status()).thenReturn(MangaAgentRunStatus.WAITING_USER);
        when(pending.runAttributes()).thenReturn(receipt.isEmpty() ? Map.of() : Map.of("review_result", receipt));
        when(pending.events()).thenReturn(List.of(new MangaAgentRunSnapshots.RunEventSnapshot(1L, "run_event", Map.of(
                "type", "external_exec_required", "data", Map.of("toolCalls", List.of(
                        Map.of("id", "original-question", "name", "ask_user")))), null)));
        when(runs.findRun(any(MangaAgentConversation.class), eq(requestId))).thenReturn(Optional.of(run));
        when(runs.snapshot(run)).thenReturn(pending);
    }
}
