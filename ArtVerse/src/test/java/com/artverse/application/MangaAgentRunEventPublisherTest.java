package com.artverse.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.Map;
import com.artverse.domain.MangaAgentRun;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MangaAgentRunEventPublisherTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void phaseIsPersistedAsReplayableSnapshotWithoutStartingAnotherRun() {
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaAgentRunEventPublisher publisher = new MangaAgentRunEventPublisher(runService, new ObjectMapper(), new AgUiEventFactory());
        MangaAgentRun run = new MangaAgentRun();
        UUID requestId = UUID.randomUUID();
        run.setRequestId(requestId);
        publisher.newPersistentSink().sendPhase(run, requestId, "BUILDING_REQUEST", "正在整理创作上下文");
        verify(runService).recordProgress(run, "BUILDING_REQUEST");
        ArgumentCaptor<Map<String, Object>> event = ArgumentCaptor.forClass((Class) Map.class);
        verify(runService).appendEvent(eq(run), eq("phase"), event.capture());
        assertThat(event.getValue()).containsEntry("type", "STATE_SNAPSHOT");
        assertThat((Map<String, Object>) event.getValue().get("snapshot"))
                .containsEntry("phase", "BUILDING_REQUEST").containsEntry("status", "RUNNING");
    }

    @Test
    void doesNotSendAfterEmitterHasCompleted() throws Exception {
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        SseEmitter emitter = mock(SseEmitter.class);
        MangaAgentRunEventPublisher publisher = new MangaAgentRunEventPublisher(
                runService, new ObjectMapper(), new AgUiEventFactory()
        );
        MangaAgentRunEventPublisher.RunEventSink sink = publisher.newSink(emitter);

        sink.complete();
        sink.sendError(null, UUID.randomUUID(), "late failure");

        verify(emitter).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
