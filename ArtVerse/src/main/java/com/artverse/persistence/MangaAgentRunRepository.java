package com.artverse.persistence;

import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MangaAgentRunRepository extends JpaRepository<MangaAgentRun, Long> {

    Optional<MangaAgentRun> findByUserIdAndChapterIdAndRequestId(Long userId, Long chapterId, UUID requestId);

    Optional<MangaAgentRun> findByConversationIdAndRequestId(Long conversationId, UUID requestId);

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

    List<MangaAgentRun> findByStatusAndUpdatedAtBefore(MangaAgentRunStatus status, OffsetDateTime updatedAt);
}
