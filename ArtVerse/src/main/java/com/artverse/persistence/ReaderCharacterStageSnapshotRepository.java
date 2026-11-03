package com.artverse.persistence;

import com.artverse.domain.ReaderCharacterStageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReaderCharacterStageSnapshotRepository extends JpaRepository<ReaderCharacterStageSnapshot, Long> {
    Optional<ReaderCharacterStageSnapshot> findByStoryIdAndCharacterProfileIdAndAnchorChapterId(
            Long storyId, Long characterProfileId, Long anchorChapterId);
}
