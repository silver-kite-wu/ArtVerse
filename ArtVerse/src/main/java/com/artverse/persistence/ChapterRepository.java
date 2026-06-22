package com.artverse.persistence;

import com.artverse.domain.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterRepository extends JpaRepository<Chapter, Long> {

    List<Chapter> findByStoryIdOrderByChapterNumberAsc(Long storyId);

    Optional<Chapter> findByStoryIdAndChapterNumber(Long storyId, Integer chapterNumber);

    @Query("SELECT COALESCE(MAX(c.chapterNumber), 0) FROM Chapter c WHERE c.story.id = :storyId")
    int findMaxChapterNumberByStoryId(@Param("storyId") Long storyId);

    @Query("SELECT c FROM Chapter c WHERE c.story.id = :storyId AND c.chapterNumber <= :maxChapter ORDER BY c.chapterNumber ASC")
    List<Chapter> findByStoryIdUpToChapter(@Param("storyId") Long storyId, @Param("maxChapter") int maxChapter);

    @Query("SELECT DISTINCT c FROM Chapter c LEFT JOIN FETCH c.images LEFT JOIN FETCH c.messages WHERE c.id = :id")
    Optional<Chapter> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT DISTINCT c FROM Chapter c LEFT JOIN FETCH c.images LEFT JOIN FETCH c.messages WHERE c.id = :id AND c.story.user.id = :userId")
    Optional<Chapter> findByIdWithDetailsAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT DISTINCT c FROM Chapter c JOIN FETCH c.story s LEFT JOIN FETCH s.user LEFT JOIN FETCH c.assetGroup LEFT JOIN FETCH c.messages WHERE c.id = :id")
    Optional<Chapter> findByIdForIdempotency(@Param("id") Long id);

    @Query("SELECT DISTINCT c FROM Chapter c JOIN FETCH c.story s LEFT JOIN FETCH s.user LEFT JOIN FETCH c.assetGroup LEFT JOIN FETCH c.messages WHERE c.id = :id AND s.user.id = :userId")
    Optional<Chapter> findByIdForIdempotencyAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT DISTINCT c FROM Chapter c LEFT JOIN FETCH c.images LEFT JOIN FETCH c.messages WHERE c.story.id = :storyId ORDER BY c.chapterNumber ASC")
    List<Chapter> findByStoryIdWithDetails(@Param("storyId") Long storyId);

    @Query("SELECT DISTINCT c FROM Chapter c LEFT JOIN FETCH c.images LEFT JOIN FETCH c.messages WHERE c.story.id = :storyId AND c.story.user.id = :userId ORDER BY c.chapterNumber ASC")
    List<Chapter> findByStoryIdWithDetailsAndUserId(@Param("storyId") Long storyId, @Param("userId") Long userId);

    @Query("SELECT DISTINCT c FROM Chapter c WHERE c.story.id = :storyId AND c.story.user.id = :userId ORDER BY c.chapterNumber ASC")
    List<Chapter> findByStoryIdAndUserIdOrderByChapterNumberAsc(@Param("storyId") Long storyId, @Param("userId") Long userId);

    @Query("SELECT COALESCE(MAX(c.chapterNumber), 0) FROM Chapter c WHERE c.story.id = :storyId AND c.story.user.id = :userId")
    int findMaxChapterNumberByStoryIdAndUserId(@Param("storyId") Long storyId, @Param("userId") Long userId);
}
