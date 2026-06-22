package com.artverse.agents;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Mono;

public class AgentScopeHitlSuspendHook implements Hook {

    static final String ASK_USER_TOOL = "ask_user";

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent acting && isSuspendedAskUser(acting)) {
            acting.stopAgent();
        }
        return Mono.just(event);
    }

    static boolean isSuspendedAskUser(PostActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        ToolResultBlock toolResult = event.getToolResult();
        return toolUse != null
                && ASK_USER_TOOL.equals(toolUse.getName())
                && toolResult != null
                && toolResult.isSuspended();
    }
}
