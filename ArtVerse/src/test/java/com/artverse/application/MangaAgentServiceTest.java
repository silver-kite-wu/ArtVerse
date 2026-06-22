package com.artverse.application;

import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentScopeEventMapper;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaAgentMessageRepository;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaAgentServiceTest {

    @Test
    void runUsesGenerationGuardAndSavesAssistantReply() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.gateway.generateText(any(AgentRunRequest.class))).thenReturn(Mono.just("\u5b8c\u6210"));
        when(fixture.guard.executeMangaAgentRun(eq(1L), eq(7L), eq(requestId.toString()), eq("continue"),
                eq("deepseek"), eq("deepseek-chat"), any(), any()))
                .thenAnswer(invocation -> invocation.<Callable<Map<String, Object>>>getArgument(7).call());

        MangaAgentService.RunResult result = fixture.service.run(7L, "continue", requestId, fixture.user);

        assertThat(result.reply()).isEqualTo("\u5b8c\u6210");
        assertThat(fixture.saved).extracting(MangaAgentMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        verify(fixture.guard).executeMangaAgentRun(eq(1L), eq(7L), eq(requestId.toString()), eq("continue"),
                eq("deepseek"), eq("deepseek-chat"), any(), any());
    }

    @Test
    void runSavesSystemFailureMarkerWhenGatewayFails() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.gateway.generateText(any(AgentRunRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("model down")));
        when(fixture.guard.executeMangaAgentRun(eq(1L), eq(7L), eq(requestId.toString()), eq("continue"),
                eq("deepseek"), eq("deepseek-chat"), any(), any()))
                .thenAnswer(invocation -> invocation.<Callable<Map<String, Object>>>getArgument(7).call());

        assertThatThrownBy(() -> fixture.service.run(7L, "continue", requestId, fixture.user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Agent service failed");

        assertThat(fixture.saved).extracting(MangaAgentMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.SYSTEM);
        assertThat(fixture.saved.get(1).getContent()).contains("agent_run_failed", "model down");
    }

    @Test
    void runReturnsFallbackAssistantReplyWhenToolSavedBeforeGatewayFails() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.gateway.generateText(any(AgentRunRequest.class))).thenAnswer(invocation -> {
            fixture.toolStatus.recordSucceeded(
                    "save_structured_storyboard",
                    1L,
                    7L,
                    requestId,
                    12L,
                    Map.of("saved", true, "scenes_count", 1)
            );
            return Mono.error(new IllegalStateException("final response timed out"));
        });
        when(fixture.guard.executeMangaAgentRun(eq(1L), eq(7L), eq(requestId.toString()), eq("rewrite storyboard"),
                eq("deepseek"), eq("deepseek-chat"), any(), any()))
                .thenAnswer(invocation -> invocation.<Callable<Map<String, Object>>>getArgument(7).call());

        MangaAgentService.RunResult result = fixture.service.run(7L, "rewrite storyboard", requestId, fixture.user);

        assertThat(result.reply()).contains("\u5206\u955c\u5df2\u7ecf\u91cd\u5199\u5e76\u4fdd\u5b58", "\u6700\u7ec8\u603b\u7ed3\u56de\u590d\u6ca1\u6709\u53ca\u65f6\u5b8c\u6210");
        assertThat(fixture.saved).extracting(MangaAgentMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM);
        assertThat(fixture.saved.get(1).getContent()).contains("\u5206\u955c\u5df2\u7ecf\u91cd\u5199\u5e76\u4fdd\u5b58");
        assertThat(fixture.saved.get(2).getContent()).contains("agent_run_degraded_after_tool_success");
    }

    private Fixture fixture() {
        MangaAgentMessageRepository messageRepository = mock(MangaAgentMessageRepository.class);
        HarnessAgentGateway gateway = mock(HarnessAgentGateway.class);
        AgentWorkspaceSyncService syncService = mock(AgentWorkspaceSyncService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        ChapterAccessService accessService = mock(ChapterAccessService.class);
        GenerationGuardService guard = mock(GenerationGuardService.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaAgentRunEventPublisher eventPublisher = mock(MangaAgentRunEventPublisher.class);
        MangaAgentConversationRegistry conversationRegistry = mock(MangaAgentConversationRegistry.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        AgentRunToolStatus toolStatus = new AgentRunToolStatus();
        properties.getAgent().setRunTimeoutSeconds(5);
        properties.getDeepseek().setModel("deepseek-chat");
        Dotenv dotenv = mock(Dotenv.class);
        when(dotenv.get("DEEPSEEK_API_KEY", "")).thenReturn("");

        User user = user(1L);
        Chapter chapter = chapter(user);
        MangaAgentConversation conversation = conversation(user, chapter);
        List<MangaAgentMessage> saved = new ArrayList<>();
        when(accessService.requireVisible(7L, 1L)).thenReturn(chapter);
        when(conversationRegistry.activeOrCreate(7L, user)).thenReturn(conversation);
        when(apiKeyService.getDecryptedKey(user, "deepseek")).thenReturn("deepseek-key");
        when(apiKeyService.getDecryptedKey(user, "coze")).thenReturn("coze-key");
        when(messageRepository.findByConversationIdAndRequestIdAndRole(eq(99L), any(UUID.class), any(MessageRole.class)))
                .thenReturn(Optional.empty());
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(99L))
                .thenAnswer(invocation -> List.copyOf(saved));
        when(messageRepository.save(any(MangaAgentMessage.class))).thenAnswer(invocation -> {
            MangaAgentMessage message = invocation.getArgument(0);
            saved.add(message);
            return message;
        });

        MangaAgentConversationService conversationService =
                new MangaAgentConversationService(messageRepository, accessService);
        MangaAgentService service = new MangaAgentService(
                conversationService,
                conversationRegistry,
                gateway,
                new AgentModelSpecFactory(properties, dotenv),
                syncService,
                apiKeyService,
                accessService,
                guard,
                properties,
                toolStatus,
                new AgentScopeEventMapper(),
                runService,
                eventPublisher,
                Executors.newSingleThreadExecutor()
        );
        return new Fixture(service, gateway, guard, toolStatus, user, saved);
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
        chapter.setImageCount(1);
        return chapter;
    }

    private static MangaAgentConversation conversation(User user, Chapter chapter) {
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(99L);
        conversation.setConversationUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        conversation.setUser(user);
        conversation.setStory(chapter.getStory());
        conversation.setChapter(chapter);
        conversation.setTitle("测试对话");
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
        return conversation;
    }

    private record Fixture(MangaAgentService service,
                           HarnessAgentGateway gateway,
                           GenerationGuardService guard,
                           AgentRunToolStatus toolStatus,
                           User user,
                           List<MangaAgentMessage> saved) {
    }
}
