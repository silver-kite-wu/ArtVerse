package com.artverse.application;

import com.artverse.agent.AgentRunEvent;
import com.artverse.domain.MangaAgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.artverse.config.ArtVerseProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.WeakHashMap;

@Slf4j
@Service
public class MangaAgentRunEventPublisher {

    private final MangaAgentRunService mangaAgentRunService;
    private final ObjectMapper objectMapper;
    private final AgUiEventFactory agUiEventFactory;
    private final StringRedisTemplate redisTemplate;
    private final ArtVerseProperties properties;
    private final Set<String> activeTextMessages = ConcurrentHashMap.newKeySet();
    private final Set<SseEmitter> completedEmitters =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("manga-agent-sse-heartbeat-", 0).factory()
    );

    @Autowired
    public MangaAgentRunEventPublisher(MangaAgentRunService mangaAgentRunService,
                                       ObjectMapper objectMapper,
                                       AgUiEventFactory agUiEventFactory,
                                       StringRedisTemplate redisTemplate,
                                       ArtVerseProperties properties) {
        this.mangaAgentRunService = mangaAgentRunService;
        this.objectMapper = objectMapper;
        this.agUiEventFactory = agUiEventFactory;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public MangaAgentRunEventPublisher(MangaAgentRunService mangaAgentRunService,
                                       ObjectMapper objectMapper,
                                       AgUiEventFactory agUiEventFactory,
                                       StringRedisTemplate redisTemplate) {
        this(mangaAgentRunService, objectMapper, agUiEventFactory, redisTemplate, null);
    }

    public MangaAgentRunEventPublisher(MangaAgentRunService mangaAgentRunService,
                                       ObjectMapper objectMapper,
                                       AgUiEventFactory agUiEventFactory) {
        this(mangaAgentRunService, objectMapper, agUiEventFactory, null, null);
    }

    public RunEventSink newSink(SseEmitter emitter) {
        emitter.onCompletion(() -> markCompleted(emitter));
        emitter.onTimeout(() -> markCompleted(emitter));
        emitter.onError(error -> markCompleted(emitter));
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter), 15, 15, TimeUnit.SECONDS
        );
        emitter.onCompletion(() -> heartbeat.cancel(false));
        emitter.onTimeout(() -> heartbeat.cancel(false));
        emitter.onError(error -> heartbeat.cancel(false));
        return new RunEventSink(emitter);
    }

    /** Durable background execution has no browser transport or SSE heartbeat. */
    public RunEventSink newPersistentSink() {
        return new RunEventSink(null);
    }

    public final class RunEventSink {
        private final SseEmitter emitter;

        private RunEventSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        public void sendStatus(MangaAgentRun run, String message, UUID requestId) {
            MangaAgentRunEventPublisher.this.sendStatus(run, emitter, message, requestId);
        }

        public void sendPhase(MangaAgentRun run, UUID requestId, String phase, String message) {
            recordProgress(run, phase);
            run.setCurrentPhase(phase);
            Map<String, Object> event = agUiEventFactory.stateSnapshot(run, requestId, "RUNNING", message);
            appendRunEvent(run, "phase", event);
            sendAgUi(emitter, event);
        }

        public void sendToolEvent(MangaAgentRun run, AgentRunToolStatus.ToolEvent event) {
            MangaAgentRunEventPublisher.this.sendToolEvent(run, emitter, event);
        }

        public void recordToolProgress(MangaAgentRun run) {
            MangaAgentRunEventPublisher.this.recordToolProgress(run);
        }

        public void sendRunEvent(MangaAgentRun run, AgentRunEvent event) {
            MangaAgentRunEventPublisher.this.sendRunEvent(run, emitter, event);
        }

        public void recordProgress(MangaAgentRun run, String phase) {
            MangaAgentRunEventPublisher.this.recordProgress(run, phase);
        }

        public void sendUserInputRequested(MangaAgentRun run, UUID requestId, AgentUserInputRequest request) {
            MangaAgentRunEventPublisher.this.sendUserInputRequested(run, emitter, requestId, request);
        }


        public void sendDone(MangaAgentRun run, String reply, UUID requestId) {
            MangaAgentRunEventPublisher.this.sendDone(run, emitter, reply, requestId);
        }

        public void sendError(MangaAgentRun run, UUID requestId, String detail) {
            MangaAgentRunEventPublisher.this.sendError(run, emitter, requestId, detail);
        }

        public void complete() {
            MangaAgentRunEventPublisher.this.complete(emitter);
        }
    }

    private void sendStatus(MangaAgentRun run, SseEmitter emitter, String message, UUID requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("requestId", requestId);
        appendRunEvent(run, "status", payload);
        sendAgUi(emitter, agUiEventFactory.runStarted(run, requestId, message));
        sendAgUi(emitter, agUiEventFactory.stateSnapshot(run, requestId, "RUNNING", message));
    }

    private void sendToolEvent(MangaAgentRun run, SseEmitter emitter, AgentRunToolStatus.ToolEvent event) {
        UUID requestId = run == null ? null : run.getRequestId();
        sendAgUi(emitter, agUiEventFactory.toolAudit(requestId, event));
    }

    private void sendRunEvent(MangaAgentRun run, SseEmitter emitter, AgentRunEvent event) {
        Map<String, Object> payload = mangaAgentRunService.toPayload(event);
        if (!"text_delta".equals(event.type())) {
            appendRunEvent(run, "run_event", payload);
        }
        UUID requestId = run == null ? null : run.getRequestId();
        if ("text_delta".equals(event.type())) {
            ensureTextMessageStarted(emitter, requestId);
        }
        sendAgUi(emitter, agUiEventFactory.fromRunEvent(run, requestId, event));
    }

    private void sendUserInputRequested(MangaAgentRun run, SseEmitter emitter, UUID requestId,
                                        AgentUserInputRequest request) {
        Map<String, Object> payload = userInputPayload(requestId, request);
        appendRunEvent(run, "user_input_requested", payload);
        sendAgUi(emitter, agUiEventFactory.userInputRequested(run, requestId, request));
        sendAgUi(emitter, agUiEventFactory.stateSnapshot(run, requestId, "WAITING_USER", request.question()));
        complete(emitter);
    }


    private void sendDone(MangaAgentRun run, SseEmitter emitter, String reply, UUID requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reply", reply);
        payload.put("requestId", requestId);
        appendRunEvent(run, "done", payload);
        finishTextMessageIfNeeded(emitter, requestId);
        sendAgUi(emitter, agUiEventFactory.runFinished(run, requestId, reply));
        complete(emitter);
    }

    private void sendError(MangaAgentRun run, SseEmitter emitter, UUID requestId, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("detail", detail);
        payload.put("requestId", requestId);
        appendRunEvent(run, "error", payload);
        finishTextMessageIfNeeded(emitter, requestId);
        sendAgUi(emitter, agUiEventFactory.runError(requestId, detail));
        complete(emitter);
    }

    private Map<String, Object> toolEventPayload(AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", event.toolName());
        payload.put("stepId", event.stepId());
        payload.put("status", event.status());
        payload.put("succeeded", event.succeeded());
        payload.put("durationMs", event.durationMs());
        payload.put("resultHash", event.resultHash());
        payload.put("auditId", event.auditId());
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
        payload.put("purpose", request.purpose());
        return payload;
    }

    private void appendRunEvent(MangaAgentRun run, String eventName, Map<String, Object> payload) {
        if (run == null) {
            return;
        }
        try {
            Long eventId = mangaAgentRunService.appendEvent(run, eventName, payload);
            publishRedisEvent(run, eventId, eventName, payload);
        } catch (Exception e) {
            log.warn("Failed to persist manga agent run event {}", eventName, e);
        }
    }

    private void sendAgUi(SseEmitter emitter, Map<String, Object> event) {
        if (emitter == null || isCompleted(emitter)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (IllegalStateException e) {
            markCompleted(emitter);
            log.debug("Manga agent SSE was already closed before event could be sent");
        } catch (Exception e) {
            markCompleted(emitter);
            log.debug("Manga agent SSE disconnected before event could be sent", e);
        }
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
        if (!markCompleted(emitter)) {
            return;
        }
        String emitterPrefix = System.identityHashCode(emitter) + ":";
        activeTextMessages.removeIf(key -> key.startsWith(emitterPrefix));
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("Manga agent SSE was already closed during completion", e);
        }
    }

    private void publishRedisEvent(MangaAgentRun run, Long eventId, String eventName,
                                   Map<String, Object> payload) {
        if (redisTemplate == null || eventId == null
                || (properties != null && !properties.getAgent().isMultiInstanceEventBusEnabled())) return;
        try {
            Map<String, String> streamBody = new LinkedHashMap<>();
            streamBody.put("event_id", String.valueOf(eventId));
            streamBody.put("event_name", eventName);
            streamBody.put("payload", objectMapper.writeValueAsString(payload));
            redisTemplate.opsForStream().add(
                    "artverse:agent:run-events:" + run.getRequestId(), streamBody);
            redisTemplate.opsForStream().trim(
                    "artverse:agent:run-events:" + run.getRequestId(), 2000, true);
        } catch (Exception error) {
            log.warn("Run event {} was persisted but Redis publication failed", eventId);
        }
    }

    private void recordToolProgress(MangaAgentRun run) {
        if (run != null) {
            mangaAgentRunService.recordProgress(run, "TOOL");
        }
    }

    private void recordProgress(MangaAgentRun run, String phase) {
        if (run == null) {
            return;
        }
        mangaAgentRunService.recordProgress(run, phase);
    }

    private void sendHeartbeat(SseEmitter emitter) {
        if (emitter == null || isCompleted(emitter)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("keepalive"));
        } catch (Exception e) {
            markCompleted(emitter);
            log.debug("Manga agent SSE disconnected before heartbeat could be sent", e);
        }
    }

    private boolean isCompleted(SseEmitter emitter) {
        return completedEmitters.contains(emitter);
    }

    private boolean markCompleted(SseEmitter emitter) {
        return completedEmitters.add(emitter);
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }
}
