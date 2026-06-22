package com.artverse.persistence;

import com.artverse.domain.MangaAgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MangaAgentMessageRepository extends JpaRepository<MangaAgentMessage, Long> {

    List<MangaAgentMessage> findByUserIdAndChapterIdOrderByCreatedAtAsc(Long userId, Long chapterId);

    List<MangaAgentMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    Optional<MangaAgentMessage> findByUserIdAndChapterIdAndRequestIdAndRole(Long userId, Long chapterId, UUID requestId,
                                                                            com.artverse.domain.MessageRole role);

    Optional<MangaAgentMessage> findByConversationIdAndRequestIdAndRole(Long conversationId, UUID requestId,
                                                                        com.artverse.domain.MessageRole role);
}
