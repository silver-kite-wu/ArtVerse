package com.artverse.agent;

import com.artverse.application.AgentBudgetService;
import com.artverse.common.BusinessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable invocation evidence injected after native argument validation, never from model JSON. */
public record AgentToolAttempt(String callId, String toolName, String inputHash, Long actor,
                               UUID requestId, Long storyId, Long chapterId, String taskType, String stepId) {
    public static AgentToolAttempt capture(ToolUseBlock call, RuntimeContext runtime) {
        var scope = runtime == null ? null : runtime.get(MangaAgentRuntimeContext.class);
        return new AgentToolAttempt(call.getId(), call.getName(),
                AgentBudgetService.hash(Map.of("name", call.getName(), "input", call.getInput())),
                scope == null ? null : scope.userId(), scope == null ? null : scope.requestId(),
                scope == null ? null : scope.storyId(), scope == null ? null : scope.chapterId(),
                scope == null || scope.taskType() == null ? null : scope.taskType().name(),
                scope == null ? null : scope.stepId());
    }

    public static AgentToolAttempt require(RuntimeContext runtime, String tool) {
        var attempt = runtime == null ? null : runtime.get(AgentToolAttempt.class);
        if (attempt == null || !Objects.equals(tool, attempt.toolName())) throw denied();
        return attempt;
    }

    public void requireScope(MangaAgentRuntimeContext context) {
        if (context == null || callId == null || callId.isBlank() || inputHash == null || inputHash.length() != 64
                || !Objects.equals(actor, context.userId()) || actor == null
                || !Objects.equals(requestId, context.requestId()) || requestId == null
                || !Objects.equals(storyId, context.storyId()) || !Objects.equals(chapterId, context.chapterId())
                || context.taskType() == null || !Objects.equals(taskType, context.taskType().name())
                || stepId == null || stepId.isBlank() || !Objects.equals(stepId, context.stepId())) throw denied();
    }

    private static BusinessException denied() {
        return new BusinessException(403, "TOOL_EFFECT_DENIED: native call identity is missing or changed");
    }
}
