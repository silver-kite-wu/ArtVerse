package com.artverse.agents;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeHitlSuspendHookTest {

    @Test
    void stopsAgentWhenAskUserToolIsSuspended() {
        ToolUseBlock toolUse = new ToolUseBlock("call-1", "ask_user", Map.of());
        PostActingEvent event = new PostActingEvent(mock(Agent.class), null, toolUse, ToolResultBlock.suspended(toolUse));

        new AgentScopeHitlSuspendHook().onEvent(event).block();

        assertThat(event.isStopRequested()).isTrue();
    }

    @Test
    void keepsAgentRunningForNonHitlToolResult() {
        ToolUseBlock toolUse = new ToolUseBlock("call-1", "get_chapter_context", Map.of());
        PostActingEvent event = new PostActingEvent(mock(Agent.class), null, toolUse, ToolResultBlock.suspended(toolUse));

        new AgentScopeHitlSuspendHook().onEvent(event).block();

        assertThat(event.isStopRequested()).isFalse();
    }
}
