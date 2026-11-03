package com.artverse.persistence;

import com.artverse.domain.ImageGenRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public interface ImageGenRecordRepository extends JpaRepository<ImageGenRecord, Long> {
    java.util.Optional<ImageGenRecord> findByUserIdAndRequestId(Long userId, java.util.UUID requestId);
    java.util.Optional<ImageGenRecord> findByRootRequestIdAndRequestId(java.util.UUID rootRequestId, java.util.UUID requestId);
    java.util.List<ImageGenRecord> findByUserIdAndRootRequestIdOrderByImageNumberAsc(Long userId, java.util.UUID rootRequestId);
    Page<ImageGenRecord> findByAttemptDecision(String attemptDecision, Pageable pageable);
    java.util.Optional<ImageGenRecord> findFirstByChapterIdAndImageNumberAndAttemptDecisionOrderByIdAsc(Long chapterId, Integer imageNumber, String attemptDecision);
    java.util.Optional<ImageGenRecord> findFirstByUserIdAndChapterIdAndImageNumberAndRequestHashAndStatusOrderByIdAsc(Long userId, Long chapterId, Integer imageNumber, String requestHash, com.artverse.domain.ImageGenStatus status);
    java.util.Optional<ImageGenRecord> findByAutomationTargetKey(String key);

    @Query("SELECT r FROM ImageGenRecord r WHERE r.user.id = :userId AND r.isDeleted = false AND (r.requestId IS NULL OR r.operation = 'STANDALONE') ORDER BY r.createdAt DESC")
    Page<ImageGenRecord> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT r FROM ImageGenRecord r WHERE r.user.id = :userId AND r.conversation.conversationUuid = :conversationId " +
            "AND r.isDeleted = false AND (r.requestId IS NULL OR r.operation = 'STANDALONE') ORDER BY r.createdAt DESC")
    Page<ImageGenRecord> findByUserIdAndConversationUuid(Long userId, java.util.UUID conversationId, Pageable pageable);

    @Modifying
    @Query("UPDATE ImageGenRecord r SET r.status = com.artverse.domain.ImageGenStatus.FAILED, r.failureReason = :reason, r.completedAt = :now " +
            "WHERE r.user.id = :userId AND r.status = com.artverse.domain.ImageGenStatus.RUNNING AND r.createdAt < :cutoff AND r.automationTargetKey IS NULL AND r.requestId IS NULL")
    int markExpiredRunningAsFailed(Long userId, OffsetDateTime cutoff, OffsetDateTime now, String reason);
}
