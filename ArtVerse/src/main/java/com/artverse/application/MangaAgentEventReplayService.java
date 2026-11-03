package com.artverse.application;

import com.artverse.domain.MangaAgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replays the persisted run event log and tails it while the run is active.
 * Any application instance can serve the stream because ownership is checked
 * against the database on every page and event ids are database sequence ids.
 */
@Slf4j
@Service
public class MangaAgentEventReplayService {

    private static final long POLL_INTERVAL_MILLIS = 500L;
    private static final int HEARTBEAT_EVERY_POLLS = 30;

    private final MangaAgentRunService runService;
    private final ObjectMapper objectMapper;
    private final ExecutorService replayExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MangaAgentEventReplayService(MangaAgentRunService runService, ObjectMapper objectMapper) {
        this.runService = runService;
        this.objectMapper = objectMapper;
    }

    public SseEmitter replay(Long userId, Long chapterId, UUID requestId, long afterEventId) {
        // Zero delegates lifecycle to disconnect/terminal state instead of imposing
        // a coarse total run timeout on a potentially long BYOK request.
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean connected = new AtomicBoolean(true);
        emitter.onCompletion(() -> connected.set(false));
        emitter.onTimeout(() -> connected.set(false));
        emitter.onError(error -> connected.set(false));
        replayExecutor.submit(() -> tail(emitter, connected, userId, chapterId, requestId, afterEventId));
        return emitter;
    }

    private void tail(SseEmitter emitter, AtomicBoolean connected, Long userId, Long chapterId,
                      UUID requestId, long afterEventId) {
        long cursor = Math.max(0L, afterEventId);
        int emptyPolls = 0;
        try {
            while (connected.get()) {
                MangaAgentRunSnapshots.RunEventReplayPage page =
                        runService.replayEvents(userId, chapterId, requestId, cursor);
                for (MangaAgentRunSnapshots.RunEventSnapshot event : page.events()) {
                    sendEvent(emitter, event);
                    cursor = event.eventId();
                }
                if (!page.events().isEmpty()) {
                    emptyPolls = 0;
                    continue;
                }
                if (page.status() != MangaAgentRunStatus.RUNNING) {
                    sendStateSnapshot(emitter, requestId, page.status(), page.lastEventId());
                    emitter.complete();
                    return;
                }
                if (++emptyPolls >= HEARTBEAT_EVERY_POLLS) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    emptyPolls = 0;
                }
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            completeQuietly(emitter);
        } catch (Exception error) {
            if (connected.get()) {
                log.debug("Manga agent replay stream disconnected for request {}", requestId, error);
                completeWithErrorQuietly(emitter, error);
            }
        }
    }

    private void sendEvent(SseEmitter emitter, MangaAgentRunSnapshots.RunEventSnapshot event) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("eventId", event.eventId());
        value.put("eventName", event.eventName());
        value.put("data", event.data());
        value.put("createdAt", event.createdAt());

        Map<String, Object> agUiEvent = new LinkedHashMap<>();
        agUiEvent.put("type", AgUiEventFactory.EVENT_CUSTOM);
        agUiEvent.put("name", "artverse.replayed_run_event");
        agUiEvent.put("value", value);
        agUiEvent.put("protocol", "ag-ui");
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.eventId()))
                .data(objectMapper.writeValueAsString(agUiEvent), MediaType.APPLICATION_JSON));
    }

    private void sendStateSnapshot(SseEmitter emitter, UUID requestId, MangaAgentRunStatus status,
                                   long lastEventId) throws Exception {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("requestId", requestId.toString());
        snapshot.put("runId", requestId.toString());
        snapshot.put("status", status.name());
        snapshot.put("lastEventId", lastEventId);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", AgUiEventFactory.EVENT_STATE_SNAPSHOT);
        event.put("snapshot", snapshot);
        event.put("protocol", "ag-ui");
        emitter.send(SseEmitter.event()
                .id(String.valueOf(lastEventId))
                .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Already disconnected.
        }
    }

    private void completeWithErrorQuietly(SseEmitter emitter, Exception error) {
        try {
            emitter.completeWithError(error);
        } catch (Exception ignored) {
            // Already disconnected.
        }
    }

    @PreDestroy
    void shutdown() {
        replayExecutor.shutdownNow();
    }
}
