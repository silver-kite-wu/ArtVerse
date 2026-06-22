package com.artverse.application;

import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunEventRecord;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MangaAgentRunServiceTest {

    @Test
    void resumeStartKeepsOriginalInputAndClearsWaitingRequest() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun existing = run(fixture.user, fixture.chapter, requestId, "原始任务");
        existing.setStatus(MangaAgentRunStatus.WAITING_USER);
        existing.setUserInputRequestJson("{\"question\":\"选择方案\",\"options\":[],\"allowFreeText\":true,\"reason\":\"\"}");

        when(fixture.runRepository.findByUserIdAndChapterIdAndRequestId(1L, 7L, requestId))
                .thenReturn(Optional.of(existing));
        when(fixture.runRepository.save(any(MangaAgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MangaAgentRun result = fixture.service.startOrReuse(fixture.user, fixture.chapter, requestId, "恢复提示");

        assertThat(result.getStatus()).isEqualTo(MangaAgentRunStatus.RUNNING);
        assertThat(result.getInputMessage()).isEqualTo("原始任务");
        assertThat(result.getUserInputRequestJson()).isNull();
    }

    @Test
    void snapshotRestoresWaitingInputAndPersistedEvents() throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(fixture.user, fixture.chapter, requestId, "生成分镜");
        run.setStatus(MangaAgentRunStatus.WAITING_USER);
        AgentUserInputRequest waiting = new AgentUserInputRequest(
                "覆盖已有分镜吗？",
                List.of(new AgentUserInputRequest.Option("a", "覆盖", "使用新分镜", true)),
                true,
                "已有旧分镜"
        );
        run.setUserInputRequestJson(fixture.objectMapper.writeValueAsString(waiting));

        MangaAgentRunEventRecord event = new MangaAgentRunEventRecord();
        event.setId(10L);
        event.setRun(run);
        event.setEventName("run_event");
        event.setEventType("tool_call_started");
        event.setPhase("tool");
        event.setLabel("准备调用：保存结构化分镜");
        event.setStatus("running");
        event.setPayloadJson(fixture.objectMapper.writeValueAsString(Map.of(
                "type", "tool_call_started",
                "phase", "tool",
                "label", "准备调用：保存结构化分镜",
                "status", "running",
                "data", Map.of()
        )));

        when(fixture.eventRepository.findByRunIdOrderByIdAsc(99L)).thenReturn(List.of(event));

        MangaAgentRunService.RunSnapshot snapshot = fixture.service.snapshot(run);

        assertThat(snapshot.status()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
        assertThat(snapshot.userInputRequest().question()).isEqualTo("覆盖已有分镜吗？");
        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.events().get(0).eventName()).isEqualTo("run_event");
        assertThat(snapshot.events().get(0).data()).containsEntry("type", "tool_call_started");
    }

    @Test
    void cancelMarksRunTerminalAndTerminalStateIsNotOverwritten() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = run(fixture.user, fixture.chapter, requestId, "生成分镜");

        when(fixture.runRepository.findByUserIdAndChapterIdAndRequestId(1L, 7L, requestId))
                .thenReturn(Optional.of(run));
        when(fixture.runRepository.save(any(MangaAgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MangaAgentRun cancelled = fixture.service.cancel(requestId, 1L, 7L, "用户停止");
        fixture.service.markSucceeded(requestId, 1L, 7L, "不应覆盖");

        assertThat(cancelled.getStatus()).isEqualTo(MangaAgentRunStatus.CANCELLED);
        assertThat(cancelled.getErrorMessage()).isEqualTo("用户停止");
        assertThat(cancelled.getFinalReply()).isNull();
    }

    @Test
    void interruptStaleRunningRunsOnlyInterruptsOldRunningRuns() {
        Fixture fixture = fixture();
        MangaAgentRun run = run(fixture.user, fixture.chapter, UUID.randomUUID(), "生成分镜");
        OffsetDateTime staleBefore = OffsetDateTime.now().minusMinutes(10);

        when(fixture.runRepository.findByStatusAndUpdatedAtBefore(eq(MangaAgentRunStatus.RUNNING), eq(staleBefore)))
                .thenReturn(List.of(run));
        when(fixture.runRepository.save(any(MangaAgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int interrupted = fixture.service.interruptStaleRunningRuns(staleBefore);

        assertThat(interrupted).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.INTERRUPTED);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getErrorMessage()).contains("interrupted");
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
