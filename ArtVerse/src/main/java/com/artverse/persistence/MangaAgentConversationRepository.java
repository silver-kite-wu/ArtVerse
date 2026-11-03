package com.artverse.persistence;

import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.AiConversationType;
import com.artverse.domain.AiConversationTitleSource;
import com.artverse.domain.AiConversationTitleState;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MangaAgentConversationRepository extends JpaRepository<MangaAgentConversation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MangaAgentConversation c WHERE c.id = :id")
    Optional<MangaAgentConversation> findByIdForTitleUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MangaAgentConversation c WHERE c.id = :id " +
            "AND c.titleSource = com.artverse.domain.AiConversationTitleSource.DEFAULT")
    Optional<MangaAgentConversation> findDefaultTitleForUpdate(Long id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE MangaAgentConversation c SET c.lastActivityAt = :now, c.updatedAt = :now WHERE c.id = :id")
    int touchActivity(Long id, java.time.OffsetDateTime now);

    @Query("SELECT c FROM MangaAgentConversation c WHERE c.user.id = :userId AND c.chapter.id = :chapterId " +
            "AND c.conversationType = com.artverse.domain.AiConversationType.STORY_CHAT " +
            "AND c.status = com.artverse.domain.MangaAgentConversationStatus.ACTIVE " +
            "AND NOT EXISTS (SELECT m.id FROM ChatMessage m WHERE m.conversation = c) " +
            "AND NOT EXISTS (SELECT r.id FROM MangaAgentRun r WHERE r.conversation = c) " +
            "ORDER BY c.lastActivityAt DESC, c.id DESC")
    List<MangaAgentConversation> findEmptyStoryConversations(Long userId, Long chapterId, Pageable pageable);

    List<MangaAgentConversation> findByUserIdAndChapterIdOrderByUpdatedAtDesc(Long userId, Long chapterId);

    Optional<MangaAgentConversation> findFirstByUserIdAndChapterIdAndStatusOrderByUpdatedAtDesc(
            Long userId,
            Long chapterId,
            MangaAgentConversationStatus status
    );

    Optional<MangaAgentConversation> findByUserIdAndChapterIdAndConversationUuid(
            Long userId,
            Long chapterId,
            UUID conversationUuid
    );

    Optional<MangaAgentConversation> findByUserIdAndChapterIdAndConversationUuidAndConversationType(
            Long userId,
            Long chapterId,
            UUID conversationUuid,
            AiConversationType conversationType
    );

    List<MangaAgentConversation> findByUserIdAndConversationTypeAndStatusOrderByLastActivityAtDesc(
            Long userId, AiConversationType conversationType, MangaAgentConversationStatus status);

    List<MangaAgentConversation> findByUserIdAndChapterIdAndConversationTypeOrderByLastActivityAtDesc(
            Long userId, Long chapterId, AiConversationType conversationType);

    Optional<MangaAgentConversation> findByUserIdAndConversationUuid(Long userId, UUID conversationUuid);

    @Query("SELECT c FROM MangaAgentConversation c JOIN FETCH c.user LEFT JOIN FETCH c.story LEFT JOIN FETCH c.chapter WHERE c.user.id=:userId AND c.conversationUuid=:conversationUuid")
    Optional<MangaAgentConversation> findAutomationConversation(Long userId, UUID conversationUuid);

    Optional<MangaAgentConversation> findFirstByUserIdAndChapterIdAndConversationTypeAndStatus(
            Long userId, Long chapterId, AiConversationType conversationType, MangaAgentConversationStatus status);

    Optional<MangaAgentConversation> findFirstByUserIdAndChapterIdAndConversationTypeAndStatusOrderByLastActivityAtDescIdDesc(
            Long userId, Long chapterId, AiConversationType conversationType, MangaAgentConversationStatus status);

    @Modifying
    @Query("UPDATE MangaAgentConversation c SET c.title = :title, c.titleSource = :source, c.titleState = :state, " +
            "c.titleGenerationStartedAt = null, c.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE c.id = :id AND c.titleSource = com.artverse.domain.AiConversationTitleSource.FALLBACK " +
            "AND c.titleState = com.artverse.domain.AiConversationTitleState.GENERATING")
    int finalizeGeneratedTitle(Long id, String title, AiConversationTitleSource source, AiConversationTitleState state);

    @Modifying
    @Query("UPDATE MangaAgentConversation c SET c.titleState = com.artverse.domain.AiConversationTitleState.FINALIZED, " +
            "c.titleGenerationStartedAt = null WHERE c.titleSource = com.artverse.domain.AiConversationTitleSource.FALLBACK " +
            "AND c.titleState = com.artverse.domain.AiConversationTitleState.GENERATING AND c.titleGenerationStartedAt < :before")
    int finalizeExpiredTitles(java.time.OffsetDateTime before);
}
