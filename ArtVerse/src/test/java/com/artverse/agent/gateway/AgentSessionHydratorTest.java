package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSessionHydratorTest {

    @Test
    void exactSuccessfulAnswerIsRequiredEvenWhenPendingDisappears() {
        var store = new io.agentscope.core.state.InMemoryAgentStateStore();
        var request = request(List.of());
        var delivery = new com.artverse.agent.AgentAnswerDelivery(UUID.randomUUID(), "question", 1L,
                "answer-hash", "{\"status\":\"ANSWERED\",\"answer\":\"accepted\"}", 1L);
        var call = new io.agentscope.core.message.ToolUseBlock("question", "ask_user", Map.of(), "{}", Map.of());
        var assistant = io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .name("artverse-" + request.taskType().sessionSuffix()).content(call).build();
        var hydrator = new AgentSessionHydrator(store);
        String session = AgentScopeRuntimeContextFactory.createSessionId(request);
        store.save(request.userId(), session, "agent_state", io.agentscope.core.state.AgentState.builder().context(List.of(assistant)).build());
        assertThat(hydrator.answerState(request, delivery).pending()).isTrue();
        for (var result : List.of(
                io.agentscope.core.message.ToolResultBlock.builder().id("question").name("ask_user")
                        .state(io.agentscope.core.message.ToolResultState.ERROR)
                        .output(delivery.toolResult().getOutput()).build(),
                io.agentscope.core.message.ToolResultBlock.builder().id("question").name("ask_user")
                        .state(io.agentscope.core.message.ToolResultState.SUCCESS)
                        .output(List.of(io.agentscope.core.message.TextBlock.builder().text("different answer").build())).build())) {
            store.save(request.userId(), session, "agent_state", io.agentscope.core.state.AgentState.builder().context(List.of(
                    assistant, io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.TOOL).content(result).build())).build());
            assertThatThrownBy(() -> hydrator.answerState(request, delivery)).hasMessageContaining("不一致或证据缺失");
        }
        store.save(request.userId(), session, "agent_state", io.agentscope.core.state.AgentState.builder().context(List.of(
                assistant, io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.TOOL)
                        .content(delivery.toolResult()).build())).build());
        assertThat(hydrator.answerState(request, delivery).pending()).isFalse();
        assertThat(hydrator.answerState(request, delivery).version()).isPositive();
    }

    @Test
    void externalResumeCannotHydrateMissingStateAsAFreshWritingSession() {
        var hydrator = new AgentSessionHydrator(new io.agentscope.core.state.InMemoryAgentStateStore());
        var result = io.agentscope.core.message.ToolResultBlock.builder().id("original-question").name("ask_user").build();
        assertThatThrownBy(() -> hydrator.requirePendingResults(request(List.of()), List.of(result)))
                .isInstanceOf(com.artverse.common.BusinessException.class).hasMessageContaining("状态缺失");
    }

    @Test
    void externalResumeChecksTheActualPendingCallRatherThanOnlySessionExistence() {
        var store = new io.agentscope.core.state.InMemoryAgentStateStore();
        var request = request(List.of());
        var call = new io.agentscope.core.message.ToolUseBlock("question-2", "ask_user", Map.of(), "{}", Map.of());
        store.save(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request), "agent_state",
                io.agentscope.core.state.AgentState.builder().context(List.of(io.agentscope.core.message.Msg.builder()
                        .name("artverse-" + request.taskType().sessionSuffix()).role(io.agentscope.core.message.MsgRole.ASSISTANT)
                        .content(call).build())).build());
        var hydrator = new AgentSessionHydrator(store);
        assertThatThrownBy(() -> hydrator.requireNoPending(request))
                .hasMessageContaining("仍待结果");
        var stale = io.agentscope.core.message.ToolResultBlock.builder().id("question-1").name("ask_user").build();
        assertThatThrownBy(() -> hydrator.requirePendingResults(request, List.of(stale)))
                .isInstanceOf(com.artverse.common.BusinessException.class);
        var current = io.agentscope.core.message.ToolResultBlock.builder().id("question-2").name("ask_user").build();
        hydrator.requirePendingResults(request, List.of(current));
        assertThatThrownBy(() -> hydrator.requirePendingResults(request, List.of(current, current)))
                .isInstanceOf(com.artverse.common.BusinessException.class);
    }

    @Test
    void healthyStateSendsOnlySystemDataAndCurrentUserMessage() {
        AgentStateStore store = mock(AgentStateStore.class);
        when(store.exists(anyString(), anyString())).thenReturn(true);
        AgentSessionHydrator hydrator = new AgentSessionHydrator(store);
        AgentRunRequest request = request(List.of(
                new AgentMessage("system", "chapter rules"),
                new AgentMessage("user", "old question"),
                new AgentMessage("assistant", "old answer"),
                new AgentMessage("system", "rag data"),
                new AgentMessage("user", "current question")
        ));

        assertThat(hydrator.messagesFor(request))
                .extracting(AgentMessage::content)
                .containsExactly("chapter rules", "rag data", "current question");
    }

    @Test
    void missingStateKeepsDatabaseRecoveryHistory() {
        AgentStateStore store = mock(AgentStateStore.class);
        when(store.exists(anyString(), anyString())).thenReturn(false);
        AgentSessionHydrator hydrator = new AgentSessionHydrator(store);
        List<AgentMessage> history = List.of(
                new AgentMessage("system", "rules"),
                new AgentMessage("user", "old"),
                new AgentMessage("assistant", "answer"),
                new AgentMessage("user", "current")
        );

        assertThat(hydrator.messagesFor(request(history))).containsExactlyElementsOf(history);
    }

    private AgentRunRequest request(List<AgentMessage> messages) {
        return new AgentRunRequest("1", 2L, 3L, AgentTaskType.MANGA_CONVERSATION,
                messages, Map.of(), null, "key", UUID.randomUUID(), UUID.randomUUID());
    }
}
