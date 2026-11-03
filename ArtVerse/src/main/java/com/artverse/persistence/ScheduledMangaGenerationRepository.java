package com.artverse.persistence;

import com.artverse.domain.ScheduledMangaGeneration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledMangaGenerationRepository extends JpaRepository<ScheduledMangaGeneration, UUID> {
    Optional<ScheduledMangaGeneration> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    Optional<ScheduledMangaGeneration> findByIdAndUserId(UUID id, Long userId);
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value="INSERT INTO scheduled_manga_generation (id,user_id,chapter_id,requested_at,timezone_id,state,chapter_version,model_config_id,model_name,scenes_snapshot,input_snapshot,snapshot_hash,prompt_version,idempotency_key) VALUES (:id,:userId,:chapterId,:requestedAt,:timezone,:state,:chapterVersion,:configId,:model,:scenes,:snapshot,:snapshotHash,:promptVersion,:idempotencyKey) ON CONFLICT DO NOTHING",nativeQuery=true)
    int insertIfAbsent(@Param("id") UUID id,@Param("userId") Long userId,@Param("chapterId") Long chapterId,
                       @Param("requestedAt") OffsetDateTime requestedAt,@Param("timezone") String timezone,
                       @Param("state") String state,@Param("chapterVersion") Long chapterVersion,
                       @Param("configId") Long configId,@Param("model") String model,@Param("scenes") String scenes,
                       @Param("snapshot") String snapshot,@Param("snapshotHash") String snapshotHash,
                       @Param("promptVersion") String promptVersion,@Param("idempotencyKey") String idempotencyKey);
    @Query("select (count(t)>0) from ScheduledMangaGeneration t where t.chapter.id=:chapterId and t.state in (com.artverse.domain.ScheduledMangaGeneration.State.SCHEDULED, com.artverse.domain.ScheduledMangaGeneration.State.RUNNING)")
    boolean existsByChapterIdAndActive(@Param("chapterId") Long chapterId);
    @Query("select count(t) from ScheduledMangaGeneration t where t.user.id=:userId and t.state in (com.artverse.domain.ScheduledMangaGeneration.State.SCHEDULED,com.artverse.domain.ScheduledMangaGeneration.State.RUNNING)")
    long countActiveForUser(@Param("userId")Long userId);
    List<ScheduledMangaGeneration> findByUserIdOrderByCreatedAtDesc(Long userId);
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value="UPDATE scheduled_manga_generation t SET state='RUNNING', lease_owner=:owner, lease_until=:until, fencing_token=fencing_token+1, started_at=COALESCE(started_at,now()) WHERE id=:id AND ((state='SCHEDULED' AND requested_at<=:now) OR (state='RUNNING' AND lease_until<:now)) AND (t.automation_id IS NULL OR EXISTS(SELECT 1 FROM automation_run r WHERE r.id=t.automation_run_id AND r.state IN ('RUNNING','CANCEL_REQUESTED')))", nativeQuery=true)
    int claim(@Param("id") UUID id, @Param("owner") String owner, @Param("until") OffsetDateTime until, @Param("now") OffsetDateTime now);
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value="UPDATE scheduled_manga_generation SET lease_until=:until WHERE id=:id AND state='RUNNING' AND lease_owner=:owner AND fencing_token=:fence AND lease_until>:now",nativeQuery=true)
    int renew(@Param("id") UUID id,@Param("owner") String owner,@Param("fence") Long fence,@Param("until") OffsetDateTime until,@Param("now") OffsetDateTime now);
    @Query(value="SELECT count(*)>0 FROM scheduled_manga_generation WHERE id=:id AND state='RUNNING' AND lease_owner=:owner AND fencing_token=:fence AND lease_until>:now",nativeQuery=true)
    boolean isCurrentLease(@Param("id") UUID id,@Param("owner") String owner,@Param("fence") Long fence,@Param("now") OffsetDateTime now);
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value="UPDATE scheduled_manga_generation SET state='CANCELLED', completed_at=:now, lease_owner=NULL, lease_until=NULL WHERE id=:id AND state='SCHEDULED'",nativeQuery=true)
    int cancelIfScheduled(@Param("id") UUID id,@Param("now") OffsetDateTime now);
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value="UPDATE scheduled_manga_generation SET state='SCHEDULED', requested_at=:now, completed_at=NULL, error_code=NULL, error_message=NULL, lease_owner=NULL, lease_until=NULL WHERE id=:id AND user_id=:userId AND state IN ('FAILED','PARTIAL_FAILED')",nativeQuery=true)
    int retryFailedIfFinal(@Param("id") UUID id,@Param("userId") Long userId,@Param("now") OffsetDateTime now);
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value="UPDATE scheduled_manga_generation SET state=:state, error_code=:code, error_message=:message, completed_at=:completed, requested_at=COALESCE(:requested,requested_at), lease_owner=NULL, lease_until=NULL WHERE id=:id AND state='RUNNING' AND lease_owner=:owner AND fencing_token=:fence",nativeQuery=true)
    int updateOwnedState(@Param("id") UUID id,@Param("owner") String owner,@Param("fence") Long fence,
                         @Param("state") String state,@Param("code") String code,@Param("message") String message,
                         @Param("completed") OffsetDateTime completed,@Param("requested") OffsetDateTime requested);
    @Query(value="SELECT t.id FROM scheduled_manga_generation t WHERE ((state='SCHEDULED' AND requested_at<=:now) OR (state='RUNNING' AND lease_until<:now)) AND (t.automation_id IS NULL OR EXISTS(SELECT 1 FROM automation_run r WHERE r.id=t.automation_run_id AND r.state IN ('RUNNING','CANCEL_REQUESTED'))) ORDER BY requested_at LIMIT :limit", nativeQuery=true)
    List<UUID> findDueIds(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}
