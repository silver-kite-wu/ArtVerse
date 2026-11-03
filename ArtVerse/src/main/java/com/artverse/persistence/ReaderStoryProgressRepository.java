package com.artverse.persistence;

import com.artverse.domain.ReaderStoryProgress;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReaderStoryProgressRepository extends JpaRepository<ReaderStoryProgress, Long> {
    Optional<ReaderStoryProgress> findByReaderIdAndStoryId(Long readerId, Long storyId);

    @Modifying
    @Query(value = """
            INSERT INTO reader_story_progress (reader_id, story_id, max_chapter_number, updated_at)
            VALUES (:readerId, :storyId, :chapterNumber, CURRENT_TIMESTAMP)
            ON CONFLICT (reader_id, story_id) DO UPDATE
            SET max_chapter_number = GREATEST(reader_story_progress.max_chapter_number, EXCLUDED.max_chapter_number),
                updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int upsertMaxChapter(@Param("readerId") Long readerId,
                         @Param("storyId") Long storyId,
                         @Param("chapterNumber") int chapterNumber);
}
