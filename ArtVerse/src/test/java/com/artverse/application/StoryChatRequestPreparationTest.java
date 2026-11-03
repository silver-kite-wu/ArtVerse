package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.domain.*;
import com.artverse.guard.AgentConcurrencyGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
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
            mock(AgentConcurrencyGate.class), artifacts, gateway, new ObjectMapper(), null, preparation);

    @BeforeEach
    void setup() {
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
        verify(runs).markWaiting(any(MangaAgentConversation.class), eq(requestId), argThat(input -> input.reason().contains(draftId.toString())));
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
}
