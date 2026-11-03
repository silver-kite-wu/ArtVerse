package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.MangaAgentConversationRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.artverse.persistence.ChapterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Shared metadata lifecycle for every persisted AI conversation. */
@Service
@RequiredArgsConstructor
public class AiConversationService {
    public static final String DEFAULT_TITLE = "新会话";
    public static final int AUTO_TITLE_MAX_CODE_POINTS = 24;
    private final MangaAgentConversationRepository repository;
    private final ChapterAccessService chapterAccessService;
    private final MangaAgentRunRepository runRepository;
    private final ChapterRepository chapterRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public MangaAgentConversation automationConversation(User user,AiConversationType type,Long chapterId,UUID runId,String title) {
        var existing=repository.findAutomationConversation(user.getId(),runId);
        if(existing.isPresent())return existing.get();
        MangaAgentConversation c=new MangaAgentConversation();
        c.setUser(user);c.setConversationUuid(runId);c.setConversationType(type);
        c.setStatus(MangaAgentConversationStatus.ARCHIVED);c.setArchivedAt(OffsetDateTime.now());
        c.setLegacyImportKey("automation:"+runId);
        if(type!=AiConversationType.IMAGE_GEN) {
            Chapter chapter=chapterAccessService.requireVisible(chapterId,user.getId());
            c.setChapter(chapter);c.setStory(chapter.getStory());
        }
        applyInitialTitle(c,title);
        return repository.save(c);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentConversation> list(User user, AiConversationType type, Long chapterId, boolean includeArchived) {
        if (type == AiConversationType.IMAGE_GEN) {
            return includeArchived
                    ? repository.findByUserIdAndConversationTypeAndStatusOrderByLastActivityAtDesc(user.getId(), type, MangaAgentConversationStatus.ACTIVE)
                    : repository.findByUserIdAndConversationTypeAndStatusOrderByLastActivityAtDesc(user.getId(), type, MangaAgentConversationStatus.ACTIVE);
        }
        if (chapterId == null) throw new BusinessException(400, "chapterId is required for this conversation type");
        chapterAccessService.requireVisible(chapterId, user.getId());
        return repository.findByUserIdAndChapterIdAndConversationTypeOrderByLastActivityAtDesc(user.getId(), chapterId, type)
                .stream().filter(c -> includeArchived || c.getStatus() == MangaAgentConversationStatus.ACTIVE).toList();
    }

    @Transactional
    public MangaAgentConversation create(User user, AiConversationType type, Long chapterId, String requestedTitle) {
        if (type == AiConversationType.STORY_CHAT) {
            if (chapterId == null) throw new BusinessException(400, "chapterId is required for story chat");
            Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
            // Serialize create requests even when there is no conversation row to lock yet.
            chapterRepository.findByIdAndUserIdForUpdate(chapterId, user.getId())
                    .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
            var empty = repository.findEmptyStoryConversations(user.getId(), chapterId, PageRequest.of(0, 1));
            if (!empty.isEmpty()) {
                MangaAgentConversation draft = empty.getFirst();
                draft.setLastActivityAt(OffsetDateTime.now());
                return repository.save(draft);
            }
            MangaAgentConversation conversation = new MangaAgentConversation();
            conversation.setUser(user);
            conversation.setStory(chapter.getStory());
            conversation.setChapter(chapter);
            conversation.setConversationType(AiConversationType.STORY_CHAT);
            conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
            applyInitialTitle(conversation, requestedTitle);
            return repository.save(conversation);
        }
        MangaAgentConversation c = new MangaAgentConversation();
        c.setUser(user);
        c.setConversationType(type);
        c.setStatus(MangaAgentConversationStatus.ACTIVE);
        applyInitialTitle(c, requestedTitle);
        return repository.save(c);
    }

    @Transactional
    public MangaAgentConversation storyConversation(User user, Long chapterId) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        return repository.findFirstByUserIdAndChapterIdAndConversationTypeAndStatusOrderByLastActivityAtDescIdDesc(
                        user.getId(), chapterId, AiConversationType.STORY_CHAT, MangaAgentConversationStatus.ACTIVE)
                .orElseGet(() -> {
                    MangaAgentConversation c = new MangaAgentConversation();
                    c.setUser(user); c.setStory(chapter.getStory()); c.setChapter(chapter);
                    c.setConversationType(AiConversationType.STORY_CHAT);
                    c.setStatus(MangaAgentConversationStatus.ACTIVE);
                    c.setTitle(DEFAULT_TITLE); c.setTitleSource(AiConversationTitleSource.DEFAULT);
                    c.setTitleState(AiConversationTitleState.WAITING);
                    return repository.save(c);
                });
    }

