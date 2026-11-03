package com.artverse.persistence;

import com.artverse.domain.KnowledgeCandidate;
import com.artverse.domain.KnowledgeCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface KnowledgeCandidateRepository extends JpaRepository<KnowledgeCandidate, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from KnowledgeCandidate c where c.id=:id and c.story.id=:storyId and c.user.id=:userId")
    Optional<KnowledgeCandidate> lockForReview(@Param("id") Long id,@Param("storyId") Long storyId,@Param("userId") Long userId);

    List<KnowledgeCandidate> findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
            Long storyId, Long userId, KnowledgeCandidateStatus status);

    List<KnowledgeCandidate> findByStoryIdAndUserIdOrderByCreatedAtDesc(Long storyId, Long userId);

    Optional<KnowledgeCandidate> findByIdAndStoryIdAndUserId(Long id, Long storyId, Long userId);

    Optional<KnowledgeCandidate> findFirstByStoryIdAndProposedHashAndStatus(
            Long storyId, String proposedHash, KnowledgeCandidateStatus status);
}
