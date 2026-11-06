package com.artverse.persistence;

import com.artverse.domain.MangaAgentRunArtifact;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MangaAgentRunArtifactRepository extends JpaRepository<MangaAgentRunArtifact, Long> {

    Optional<MangaAgentRunArtifact> findFirstByRunIdAndChecksumOrderByCreatedAtDesc(Long runId, String checksum);

    long countByRunIdAndArtifactType(Long runId, String artifactType);

    Optional<MangaAgentRunArtifact> findByArtifactUuidAndRunUserIdAndRunChapterId(UUID artifactUuid, Long userId, Long chapterId);

    List<MangaAgentRunArtifact> findByRunIdOrderByCreatedAtAsc(Long runId);

    @Query("select coalesce(max(a.id),0) from MangaAgentRunArtifact a where a.run.id=:run")
    long highestId(@Param("run") Long runId);

    @Query("select a.id,a.status,a.checksum,a.updatedAt from MangaAgentRunArtifact a where a.run.id=:run "
            + "and a.artifactType in ('NOVEL_CONTENT_DRAFT','STORYBOARD_DRAFT','SETTINGS_CHANGE','CHARACTER_CHANGE','IMAGE_PROPOSAL','IMAGE_RECOVERY') "
            + "and (cast(:type as String) is null or a.artifactType=:type) "
            + "and (cast(:status as String) is null or a.status=:status) order by a.id")
    List<Object[]> agentSnapshot(@Param("run") Long runId, @Param("type") String type, @Param("status") String status);

    @Query("select a from MangaAgentRunArtifact a where a.run.id=:run and a.id<=:upper "
            + "and a.artifactType in ('NOVEL_CONTENT_DRAFT','STORYBOARD_DRAFT','SETTINGS_CHANGE','CHARACTER_CHANGE','IMAGE_PROPOSAL','IMAGE_RECOVERY') "
            + "and (cast(:before as Long) is null or a.id<:before) and (cast(:type as String) is null or a.artifactType=:type) "
            + "and (cast(:status as String) is null or a.status=:status) order by a.id desc")
    List<MangaAgentRunArtifact> agentPage(@Param("run") Long runId, @Param("upper") long upper,
            @Param("before") Long before, @Param("type") String type, @Param("status") String status,
            org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select artifact from MangaAgentRunArtifact artifact "
            + "where artifact.artifactUuid = :artifactUuid "
            + "and artifact.run.user.id = :userId and artifact.run.chapter.id = :chapterId")
    Optional<MangaAgentRunArtifact> findForCommit(
            @Param("artifactUuid") UUID artifactUuid,
            @Param("userId") Long userId,
            @Param("chapterId") Long chapterId);
}
