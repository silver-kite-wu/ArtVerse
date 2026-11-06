package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentModelSpec;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.workflow.MangaWorkflowContextSnapshot;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared test fixture helpers for workflow node tests.
 */
final class TestContexts {

    private TestContexts() {
    }

    static MangaWorkflowExecutionContext context(UUID requestId, MangaAgentRun run) {
        User user = run.getUser() != null ? run.getUser() : stubUser();
        Chapter chapter = run.getChapter() != null ? run.getChapter() : stubChapter(user);
        MangaAgentConversation conversation = run.getConversation() != null
                ? run.getConversation()
                : stubConversation(user, chapter);

        return new MangaWorkflowExecutionContext(
                conversation,
                "test message",
                requestId,
                "llm-key",
                new AgentModelSpec("deepseek", "", "model", "none"),
                stubRunState(),
                user,
                chapter,
                new MangaWorkflowContextSnapshot(3L, 7L, "Story", "Chapter", "style",
                        0, 0, "", "", "", "", MangaWorkflowRoute.DIRECTOR, "ctx",
                        List.of(), List.of()),
                true,
                null,
                MangaWorkflowExecutionContext.ExecutionClaim.from(run)
        );
    }

    private static AgentRunToolStatus.RunState stubRunState() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        doNothing().when(ops).set(anyString(), any(), any(Duration.class));
        when(ops.get(anyString())).thenReturn(null);
        AgentRunToolStatus status = new AgentRunToolStatus(redis);
        return status.start(1L, 7L, UUID.randomUUID()).state();
    }

    private static User stubUser() {
        User u = new User();
        u.setId(1L);
        return u;
    }

    private static Chapter stubChapter(User user) {
        Story story = new Story();
        story.setId(3L);
        story.setTitle("Test Story");
        story.setUser(user);
        Chapter c = new Chapter();
        c.setId(7L);
        c.setChapterNumber(1);
        c.setStory(story);
        c.setColorMode(ColorMode.BW);
        return c;
    }

    private static MangaAgentConversation stubConversation(User user, Chapter chapter) {
        MangaAgentConversation conv = new MangaAgentConversation();
        conv.setId(99L);
        conv.setUser(user);
        conv.setChapter(chapter);
        conv.setStory(chapter.getStory());
        conv.setConversationUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        conv.setStatus(MangaAgentConversationStatus.ACTIVE);
        return conv;
    }
}
