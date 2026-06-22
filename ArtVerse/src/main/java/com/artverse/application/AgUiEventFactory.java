package com.artverse.application;

import com.artverse.agents.AgentRunEvent;
import com.artverse.domain.MangaAgentRun;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class AgUiEventFactory {

    public static final String EVENT_RUN_STARTED = "RUN_STARTED";
    public static final String EVENT_RUN_FINISHED = "RUN_FINISHED";
    public static final String EVENT_RUN_ERROR = "RUN_ERROR";
    public static final String EVENT_STEP_STARTED = "STEP_STARTED";
    public static final String EVENT_STEP_FINISHED = "STEP_FINISHED";
    public static final String EVENT_TEXT_MESSAGE_START = "TEXT_MESSAGE_START";
    public static final String EVENT_TEXT_MESSAGE_CONTENT = "TEXT_MESSAGE_CONTENT";
    public static final String EVENT_TEXT_MESSAGE_END = "TEXT_MESSAGE_END";
    public static final String EVENT_TEXT_MESSAGE_CHUNK = "TEXT_MESSAGE_CHUNK";
    public static final String EVENT_TOOL_CALL_START = "TOOL_CALL_START";
    public static final String EVENT_TOOL_CALL_END = "TOOL_CALL_END";
    public static final String EVENT_TOOL_CALL_RESULT = "TOOL_CALL_RESULT";
    public static final String EVENT_STATE_SNAPSHOT = "STATE_SNAPSHOT";
    public static final String EVENT_CUSTOM = "CUSTOM";

    public Map<String, Object> runStarted(MangaAgentRun run, UUID requestId, String message) {
        Map<String, Object> event = base(EVENT_RUN_STARTED);
        event.put("threadId", threadId(run));
        event.put("runId", runId(requestId));
        event.put("input", Map.of(
                "threadId", threadId(run),
                "runId", runId(requestId),
                "state", Map.of("status", "RUNNING", "message", message == null ? "" : message),
                "messages", java.util.List.of(),
                "tools", java.util.List.of(),
                "context", java.util.List.of(),
                "forwardedProps", Map.of()
        ));
        return event;
    }

    public Map<String, Object> stateSnapshot(MangaAgentRun run, UUID requestId, String status, String message) {
        Map<String, Object> event = base(EVENT_STATE_SNAPSHOT);
        event.put("snapshot", Map.of(
                "threadId", threadId(run),
                "runId", runId(requestId),
                "requestId", runId(requestId),
                "status", status,
                "message", message == null ? "" : message
        ));
        return event;
    }

    public Map<String, Object> fromRunEvent(MangaAgentRun run, UUID requestId, AgentRunEvent runEvent) {
        String type = runEvent.type();
        if ("text_delta".equals(type)) {
            Map<String, Object> event = base(EVENT_TEXT_MESSAGE_CONTENT);
            event.put("messageId", assistantMessageId(requestId));
            event.put("delta", runEvent.text() == null ? "" : runEvent.text());
            event.put("rawEvent", rawRunEvent(runEvent));
            return event;
        }
        if ("run_started".equals(type)) {
            return stateSnapshot(run, requestId, "RUNNING", runEvent.label());
        }

        Map<String, Object> event = base(EVENT_CUSTOM);
        event.put("name", type == null || type.isBlank() ? "artverse.run_event" : type);
        event.put("value", rawRunEvent(runEvent));
        return event;
    }

    public Map<String, Object> textMessageStart(UUID requestId) {
        Map<String, Object> event = base(EVENT_TEXT_MESSAGE_START);
        event.put("messageId", assistantMessageId(requestId));
        event.put("role", "assistant");
        return event;
    }

    public Map<String, Object> textMessageEnd(UUID requestId) {
        Map<String, Object> event = base(EVENT_TEXT_MESSAGE_END);
        event.put("messageId", assistantMessageId(requestId));
        return event;
    }

    public Map<String, Object> toolAudit(UUID requestId, AgentRunToolStatus.ToolEvent toolEvent) {
        Map<String, Object> event = base(EVENT_CUSTOM);
        event.put("name", "artverse.tool_audit");
        event.put("value", Map.of(
                "runId", runId(requestId),
                "payload", toolEventPayload(toolEvent)
        ));
        return event;
    }

    public Map<String, Object> userInputRequested(MangaAgentRun run, UUID requestId, AgentUserInputRequest request) {
        Map<String, Object> event = base(EVENT_RUN_FINISHED);
        event.put("threadId", threadId(run));
        event.put("runId", runId(requestId));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("question", nullToBlank(request.question()));
        metadata.put("options", request.options());
        metadata.put("allowFreeText", request.allowFreeText());

        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put("id", runId(requestId));
        interrupt.put("reason", request.reason() == null || request.reason().isBlank()
                ? "USER_INPUT_REQUIRED"
                : request.reason());
        interrupt.put("message", nullToBlank(request.question()));
        interrupt.put("metadata", metadata);

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("type", "interrupt");
        outcome.put("interrupts", java.util.List.of(interrupt));
        event.put("outcome", outcome);
        return event;
    }

    public Map<String, Object> runFinished(MangaAgentRun run, UUID requestId, String reply) {
        Map<String, Object> event = base(EVENT_RUN_FINISHED);
        event.put("threadId", threadId(run));
        event.put("runId", runId(requestId));
        event.put("result", Map.of("reply", reply == null ? "" : reply));
        event.put("outcome", Map.of("type", "success"));
        return event;
    }

    public Map<String, Object> runError(UUID requestId, String detail) {
        Map<String, Object> event = base(EVENT_RUN_ERROR);
        event.put("message", detail == null || detail.isBlank() ? "Agent run failed" : detail);
        event.put("code", "ARTVERSE_AGENT_RUN_FAILED");
        event.put("runId", runId(requestId));
        return event;
    }

    private Map<String, Object> base(String eventType) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", eventType);
        event.put("timestamp", Instant.now().toEpochMilli());
        event.put("protocol", "ag-ui");
        return event;
    }

    private String threadId(MangaAgentRun run) {
        if (run == null || run.getChapter() == null) {
            return "manga-agent";
        }
        return "chapter-" + run.getChapter().getId();
    }

    private String runId(UUID requestId) {
        return requestId == null ? "unknown" : requestId.toString();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String assistantMessageId(UUID requestId) {
        return "assistant-" + runId(requestId);
    }

    private Map<String, Object> rawRunEvent(AgentRunEvent event) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", event.type());
        raw.put("phase", event.phase());
        raw.put("label", event.label());
        raw.put("toolName", event.toolName());
        raw.put("status", event.status());
        raw.put("text", event.text());
        raw.put("data", event.data());
        raw.put("createdAt", event.createdAt() == null ? null : event.createdAt().toString());
        return raw;
    }

    private Map<String, Object> toolEventPayload(AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", event.toolName());
        payload.put("succeeded", event.succeeded());
        payload.put("durationMs", event.durationMs());
        payload.put("result", event.result());
        payload.put("error", event.error());
        return payload;
    }

}
