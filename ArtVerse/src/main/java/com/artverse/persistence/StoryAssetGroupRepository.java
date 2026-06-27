package com.artverse.persistence;

import com.artverse.domain.StoryAssetGroup;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoryAssetGroupRepository extends JpaRepository<StoryAssetGroup, Long> {

    List<StoryAssetGroup> findByStoryIdOrderByIdAsc(Long storyId);

    @Query("SELECT g FROM StoryAssetGroup g WHERE g.id = :id AND g.story.user.id = :userId")
    Optional<StoryAssetGroup> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT DISTINCT g FROM StoryAssetGroup g LEFT JOIN FETCH g.characters WHERE g.id = :id AND g.story.user.id = :userId")
    Optional<StoryAssetGroup> findByIdAndUserIdWithCharacters(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT g FROM StoryAssetGroup g WHERE g.story.id = :storyId AND g.story.user.id = :userId ORDER BY g.id ASC")
    List<StoryAssetGroup> findByStoryIdAndUserIdOrderByIdAsc(@Param("storyId") Long storyId, @Param("userId") Long userId);
}
