package com.artverse.application;

import com.artverse.agents.AgentMessage;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MangaAgentConversationServiceTest {

    @Test
    void buildMessagesExcludesCurrentRequestHistoryAndKeepsRecentMessages() {
        Fixture fixture = fixture();
        UUID currentRequestId = UUID.randomUUID();
        UUID previousRequestId = UUID.randomUUID();
        List<MangaAgentMessage> history = List.of(
                message(fixture.user, fixture.chapter, MessageRole.USER, "old question", previousRequestId),
                message(fixture.user, fixture.chapter, MessageRole.ASSISTANT, "old answer", previousRequestId),
                message(fixture.user, fixture.chapter, MessageRole.USER, "current request echo", currentRequestId),
                message(fixture.user, fixture.chapter, MessageRole.SYSTEM, "internal", currentRequestId)
        );

        List<AgentMessage> messages = fixture.service.buildMessages(
                fixture.chapter, fixture.user, history, "current question", currentRequestId);

        assertThat(messages).extracting(AgentMessage::content)
                .anyMatch(content -> content.contains("ArtVerse Manga Director"))
                .contains("old question", "old answer", "current question")
                .doesNotContain("current request echo", "internal");
    }

    @Test
    void fallbackAfterToolSuccessWritesAssistantAndSystemMarkers() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        AgentRunToolStatus toolStatus = new AgentRunToolStatus();
        when(fixture.messageRepository.findByUserIdAndChapterIdAndRequestIdAndRole(eq(1L), eq(7L), any(UUID.class), any(MessageRole.class)))
                .thenReturn(Optional.empty());

        try (AgentRunToolStatus.RunScope scope = toolStatus.start(1L, 7L, requestId)) {
            toolStatus.recordSucceeded(
                    "save_structured_storyboard",
                    1L,
                    7L,
                    requestId,
                    25L,
                    Map.of("scenes_count", 12)
            );

            Map<String, Object> result = fixture.service.fallbackAfterToolSuccess(
                    fixture.user, fixture.chapter, requestId, scope.state(), "boom");

            assertThat(result.get("agent_final_response_degraded")).isEqualTo(true);
            assertThat(fixture.saved).extracting(MangaAgentMessage::getRole)
                    .contains(MessageRole.ASSISTANT, MessageRole.SYSTEM);
            assertThat(fixture.saved.get(0).getContent()).contains("\u5206\u955c\u5df2\u7ecf\u91cd\u5199\u5e76\u4fdd\u5b58");
            assertThat(fixture.saved.get(1).getContent()).contains("agent_run_degraded_after_tool_success");
        }
    }

    @Test
    void resumeMessageFormatsWaitingQuestionAndSelection() {
        Fixture fixture = fixture();
        AgentUserInputRequest waiting = new AgentUserInputRequest(
                "\u8bf7\u9009\u62e9\u6570\u636e\u5e93",
                List.of(
                        new AgentUserInputRequest.Option("mysql", "MySQL", "MySQL", true),
                        new AgentUserInputRequest.Option("postgres", "PostgreSQL", "PostgreSQL", false)
                ),
                true,
                "\u9700\u8981\u7528\u6237\u51b3\u7b56"
        );

        String message = fixture.service.resumeMessage("\u7ee7\u7eed\u4efb\u52a1", waiting, "PostgreSQL");

        assertThat(message).contains("\u7ee7\u7eed\u4e4b\u524d\u6682\u505c\u7684\u6f2b\u753b\u667a\u80fd\u4f53\u4efb\u52a1", "\u7ee7\u7eed\u4efb\u52a1", "\u8bf7\u9009\u62e9\u6570\u636e\u5e93", "PostgreSQL");
    }

    private Fixture fixture() {
        MangaAgentMessageRepository messageRepository = mock(MangaAgentMessageRepository.class);
        ChapterAccessService accessService = mock(ChapterAccessService.class);
        MangaAgentConversationService service = new MangaAgentConversationService(messageRepository, accessService);
        User user = user(1L);
        Chapter chapter = chapter(user);
        List<MangaAgentMessage> saved = new ArrayList<>();
        when(accessService.requireVisible(7L, 1L)).thenReturn(chapter);
        when(messageRepository.findByUserIdAndChapterIdOrderByCreatedAtAsc(1L, 7L))
                .thenAnswer(invocation -> List.copyOf(saved));
        when(messageRepository.findByUserIdAndChapterIdAndRequestIdAndRole(eq(1L), eq(7L), any(UUID.class), any(MessageRole.class)))
                .thenReturn(Optional.empty());
        when(messageRepository.save(any(MangaAgentMessage.class))).thenAnswer(invocation -> {
            MangaAgentMessage message = invocation.getArgument(0);
            saved.add(message);
            return message;
        });
        return new Fixture(service, messageRepository, accessService, user, chapter, saved);
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Chapter chapter(User user) {
        Story story = new Story();
        story.setId(3L);
        story.setTitle("\u6545\u4e8b");
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setColorMode(ColorMode.BW);
        chapter.setImageCount(10);
        return chapter;
    }

    private static MangaAgentMessage message(User user, Chapter chapter, MessageRole role, String content, UUID requestId) {
        MangaAgentMessage message = new MangaAgentMessage();
        message.setUser(user);
        message.setStory(chapter.getStory());
        message.setChapter(chapter);
        message.setRole(role);
        message.setContent(content);
        message.setRequestId(requestId);
        return message;
    }

    private record Fixture(MangaAgentConversationService service,
                           MangaAgentMessageRepository messageRepository,
                           ChapterAccessService accessService,
                           User user,
                           Chapter chapter,
                           List<MangaAgentMessage> saved) {
    }
}
