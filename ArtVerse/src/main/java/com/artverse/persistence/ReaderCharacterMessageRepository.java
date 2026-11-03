package com.artverse.persistence;

import com.artverse.domain.ReaderCharacterMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReaderCharacterMessageRepository extends JpaRepository<ReaderCharacterMessage, Long> {
    List<ReaderCharacterMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);
    List<ReaderCharacterMessage> findByConversationIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(Long conversationId);
    Optional<ReaderCharacterMessage> findByConversationIdAndClientMessageId(Long conversationId, String clientMessageId);
}
