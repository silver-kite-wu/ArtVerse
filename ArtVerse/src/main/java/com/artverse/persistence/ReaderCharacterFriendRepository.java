package com.artverse.persistence;

import com.artverse.domain.ReaderCharacterFriend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReaderCharacterFriendRepository extends JpaRepository<ReaderCharacterFriend, Long> {
    List<ReaderCharacterFriend> findByReaderIdAndStoryIdOrderByCreatedAtAsc(Long readerId, Long storyId);
    Optional<ReaderCharacterFriend> findByReaderIdAndStoryIdAndCharacterProfileId(Long readerId, Long storyId, Long characterProfileId);
}
