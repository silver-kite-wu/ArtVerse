package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentToolAttempt;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.ToolSuspendException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

@Slf4j
@Service
public class AgentToolAuditService {

    private final AgentRunToolStatus agentRunToolStatus;
    private final AgentBudgetService budgetService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentToolAuditService(AgentRunToolStatus agentRunToolStatus,
                                 AgentBudgetService budgetService,
                                 ObjectMapper objectMapper) {
        this.agentRunToolStatus = agentRunToolStatus;
        this.budgetService = budgetService;
        this.objectMapper = objectMapper;
    }

    public AgentToolAuditService(AgentRunToolStatus agentRunToolStatus) {
        this(agentRunToolStatus, null, new ObjectMapper());
    }

    public Map<String, Object> around(String toolName, Long userId, Long chapterId, RuntimeContext runtimeContext,
                                      Callable<Map<String, Object>> action) {
        long startedAt = System.currentTimeMillis();
        try {
            consumeToolBudget(toolName, runtimeContext);
            Map<String, Object> result = action.call();
            long durationMs = System.currentTimeMillis() - startedAt;
            Map<String, Object> envelope = successEnvelope(result);
            recordSucceeded(toolName, userId, chapterId, runtimeContext, durationMs, envelope);
            log.info("Agent tool succeeded: {}", event(toolName, userId, chapterId, "succeeded",
                    durationMs, null));
            return envelope;
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
        MangaAgentRuntimeContext context = runtimeContext == null ? null : runtimeContext.get(MangaAgentRuntimeContext.class);
        String stepId = context == null ? null : context.stepId();
        String auditId = result == null ? null : stringValue(result.get("auditId"));
        String resultHash = resultHash(result);
        UUID requestId = requestId(runtimeContext);
        if (requestId != null) {
            agentRunToolStatus.recordSucceeded(toolName, userId, chapterId, requestId, stepId,
                    auditId, resultHash, durationMs, result);
            return;
        }
        agentRunToolStatus.recordSucceeded(toolName, userId, chapterId, durationMs, result);
    }

    private void recordFailed(String toolName, Long userId, Long chapterId, RuntimeContext runtimeContext,
                              long durationMs, String error) {
        MangaAgentRuntimeContext context = runtimeContext == null ? null : runtimeContext.get(MangaAgentRuntimeContext.class);
        String stepId = context == null ? null : context.stepId();
        String auditId = UUID.randomUUID().toString();
        UUID requestId = requestId(runtimeContext);
        if (requestId != null) {
            agentRunToolStatus.recordFailed(toolName, userId, chapterId, requestId, stepId, auditId,
                    durationMs, error);
            return;
        }
        agentRunToolStatus.recordFailed(toolName, userId, chapterId, durationMs, error);
    }

    private UUID requestId(RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return null;
        }
        MangaAgentRuntimeContext context = runtimeContext.get(MangaAgentRuntimeContext.class);
        return context == null ? null : context.requestId();
    }

    private void consumeToolBudget(String toolName, RuntimeContext runtimeContext) {
        if (budgetService == null || runtimeContext == null || hasNativeBoundaryEvidence(toolName, runtimeContext)) {
            return;
        }
        if (runtimeContext.get(MangaAgentRuntimeContext.class) != null)
            throw new com.artverse.common.BusinessException(403, "工具调用缺少原生边界的预算授权");
    }

    private boolean hasNativeBoundaryEvidence(String toolName, RuntimeContext runtimeContext) {
        if (runtimeContext.get(com.artverse.agent.gateway.tools.AgentToolCapability.class) != null) return true;
        AgentToolAttempt attempt = runtimeContext.get(AgentToolAttempt.class);
        if (attempt == null || !attempt.toolName().equals(toolName)) return false;
        MangaAgentRuntimeContext context = runtimeContext.get(MangaAgentRuntimeContext.class);
        if (context != null) attempt.requireScope(context);
        return true;
    }

    private Map<String, Object> successEnvelope(Map<String, Object> result) {
        Map<String, Object> safeData = result == null ? Map.of() : Map.copyOf(result);
        Map<String, Object> envelope = new LinkedHashMap<>(safeData);
        envelope.put("success", true);
        envelope.put("errorCode", "");
        envelope.put("retryable", false);
        envelope.put("auditId", UUID.randomUUID().toString());
        envelope.put("resultHash", resultHash(safeData));
        return Map.copyOf(envelope);
    }

    private String resultHash(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return ToolIdempotencyService.sha256(objectMapper.writeValueAsString(payload));
        } catch (Exception error) {
            log.debug("Failed to serialize tool result for hashing", error);
            return ToolIdempotencyService.sha256(String.valueOf(payload));
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
