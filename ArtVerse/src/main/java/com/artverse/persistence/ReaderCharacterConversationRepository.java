package com.artverse.persistence;

import com.artverse.domain.ReaderCharacterConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReaderCharacterConversationRepository extends JpaRepository<ReaderCharacterConversation, Long> {
    Optional<ReaderCharacterConversation> findByConversationUuidAndReaderId(UUID conversationUuid, Long readerId);
    Optional<ReaderCharacterConversation> findByReaderIdAndStoryIdAndCharacterProfileIdAndConversationType(
            Long readerId, Long storyId, Long characterProfileId, String conversationType);

    List<ReaderCharacterConversation> findByReaderIdAndStoryIdAndConversationTypeOrderByLastMessageAtDesc(
            Long readerId, Long storyId, String conversationType);
}
