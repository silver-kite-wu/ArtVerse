package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MangaAgentConversationRegistry {

    private final MangaAgentConversationRepository conversationRepository;
    private final ChapterAccessService chapterAccessService;

    @Transactional(readOnly = true)
    public List<MangaAgentConversation> list(Long chapterId, User user) {
        chapterAccessService.requireVisible(chapterId, user.getId());
        return conversationRepository.findByUserIdAndChapterIdOrderByUpdatedAtDesc(user.getId(), chapterId);
    }

    @Transactional
    public MangaAgentConversation activeOrCreate(Long chapterId, User user) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        return conversationRepository.findFirstByUserIdAndChapterIdAndStatusOrderByUpdatedAtDesc(
                        user.getId(),
                        chapterId,
                        MangaAgentConversationStatus.ACTIVE
                )
                .orElseGet(() -> conversationRepository.save(newConversation(user, chapter)));
    }

    @Transactional
    public MangaAgentConversation create(Long chapterId, User user) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        conversationRepository.findFirstByUserIdAndChapterIdAndStatusOrderByUpdatedAtDesc(
                user.getId(),
                chapterId,
                MangaAgentConversationStatus.ACTIVE
        ).ifPresent(this::archiveConversation);
        return conversationRepository.save(newConversation(user, chapter));
    }

    @Transactional(readOnly = true)
    public MangaAgentConversation require(Long chapterId, User user, UUID conversationId) {
        if (conversationId == null) {
            throw new BusinessException(400, "conversationId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        return conversationRepository.findByUserIdAndChapterIdAndConversationUuid(user.getId(), chapterId, conversationId)
                .orElseThrow(() -> new BusinessException(404, "Agent conversation not found"));
    }

    @Transactional
    public MangaAgentConversation archive(Long chapterId, User user, UUID conversationId) {
        MangaAgentConversation conversation = require(chapterId, user, conversationId);
        archiveConversation(conversation);
        return conversationRepository.save(conversation);
    }

    private MangaAgentConversation newConversation(User user, Chapter chapter) {
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setUser(user);
        conversation.setStory(chapter.getStory());
        conversation.setChapter(chapter);
        conversation.setTitle("新对话");
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
        return conversation;
    }

    private void archiveConversation(MangaAgentConversation conversation) {
        if (conversation.getStatus() == MangaAgentConversationStatus.ARCHIVED) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        conversation.setStatus(MangaAgentConversationStatus.ARCHIVED);
        conversation.setArchivedAt(now);
        conversation.setUpdatedAt(now);
    }
}
