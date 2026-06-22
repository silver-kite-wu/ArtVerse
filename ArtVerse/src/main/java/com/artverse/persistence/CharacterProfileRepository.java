package com.artverse.persistence;

import com.artverse.domain.CharacterProfile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CharacterProfileRepository extends JpaRepository<CharacterProfile, Long> {

    List<CharacterProfile> findByStoryIdOrderByIdAsc(Long storyId);

    void deleteByStoryIdAndId(Long storyId, Long id);

    @Query("SELECT c FROM CharacterProfile c WHERE c.id = :id AND c.story.user.id = :userId")
    Optional<CharacterProfile> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT c FROM CharacterProfile c WHERE c.story.id = :storyId AND c.story.user.id = :userId ORDER BY c.id ASC")
    List<CharacterProfile> findByStoryIdAndUserIdOrderByIdAsc(@Param("storyId") Long storyId, @Param("userId") Long userId);
}
