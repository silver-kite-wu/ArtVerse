package com.artverse.application;

import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentRunEventRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MangaAgentRunRouteTest {

    @Test
    void snapshotIncludesPersistedRoute() {
        Fixture fixture = fixture();
        MangaAgentRun run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "hello");
        run.setStatus(MangaAgentRunStatus.WAITING_USER);
        run.setRoute(MangaWorkflowRoute.REVIEW);
        run.setUserInputRequestJson("{\"question\":\"选择方案\",\"options\":[],\"allowFreeText\":false,\"reason\":\"\"}");

        when(fixture.eventRepository.findByRunIdOrderByIdAsc(99L)).thenReturn(List.of());

        MangaAgentRunService.RunSnapshot snapshot = fixture.service.snapshot(run);

        assertThat(snapshot.route()).isEqualTo(MangaWorkflowRoute.REVIEW);
    }

    @Test
    void snapshotCanRestoreAutoRoute() {
        Fixture fixture = fixture();
        MangaAgentRun run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "hello");
        run.setRoute(MangaWorkflowRoute.AUTO);

        when(fixture.eventRepository.findByRunIdOrderByIdAsc(99L)).thenReturn(List.of());

        MangaAgentRunService.RunSnapshot snapshot = fixture.service.snapshot(run);

        assertThat(snapshot.route()).isEqualTo(MangaWorkflowRoute.AUTO);
    }

    private Fixture fixture() {
        MangaAgentRunRepository runRepository = mock(MangaAgentRunRepository.class);
        MangaAgentRunEventRepository eventRepository = mock(MangaAgentRunEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MangaAgentRunService service = new MangaAgentRunService(runRepository, eventRepository, objectMapper);
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        return new Fixture(service, runRepository, eventRepository, objectMapper, user, chapter);
    }

    private MangaAgentRun run(User user, Chapter chapter, UUID requestId, String input) {
        MangaAgentRun run = new MangaAgentRun();
        run.setId(99L);
        run.setUser(user);
        run.setStory(chapter.getStory());
        run.setChapter(chapter);
        run.setRequestId(requestId);
        run.setInputMessage(input);
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);
        run.setCreatedAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        return run;
    }

    private record Fixture(MangaAgentRunService service,
                           MangaAgentRunRepository runRepository,
                           MangaAgentRunEventRepository eventRepository,
                           ObjectMapper objectMapper,
                           User user,
                           Chapter chapter) {
    }
}
