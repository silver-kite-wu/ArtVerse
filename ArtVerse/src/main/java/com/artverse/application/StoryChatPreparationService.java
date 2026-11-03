package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.persistence.MangaAgentConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Short database transactions run on the worker, before any retrieval/model network call. */
@Service
@RequiredArgsConstructor
public class StoryChatPreparationService {
    private static final int MAX_HISTORY_MESSAGES = 24;
    private final MangaAgentConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChapterAccessService chapterAccess;
    private final AiConversationService conversationService;

    @Transactional(readOnly = true)
    public Scope resolve(Long userId, Long chapterId, UUID conversationId) {
        MangaAgentConversation conversation = conversations.findByUserIdAndConversationUuid(userId, conversationId)
                .orElseThrow(() -> new BusinessException(404, "Story chat conversation not found"));
        if (conversation.getConversationType() != AiConversationType.STORY_CHAT
                || conversation.getChapter() == null || !conversation.getChapter().getId().equals(chapterId)) {
            throw new BusinessException(404, "Story chat conversation not found");
        }
        Chapter chapter = chapterAccess.requireVisible(chapterId, userId);
        return new Scope(conversation.getId(), conversationId, userId, chapter.getStory().getId(), chapterId);
    }

    @Transactional
    public void touch(Scope scope, String message) {
        MangaAgentConversation conversation = conversations.getReferenceById(scope.id());
        conversationService.autoTitle(conversation, message);
        conversationService.touch(conversation);
    }

    @Transactional(readOnly = true)
    public StoryChatContextSnapshot snapshot(Scope scope, UUID requestId, String userMessage) {
        // Revalidate on the worker: permissions/chapter binding may have changed since submission.
        Scope current = resolve(scope.userId(), scope.chapterId(), scope.conversationId());
        Chapter chapter = chapterAccess.requireVisible(current.chapterId(), current.userId());
        List<AgentMessage> history = messages.findByConversationIdOrderByCreatedAtAscIdAsc(current.id()).stream()
                .filter(message -> !(Objects.equals(message.getRequestId(), requestId)
                        && message.getRole() == MessageRole.USER && Objects.equals(message.getContent(), userMessage)))
                .map(message -> new AgentMessage(message.getRole().name().toLowerCase(java.util.Locale.ROOT), message.getContent()))
                .toList();
        history = history.subList(Math.max(0, history.size() - MAX_HISTORY_MESSAGES), history.size());
        return new StoryChatContextSnapshot(current.userId(), current.storyId(), current.chapterId(),
                current.conversationId(), requestId, chapter.getChapterNumber(),
                chapter.getVersion() == null ? 0L : chapter.getVersion(), chapter.getNovelContent(), history);
    }

    @Transactional
    public ChatMessage saveMessage(Scope scope, MessageRole role, String content, UUID requestId, String skills) {
        return messages.findByConversationIdAndRequestIdAndRole(scope.id(), requestId, role).orElseGet(() -> {
            MangaAgentConversation conversation = conversations.getReferenceById(scope.id());
            ChatMessage message = new ChatMessage();
            message.setChapter(conversation.getChapter());
            message.setConversation(conversation);
            message.setRole(role);
            message.setContent(content);
            message.setRequestId(requestId);
            message.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
            if (role == MessageRole.ASSISTANT) message.setSkillVersionsJson(skills == null ? "{}" : skills);
            return messages.save(message);
        });
    }

    public record Scope(Long id, UUID conversationId, Long userId, Long storyId, Long chapterId) {
        /** IDs-only references for existing persistence APIs. Never use them for model context. */
        public MangaAgentConversation reference() {
            User user = new User(); user.setId(userId);
            Story story = new Story(); story.setId(storyId); story.setUser(user);
            Chapter chapter = new Chapter(); chapter.setId(chapterId); chapter.setStory(story);
            MangaAgentConversation conversation = new MangaAgentConversation();
            conversation.setId(id); conversation.setConversationUuid(conversationId);
            conversation.setConversationType(AiConversationType.STORY_CHAT);
            conversation.setUser(user); conversation.setStory(story); conversation.setChapter(chapter);
            return conversation;
        }
    }
}
