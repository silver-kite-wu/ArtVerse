package com.artverse.application;

import com.artverse.agents.AgentRunContext;
import io.agentscope.core.agent.RuntimeContext;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.ToolSuspendException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class AgentToolAuditService {

    private final AgentRunToolStatus agentRunToolStatus;

    public Map<String, Object> around(String toolName, Long userId, Long chapterId, RuntimeContext runtimeContext,
                                      Callable<Map<String, Object>> action) {
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = action.call();
            long durationMs = System.currentTimeMillis() - startedAt;
            recordSucceeded(toolName, userId, chapterId, runtimeContext, durationMs, result);
            log.info("Agent tool succeeded: {}", event(toolName, userId, chapterId, "succeeded",
                    durationMs, null));
            return result;
        } catch (AgentUserInputRequiredException e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.info("Agent tool waiting for user input: {}", event(toolName, userId, chapterId, "waiting_user",
                    durationMs, null));
            throw e;
        } catch (ToolSuspendException e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.info("Agent tool suspended: {}", event(toolName, userId, chapterId, "waiting_user",
                    durationMs, e.getReason()));
            throw e;
        } catch (RuntimeException e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            recordFailed(toolName, userId, chapterId, runtimeContext, durationMs, e.getMessage());
            log.warn("Agent tool failed: {}", event(toolName, userId, chapterId, "failed",
                    durationMs, e.getMessage()));
            throw e;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            recordFailed(toolName, userId, chapterId, runtimeContext, durationMs, e.getMessage());
            log.warn("Agent tool failed: {}", event(toolName, userId, chapterId, "failed",
                    durationMs, e.getMessage()));
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> around(String toolName, Long userId, Long chapterId, Callable<Map<String, Object>> action) {
        return around(toolName, userId, chapterId, null, action);
    }

    private void recordSucceeded(String toolName, Long userId, Long chapterId, RuntimeContext runtimeContext,
                                 long durationMs, Map<String, Object> result) {
        UUID requestId = requestId(runtimeContext);
        if (requestId != null) {
            agentRunToolStatus.recordSucceeded(toolName, userId, chapterId, requestId, durationMs, result);
            return;
        }
        agentRunToolStatus.recordSucceeded(toolName, userId, chapterId, durationMs, result);
    }

    private void recordFailed(String toolName, Long userId, Long chapterId, RuntimeContext runtimeContext,
                              long durationMs, String error) {
        UUID requestId = requestId(runtimeContext);
        if (requestId != null) {
            agentRunToolStatus.recordFailed(toolName, userId, chapterId, requestId, durationMs, error);
            return;
        }
        agentRunToolStatus.recordFailed(toolName, userId, chapterId, durationMs, error);
    }

    private UUID requestId(RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return null;
        }
        AgentRunContext context = runtimeContext.get(AgentRunContext.class);
        return context == null ? null : context.requestId();
    }

    private Map<String, Object> event(String toolName, Long userId, Long chapterId, String status,
                                      long durationMs, String error) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tool", toolName);
        event.put("userId", userId);
        event.put("chapterId", chapterId);
        event.put("status", status);
        event.put("durationMs", durationMs);
        if (error != null && !error.isBlank()) {
            event.put("error", truncate(error, 160));
        }
        return event;
    }

    private String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }
}
