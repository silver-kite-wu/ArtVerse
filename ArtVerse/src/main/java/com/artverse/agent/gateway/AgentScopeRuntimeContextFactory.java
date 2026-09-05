package com.artverse.agent.gateway;


import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentRunRequest;
import com.artverse.application.ApiKeyService;
import com.artverse.application.UserProviderConfig;

import com.artverse.common.BusinessException;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentScopeRuntimeContextFactory {


    public RuntimeContext create(AgentRunRequest request) {
        return create(request, "");
    }

    public RuntimeContext create(AgentRunRequest request, String systemContent) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(createSessionId(request))
                .userId(request.userId());
        if (systemContent != null && !systemContent.isBlank()) {
            builder.put(AgentScopeSystemPromptContext.class,
                    new AgentScopeSystemPromptContext(systemContent));
        }
        if (request.taskType() != null && request.taskType().isMangaExecutionTask()) {
            builder.put(MangaAgentRuntimeContext.class, new MangaAgentRuntimeContext(
                    parseUserIdForTool(request.userId()),
                    request.storyId(),
                    request.chapterId(),
                    request.conversationId(),
                    request.requestId(),
                    workflowConfig(request),
                    request.taskType(),
                    String.valueOf(request.variables().getOrDefault(
                            "step_id", request.taskType().sessionSuffix())),
                    longValue(request.variables().get("fencing_token"))
            ));
        }
        return builder.build();
    }

    /**
     * The workflow provider configuration always comes from the user's saved
     * settings. A blank config is used when the request carries none, so the
     * tool layer can surface a clear "configure it in Settings" error instead
     * of silently falling back to operator credentials.
     */
    private static UserProviderConfig workflowConfig(AgentRunRequest request) {
        Object value = request.variables().get("workflow_config");
        if (value instanceof UserProviderConfig config) {
            return config;
        }
        return new UserProviderConfig(ApiKeyService.SLOT_WORKFLOW, "", "", "", "", "");
    }

    static Long parseUserIdForTool(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (Exception e) {
            throw new BusinessException(400, "Invalid agent user id");
        }
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || String.valueOf(value).isBlank()) return 0L;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new BusinessException(400, "Invalid agent fencing token");
        }
    }

    public static String createSessionId(AgentRunRequest request) {
        return String.join("-",
                "u", safeSegment(request.userId()),
                "story", safeSegment(request.storyId()),
                "chapter", safeSegment(request.chapterId()),
                "conv", safeSegment(request.conversationId()),
                safeSegment(request.taskType() == null ? "unknown" : request.taskType().sessionSuffix())
        );
    }

    public static String safeSegment(Object value) {
        if (value == null) {
            return "none";
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        if (normalized.isBlank()) {
            return "none";
        }
        String safe = normalized.replaceAll("[^a-z0-9._-]", "-")
                .replace("..", "-");
        return safe.isBlank() ? "none" : safe;
    }

}
