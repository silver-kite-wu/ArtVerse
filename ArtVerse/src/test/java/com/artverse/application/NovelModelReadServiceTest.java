package com.artverse.application;

import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.domain.MangaAgentRun;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class NovelModelReadServiceTest {
    @Test void historicalNativeReadDoesNotBlockALaterModelRequest() {
        var runs = mock(MangaAgentRunService.class);
        var repository = mock(MangaAgentRunRepository.class);
        var context = new MangaAgentRuntimeContext(
                1L, 2L, 3L, UUID.randomUUID(), UUID.randomUUID(), null,
                AgentTaskType.STORY_CHAT_WRITE, "story-chat:POLISH", 1L, null, "owner");
        var run = new MangaAgentRun();
        run.setRunAttributesJson("{}");
        when(repository.findForUpdate(context.userId(), context.chapterId(), context.requestId()))
                .thenReturn(Optional.of(run));
        var service = new NovelModelReadService(
                runs, repository, mock(ChapterRepository.class), new ObjectMapper().findAndRegisterModules());
        var historical = List.of(new StoryboardArtifactService.ModelReadInput("earlier-read", Map.of(
                "read_receipt", Map.of(
                        "request_id", UUID.randomUUID().toString(),
                        "step_id", "story-chat:POLISH",
                        "conversation_id", context.conversationId().toString()))));

        assertThatCode(() -> service.validateModelReadInput(context, historical)).doesNotThrowAnyException();
        assertThatCode(() -> service.recordModelReadInput(
                context, UUID.randomUUID(), "c".repeat(64), historical)).doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }
}
