package com.artverse.persistence;

import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MangaAgentConversationRepository extends JpaRepository<MangaAgentConversation, Long> {

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
}
