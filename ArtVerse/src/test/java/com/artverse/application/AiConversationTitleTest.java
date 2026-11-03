package com.artverse.application;

import com.artverse.ai.ConversationTitleClient;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaAgentConversationRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiConversationTitleTest {
    private final MangaAgentConversationRepository repository = mock(MangaAgentConversationRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AiConversationService service = new AiConversationService(repository, mock(ChapterAccessService.class),
            mock(MangaAgentRunRepository.class), mock(ChapterRepository.class), events);

    @Test
    void firstShortMessageGetsFallbackAndOnlyOneGeneration() {
        MangaAgentConversation c = conversation();
        when(repository.findDefaultTitleForUpdate(1L)).thenReturn(Optional.of(c));
        service.autoTitle(c, "你好");
        service.touch(c);
        service.autoTitle(c, "后续消息不能重命名");
        assertThat(c.getTitle()).isEqualTo("你好");
        assertThat(c.getTitleSource()).isEqualTo(AiConversationTitleSource.FALLBACK);
        assertThat(c.getTitleState()).isEqualTo(AiConversationTitleState.GENERATING);
        verify(events).publishEvent(new ConversationTitleRequested(1L, 2L, "你好", "你好"));
        verify(repository).touchActivity(eq(1L), any());
        verify(repository, never()).save(any());
    }

    @Test
    void staleCallerCannotOverwriteUserTitle() {
        var current = conversation(); current.setTitle("用户标题"); current.setTitleSource(AiConversationTitleSource.USER);
        when(repository.findDefaultTitleForUpdate(1L)).thenReturn(Optional.empty());
        service.autoTitle(conversation(), "旧对象中的新会话");
        assertThat(current.getTitle()).isEqualTo("用户标题");
        verifyNoInteractions(events);
    }

    @Test
    void blankInputWaitsAndModelInputIsBounded() {
        service.autoTitle(conversation(), "\n \t");
        verifyNoInteractions(repository, events);
        when(repository.findDefaultTitleForUpdate(1L)).thenReturn(Optional.of(conversation()));
        service.autoTitle(conversation(), "😀".repeat(2500));
        verify(events).publishEvent(new ConversationTitleRequested(1L, 2L, "😀".repeat(2000), "😀".repeat(24)));
    }

    @Test
    void fallbackPreservesContentAndUnicodeWithoutSplittingSurrogates() {
        assertThat(AiConversationService.fallbackTitle("  帮我\n\t设计一个故事  ")).isEqualTo("帮我 设计一个故事");
        assertThat(AiConversationService.fallbackTitle("# C++ *指针* | 用法")).isEqualTo("# C++ *指针* | 用法");
        assertThat(AiConversationService.fallbackTitle("😀".repeat(30))).isEqualTo("😀".repeat(24));
        assertThat(AiConversationService.fallbackTitle("中".repeat(25))).isEqualTo("中".repeat(24));
        assertThat(AiConversationService.fallbackTitle(null)).isEqualTo("新会话");
        assertThat(ConversationTitleClient.normalize("“设计奇幻世界”")).isEqualTo("设计奇幻世界");
        assertThatThrownBy(() -> ConversationTitleClient.normalize("标题\n解释")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationTitleClient.normalize("新会话")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void successfulGenerationFinalizesAiTitleAndFailureKeepsFallback() {
        var apiKeys = mock(ApiKeyService.class);
        var client = mock(ConversationTitleClient.class);
        var config = new UserProviderConfig("llm", "provider", "fixture", "test-key", "https://example.test", "test-model");
        when(apiKeys.requireActiveUserProviderConfig(any(), eq("llm"), anyString())).thenReturn(config);
        var generator = generator(Runnable::run, apiKeys, client);
        var event = new ConversationTitleRequested(1L, 2L, "用户内容", "用户内容");
        when(client.generate(config, event.input())).thenReturn("奇幻故事构思");
        generator.requested(event);
        verify(repository).finalizeGeneratedTitle(1L, "奇幻故事构思", AiConversationTitleSource.AI, AiConversationTitleState.FINALIZED);
        when(client.generate(config, event.input())).thenThrow(new IllegalStateException("timeout"));
        generator.requested(event);
        verify(repository).finalizeGeneratedTitle(1L, event.fallback(), AiConversationTitleSource.FALLBACK, AiConversationTitleState.FINALIZED);
    }

    @Test
    void missingProfileAndFullQueueKeepFallback() {
        var apiKeys = mock(ApiKeyService.class);
        var client = mock(ConversationTitleClient.class);
        var event = new ConversationTitleRequested(1L, 2L, "内容", "内容");
        when(apiKeys.requireActiveUserProviderConfig(any(), anyString(), anyString())).thenThrow(new IllegalStateException("no profile"));
        generator(Runnable::run, apiKeys, client).requested(event);
        generator(task -> { throw new RejectedExecutionException(); }, apiKeys, client).requested(event);
        verify(repository, times(2)).finalizeGeneratedTitle(1L, "内容", AiConversationTitleSource.FALLBACK, AiConversationTitleState.FINALIZED);
        verifyNoInteractions(client);
    }

    private ConversationTitleGenerator generator(Executor executor, ApiKeyService apiKeys, ConversationTitleClient client) {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        return new ConversationTitleGenerator(executor, apiKeys, client, repository, manager);
    }

    private MangaAgentConversation conversation() {
        var c = new MangaAgentConversation(); c.setId(1L); c.setTitle("新会话");
        var user = new User(); user.setId(2L); c.setUser(user);
        return c;
    }
}
