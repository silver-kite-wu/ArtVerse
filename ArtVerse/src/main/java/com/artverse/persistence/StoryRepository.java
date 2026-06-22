package com.artverse.persistence;

import com.artverse.domain.Story;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {

    @Query("SELECT DISTINCT s FROM Story s LEFT JOIN FETCH s.chapters LEFT JOIN FETCH s.assetGroups ORDER BY s.createdAt DESC")
    List<Story> findAllWithChaptersAndGroups();

    @Query("SELECT DISTINCT s FROM Story s LEFT JOIN FETCH s.chapters LEFT JOIN FETCH s.assetGroups WHERE s.id = :id")
    Optional<Story> findByIdWithChaptersAndGroups(Long id);

    @Query("SELECT DISTINCT s FROM Story s LEFT JOIN FETCH s.chapters LEFT JOIN FETCH s.assetGroups WHERE s.id = :id AND s.user.id = :userId")
    Optional<Story> findByIdAndUserIdWithChaptersAndGroups(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT DISTINCT s FROM Story s LEFT JOIN FETCH s.chapters LEFT JOIN FETCH s.assetGroups WHERE s.user.id = :userId ORDER BY s.createdAt DESC")
    List<Story> findByUserIdWithChaptersAndGroups(@Param("userId") Long userId);

    @Query("SELECT s FROM Story s LEFT JOIN FETCH s.chapters WHERE s.user.id = :userId ORDER BY s.createdAt DESC")
    List<Story> findByUserIdWithChapters(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Story s SET s.mangaStyle = :mangaStyle WHERE s.id = :id")
    void setMangaStyle(Long id, String mangaStyle);

    @Query("SELECT s FROM Story s WHERE s.isPublished = true ORDER BY s.publishedAt DESC")
    org.springframework.data.domain.Page<Story> findPublishedStories(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT s FROM Story s WHERE s.isPublished = true AND LOWER(s.title) LIKE LOWER(CONCAT(CHAR(37), :search, CHAR(37))) ORDER BY s.publishedAt DESC")
    org.springframework.data.domain.Page<Story> searchPublishedStories(@Param("search") String search, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT s FROM Story s WHERE s.isPublished = true AND s.id = :id")
    Optional<Story> findPublishedById(@Param("id") Long id);
}