    @Transactional
    public MangaAgentConversation activeImageConversation(User user) {
        return repository.findByUserIdAndConversationTypeAndStatusOrderByLastActivityAtDesc(
                        user.getId(), AiConversationType.IMAGE_GEN, MangaAgentConversationStatus.ACTIVE)
                .stream().findFirst().orElseGet(() -> create(user, AiConversationType.IMAGE_GEN, null, null));
    }

    @Transactional(readOnly = true)
    public MangaAgentConversation require(User user, UUID id) {
        return repository.findByUserIdAndConversationUuid(user.getId(), id)
                .orElseThrow(() -> new BusinessException(404, "AI conversation not found"));
    }

    @Transactional
    public MangaAgentConversation rename(User user, UUID id, String title) {
        MangaAgentConversation owned = require(user, id);
        MangaAgentConversation c = repository.findByIdForTitleUpdate(owned.getId()).orElseThrow();
        c.setTitle(normalizeManualTitle(title));
        c.setTitleSource(AiConversationTitleSource.USER);
        c.setTitleState(AiConversationTitleState.FINALIZED);
        c.setTitleGenerationStartedAt(null);
        return repository.save(c);
    }

    @Transactional
    public void delete(User user, UUID id) {
        MangaAgentConversation c = require(user, id);
        if (c.getConversationType() != AiConversationType.STORY_CHAT) {
            throw new BusinessException(400, "Only story chat conversations can be deleted here");
        }
        chapterAccessService.requireVisible(c.getChapter().getId(), user.getId());
        if (runRepository.existsByConversationIdAndStatusIn(c.getId(),
                List.of(MangaAgentRunStatus.RUNNING, MangaAgentRunStatus.WAITING_USER))) {
            throw new BusinessException(409, "请先停止生成或处理待确认的草稿，再删除会话");
        }
        // Foreign keys cascade to this conversation's messages, proposals and completed runs.
        repository.delete(c);
        repository.flush();
    }

    @Transactional
    public MangaAgentConversation archive(User user, UUID id) {
        MangaAgentConversation c = require(user, id);
        if (c.getConversationType() == AiConversationType.STORY_CHAT) {
            throw new BusinessException(409, "Story chat is managed by its chapter");
        }
        c.setStatus(MangaAgentConversationStatus.ARCHIVED);
        c.setArchivedAt(OffsetDateTime.now());
        return repository.save(c);
    }

    @Transactional
    public void autoTitle(MangaAgentConversation c, String input) {
        if (input == null || input.isBlank()) return;
        // Read current metadata under a lock: callers can hold detached/stale conversation objects.
        MangaAgentConversation current = repository.findDefaultTitleForUpdate(c.getId()).orElse(null);
        if (current == null || current.getTitleSource() != AiConversationTitleSource.DEFAULT) return;
        String fallback = fallbackTitle(input);
        current.setTitle(fallback);
        current.setTitleSource(AiConversationTitleSource.FALLBACK);
        current.setTitleState(AiConversationTitleState.GENERATING);
        current.setTitleGenerationStartedAt(OffsetDateTime.now());
        events.publishEvent(new ConversationTitleRequested(current.getId(), current.getUser().getId(),
                limitCodePoints(input, 2000), fallback));
    }

    @Transactional
    public void touch(MangaAgentConversation c) {
        repository.touchActivity(c.getId(), OffsetDateTime.now());
    }

    private void applyInitialTitle(MangaAgentConversation c, String requestedTitle) {
        if (requestedTitle == null || requestedTitle.isBlank()) {
            c.setTitle(DEFAULT_TITLE); c.setTitleSource(AiConversationTitleSource.DEFAULT); c.setTitleState(AiConversationTitleState.WAITING);
        } else {
            c.setTitle(normalizeManualTitle(requestedTitle)); c.setTitleSource(AiConversationTitleSource.USER); c.setTitleState(AiConversationTitleState.FINALIZED);
        }
    }

    public static String fallbackTitle(String input) {
        String clean = input == null ? "" : input.replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ").strip();
        if (clean.isBlank()) return DEFAULT_TITLE;
        return limitCodePoints(clean, AUTO_TITLE_MAX_CODE_POINTS);
    }

    public static String limitCodePoints(String value, int limit) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(limit, value.codePointCount(0, value.length()))));
    }

    private static String normalizeManualTitle(String title) {
        String value = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").replaceAll("\\s+", " ").strip();
        if (value.isBlank()) throw new BusinessException(400, "Conversation title cannot be blank");
        if (value.codePointCount(0, value.length()) > 120) throw new BusinessException(400, "Conversation title must be at most 120 characters");
        return value;
    }
}
