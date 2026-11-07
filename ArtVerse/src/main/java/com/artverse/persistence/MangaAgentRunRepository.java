package com.artverse.persistence;

import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MangaAgentRunRepository extends JpaRepository<MangaAgentRun, Long> {

    boolean existsByConversationIdAndStatusIn(Long conversationId, Collection<MangaAgentRunStatus> statuses);

    Optional<MangaAgentRun> findByUserIdAndChapterIdAndRequestId(Long userId, Long chapterId, UUID requestId);

    Optional<MangaAgentRun> findByConversationIdAndRequestId(Long conversationId, UUID requestId);

    List<MangaAgentRun> findByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from MangaAgentRun run where run.user.id = :userId and run.chapter.id = :chapterId " +
            "and run.requestId = :requestId")
    Optional<MangaAgentRun> findForUpdate(@Param("userId") Long userId,
                                          @Param("chapterId") Long chapterId,
                                          @Param("requestId") UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from MangaAgentRun run where run.conversation.id = :conversationId " +
            "and run.requestId = :requestId")
    Optional<MangaAgentRun> findForUpdate(@Param("conversationId") Long conversationId,
                                          @Param("requestId") UUID requestId);

    List<MangaAgentRun> findByUserIdAndChapterIdAndStatusInOrderByUpdatedAtDesc(
            Long userId,
            Long chapterId,
            Collection<MangaAgentRunStatus> statuses,
            Pageable pageable
    );

    List<MangaAgentRun> findByConversationIdAndStatusInOrderByUpdatedAtDesc(
            Long conversationId,
            Collection<MangaAgentRunStatus> statuses,
            Pageable pageable
    );

    List<MangaAgentRun> findByStatusAndLastProgressAtBefore(MangaAgentRunStatus status, OffsetDateTime lastProgressAt);

    @Query("select run.id from MangaAgentRun run where run.status = :status and run.lastProgressAt < :before")
    List<Long> findStalledCandidateIds(@Param("status") MangaAgentRunStatus status, @Param("before") OffsetDateTime before);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from MangaAgentRun run where run.id = :id")
    Optional<MangaAgentRun> findForUpdateById(@Param("id") Long id);

    /**
     * 只更新 updated_at，避免整行 UPDATE 与并发中的等待/执行权事务产生丢失更新。
     */
    @Modifying
    @Query("update MangaAgentRun run set run.updatedAt = :updatedAt where run.id = :id")
    int touchUpdatedAt(@Param("id") Long id, @Param("updatedAt") OffsetDateTime updatedAt);
}
