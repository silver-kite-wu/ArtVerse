package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.persistence.MangaAgentConversationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.artverse.application.workflow.MangaWorkflowExecutionContext.ExecutionClaim;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Short database transactions run on the worker, before any retrieval/model network call. */
@Service
public class StoryChatPreparationService {
    private static final int MAX_HISTORY_MESSAGES = 24;
    private final MangaAgentConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChapterAccessService chapterAccess;
    private final AiConversationService conversationService;
    private final MangaAgentRunService runService;

    @Autowired
    public StoryChatPreparationService(MangaAgentConversationRepository conversations, ChatMessageRepository messages,
                                      ChapterAccessService chapterAccess, AiConversationService conversationService,
                                      MangaAgentRunService runService) {
        this.conversations = conversations;
        this.messages = messages;
        this.chapterAccess = chapterAccess;
        this.conversationService = conversationService;
        this.runService = runService;
    }

    public StoryChatPreparationService(MangaAgentConversationRepository conversations, ChatMessageRepository messages,
                                      ChapterAccessService chapterAccess, AiConversationService conversationService) {
        this(conversations, messages, chapterAccess, conversationService, null);
    }

    @Transactional(readOnly = true)
    public Scope resolve(Long userId, Long chapterId, UUID conversationId) {
        MangaAgentConversation conversation = conversations.findByUserIdAndConversationUuid(userId, conversationId)
                .orElseThrow(() -> new BusinessException(404, "Story chat conversation not found"));
        if (conversation.getConversationType() != AiConversationType.STORY_CHAT
                || conversation.getChapter() == null || !conversation.getChapter().getId().equals(chapterId)) {
            throw new BusinessException(404, "Story chat conversation not found");
        }
        Chapter chapter = chapterAccess.requireVisible(chapterId, userId);
        Story story = chapter.getStory();
        return new Scope(conversation.getId(), conversationId, userId, story.getId(),
                story.getVersion() == null ? 0L : story.getVersion(), chapterId);
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

    @Transactional
    public ChatMessage saveAssistantResult(Scope scope, String content, UUID requestId, String skills,
                                           ChatMessageCompletionStatus status) {
        ChatMessage message = saveMessage(scope, MessageRole.ASSISTANT, content, requestId, skills);
        message.setContent(content);
        message.setCompletionStatus(status);
        message.setSkillVersionsJson(skills == null ? "{}" : skills);
        return messages.save(message);
    }

    @Transactional
    public ChatMessage saveOwnedAssistantResult(Scope scope, String content, UUID requestId, String skills,
                                                ChatMessageCompletionStatus status, ExecutionClaim claim) {
        if (runService == null) throw new BusinessException(503, "运行持久服务未配置");
        runService.lockAutomationExecution(scope.reference(), requestId, claim);
        runService.requireExecutionClaim(scope.reference(), requestId, claim);
        return saveAssistantResult(scope, content, requestId, skills, status);
    }

    @Transactional
    public ChatMessage saveWaitingAssistantResult(Scope scope, String content, UUID requestId, String skills,
                                                  ChatMessageCompletionStatus status, ExecutionClaim claim) {
        if (runService == null) throw new BusinessException(503, "运行持久服务未配置");
        runService.requireWaitingExecutionClaim(scope.reference(), requestId, claim);
        return saveAssistantResult(scope, content, requestId, skills, status);
    }

    @Transactional
    public void completeAssistantResult(Scope scope, String content, UUID requestId, String skills,
                                        ExecutionClaim claim, UUID reviewedArtifact) {
        if (runService == null) throw new BusinessException(503, "运行持久服务未配置");
        runService.lockAutomationExecution(scope.reference(), requestId, claim);
        runService.requireFinalizable(scope.reference(), requestId, claim);
        saveAssistantResult(scope, content, requestId, skills, ChatMessageCompletionStatus.COMPLETE);
        if (reviewedArtifact == null) runService.markSucceeded(scope.reference(), requestId, content, claim);
        else runService.markReviewSummarySucceeded(scope.reference(), requestId, reviewedArtifact, claim.fencingToken(), content);
    }

    public record Scope(Long id, UUID conversationId, Long userId, Long storyId, long storyVersion, Long chapterId) {
        /** Test/helper compatibility; real worker scopes must carry the persisted story version. */
        public Scope(Long id, UUID conversationId, Long userId, Long storyId, Long chapterId) {
            this(id, conversationId, userId, storyId, 0L, chapterId);
        }

        /** IDs-only references for existing persistence APIs. Never use them for model context. */
        public MangaAgentConversation reference() {
            User user = new User(); user.setId(userId);
            Story story = new Story(); story.setId(storyId); story.setVersion(storyVersion); story.setUser(user);
            Chapter chapter = new Chapter(); chapter.setId(chapterId); chapter.setStory(story);
            MangaAgentConversation conversation = new MangaAgentConversation();
            conversation.setId(id); conversation.setConversationUuid(conversationId);
            conversation.setConversationType(AiConversationType.STORY_CHAT);
            conversation.setUser(user); conversation.setStory(story); conversation.setChapter(chapter);
            return conversation;
        }
    }
}
