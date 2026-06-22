package com.artverse.application;

import com.artverse.agents.AgentRunEvent;
import com.artverse.domain.MangaAgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaAgentRunEventPublisher {

    private final MangaAgentRunService mangaAgentRunService;
    private final ObjectMapper objectMapper;
    private final AgUiEventFactory agUiEventFactory;
    private final Set<String> activeTextMessages = ConcurrentHashMap.newKeySet();

    public enum StreamProtocol {
        LEGACY_AND_AG_UI,
        AG_UI_ONLY
    }

    public RunEventSink legacyAndAgUi(SseEmitter emitter) {
        return new RunEventSink(emitter, StreamProtocol.LEGACY_AND_AG_UI);
    }

    public RunEventSink agUiOnly(SseEmitter emitter) {
        return new RunEventSink(emitter, StreamProtocol.AG_UI_ONLY);
    }

    public final class RunEventSink {
        private final SseEmitter emitter;
        private final StreamProtocol protocol;

        private RunEventSink(SseEmitter emitter, StreamProtocol protocol) {
            this.emitter = emitter;
            this.protocol = protocol;
        }

        public void sendStatus(MangaAgentRun run, String message, UUID requestId) {
            MangaAgentRunEventPublisher.this.sendStatus(run, emitter, message, requestId, protocol);
        }

        public void sendToolEvent(MangaAgentRun run, AgentRunToolStatus.ToolEvent event) {
            MangaAgentRunEventPublisher.this.sendToolEvent(run, emitter, event, protocol);
        }

        public void sendRunEvent(MangaAgentRun run, AgentRunEvent event) {
            MangaAgentRunEventPublisher.this.sendRunEvent(run, emitter, event, protocol);
        }

        public void sendUserInputRequested(MangaAgentRun run, UUID requestId, AgentUserInputRequest request) {
            MangaAgentRunEventPublisher.this.sendUserInputRequested(run, emitter, requestId, request, protocol);
        }

        public void sendUserAnswerEvent(MangaAgentRun run, UUID requestId, String answer) {
            MangaAgentRunEventPublisher.this.sendUserAnswerEvent(run, emitter, requestId, answer, protocol);
        }

        public void sendDone(MangaAgentRun run, String reply, UUID requestId) {
            MangaAgentRunEventPublisher.this.sendDone(run, emitter, reply, requestId, protocol);
        }

        public void sendError(MangaAgentRun run, UUID requestId, String detail) {
            MangaAgentRunEventPublisher.this.sendError(run, emitter, requestId, detail, protocol);
        }

        public void complete() {
            MangaAgentRunEventPublisher.this.complete(emitter);
        }
    }

    private void sendStatus(MangaAgentRun run, SseEmitter emitter, String message, UUID requestId,
                            StreamProtocol protocol) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("requestId", requestId);
        publish(run, emitter, "status", payload, protocol);
        sendAgUi(emitter, agUiEventFactory.runStarted(run, requestId, message));
        sendAgUi(emitter, agUiEventFactory.stateSnapshot(run, requestId, "RUNNING", message));
    }

    private void sendToolEvent(MangaAgentRun run, SseEmitter emitter, AgentRunToolStatus.ToolEvent event,
                               StreamProtocol protocol) {
        publish(run, emitter, "tool", toolEventPayload(event), protocol);
        UUID requestId = run == null ? null : run.getRequestId();
        sendAgUi(emitter, agUiEventFactory.toolAudit(requestId, event));
    }

    private void sendRunEvent(MangaAgentRun run, SseEmitter emitter, AgentRunEvent event,
                              StreamProtocol protocol) {
        Map<String, Object> payload = mangaAgentRunService.toPayload(event);
        if (!"text_delta".equals(event.type())) {
            appendRunEvent(run, "run_event", payload);
        }
        if (protocol == StreamProtocol.LEGACY_AND_AG_UI) {
            sendSse(emitter, "run_event", payload);
        }
        UUID requestId = run == null ? null : run.getRequestId();
        if ("text_delta".equals(event.type())) {
            ensureTextMessageStarted(emitter, requestId);
        }
        sendAgUi(emitter, agUiEventFactory.fromRunEvent(run, requestId, event));
    }

    private void sendUserInputRequested(MangaAgentRun run, SseEmitter emitter, UUID requestId,
                                        AgentUserInputRequest request, StreamProtocol protocol) {
        publish(run, emitter, "user_input_requested", userInputPayload(requestId, request), protocol);
        finishTextMessageIfNeeded(emitter, requestId);
        sendAgUi(emitter, agUiEventFactory.userInputRequested(run, requestId, request));
        complete(emitter);
    }

    private void sendUserAnswerEvent(MangaAgentRun run, SseEmitter emitter, UUID requestId, String answer,
                                     StreamProtocol protocol) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "user_answered");
        payload.put("phase", "human_input");
        payload.put("label", "已收到用户选择");
        payload.put("status", "success");
        payload.put("requestId", requestId);
        payload.put("answer", answer == null || answer.isBlank() ? "继续默认方案" : answer.trim());
        payload.put("createdAt", OffsetDateTime.now().toString());
        publish(run, emitter, "run_event", payload, protocol);
        if (protocol == StreamProtocol.LEGACY_AND_AG_UI) {
            sendAgUi(emitter, agUiEventFactory.stateSnapshot(run, requestId, "RUNNING", "已收到用户选择，继续执行"));
        }
    }

    private void sendDone(MangaAgentRun run, SseEmitter emitter, String reply, UUID requestId,
                          StreamProtocol protocol) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reply", reply);
        payload.put("requestId", requestId);
        publish(run, emitter, "done", payload, protocol);
        finishTextMessageIfNeeded(emitter, requestId);
        sendAgUi(emitter, agUiEventFactory.runFinished(run, requestId, reply));
        complete(emitter);
    }

    private void sendError(MangaAgentRun run, SseEmitter emitter, UUID requestId, String detail,
                           StreamProtocol protocol) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("detail", detail);
        payload.put("requestId", requestId);
        publish(run, emitter, "error", payload, protocol);
        finishTextMessageIfNeeded(emitter, requestId);
        sendAgUi(emitter, agUiEventFactory.runError(requestId, detail));
        complete(emitter);
    }

    private void publish(MangaAgentRun run, SseEmitter emitter, String eventName, Map<String, Object> payload,
                         StreamProtocol protocol) {
        appendRunEvent(run, eventName, payload);
        if (protocol == StreamProtocol.LEGACY_AND_AG_UI) {
            sendSse(emitter, eventName, payload);
        }
    }

    private Map<String, Object> toolEventPayload(AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", event.toolName());
        payload.put("succeeded", event.succeeded());
        payload.put("durationMs", event.durationMs());
        if (event.error() != null && !event.error().isBlank()) {
            payload.put("error", event.error());
        }
        Object saved = event.result().get("saved");
        if (saved != null) {
            payload.put("saved", saved);
        }
        Object scenesCount = event.result().get("scenes_count");
        if (scenesCount != null) {
            payload.put("scenes_count", scenesCount);
        }
        return payload;
    }

    private Map<String, Object> userInputPayload(UUID requestId, AgentUserInputRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("question", request.question());
        payload.put("options", request.options());
        payload.put("allowFreeText", request.allowFreeText());
        payload.put("reason", request.reason());
        return payload;
    }

    private void appendRunEvent(MangaAgentRun run, String eventName, Map<String, Object> payload) {
        if (run == null) {
            return;
        }
        try {
            mangaAgentRunService.appendEvent(run, eventName, payload);
        } catch (Exception e) {
            log.debug("Failed to persist manga agent run event {}: {}", eventName, e.getMessage());
        }
    }

    private void sendSse(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON);
            if (eventName != null && !eventName.isBlank()) {
                event.name(eventName);
            }
            emitter.send(event);
        } catch (Exception e) {
            log.debug("Failed to send manga agent SSE {}: {}", eventName, e.getMessage());
        }
    }

    private void sendAgUi(SseEmitter emitter, Map<String, Object> event) {
        sendSse(emitter, null, event);
    }

    private void ensureTextMessageStarted(SseEmitter emitter, UUID requestId) {
        String key = textMessageKey(emitter, requestId);
        if (key != null && activeTextMessages.add(key)) {
            sendAgUi(emitter, agUiEventFactory.textMessageStart(requestId));
        }
    }

    private void finishTextMessageIfNeeded(SseEmitter emitter, UUID requestId) {
        String key = textMessageKey(emitter, requestId);
        if (key != null && activeTextMessages.remove(key)) {
            sendAgUi(emitter, agUiEventFactory.textMessageEnd(requestId));
        }
    }

    private String textMessageKey(SseEmitter emitter, UUID requestId) {
        if (emitter == null || requestId == null) {
            return null;
        }
        return System.identityHashCode(emitter) + ":" + requestId;
    }

    private void complete(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        String emitterPrefix = System.identityHashCode(emitter) + ":";
        activeTextMessages.removeIf(key -> key.startsWith(emitterPrefix));
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("Failed to complete manga agent SSE: {}", e.getMessage());
        }
    }
}
