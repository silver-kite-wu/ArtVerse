package com.artverse.persistence;

import com.artverse.domain.ScheduledMangaPanel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ScheduledMangaPanelRepository extends JpaRepository<ScheduledMangaPanel, Long> {
    List<ScheduledMangaPanel> findByTaskIdOrderByImageNumberAsc(java.util.UUID taskId);
    long countByTaskIdAndState(java.util.UUID taskId, ScheduledMangaPanel.State state);
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value="UPDATE scheduled_manga_panel SET state='PENDING',attempt_count=0,next_attempt_at=NULL,error_code=NULL,error_message=NULL,lease_owner=NULL,lease_until=NULL WHERE task_id=:taskId AND state='FAILED'",nativeQuery=true)
    int resetFailedForRetry(@org.springframework.data.repository.query.Param("taskId") java.util.UUID taskId);
    @Query("select p from ScheduledMangaPanel p where p.task.id=:taskId and (p.state in (com.artverse.domain.ScheduledMangaPanel.State.PENDING, com.artverse.domain.ScheduledMangaPanel.State.RETRY_WAIT) and (p.nextAttemptAt is null or p.nextAttemptAt<=CURRENT_TIMESTAMP)) order by p.imageNumber")
    List<ScheduledMangaPanel> findReady(@Param("taskId") java.util.UUID taskId);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value="UPDATE scheduled_manga_panel SET state='SUCCEEDED',result_image_id=:imageId,lease_owner=NULL,lease_until=NULL,error_code=NULL,error_message=NULL WHERE id=:panelId AND state='RUNNING' AND lease_owner=:owner AND fencing_token=:fence",nativeQuery=true)
    int completeIfOwned(@Param("panelId")Long panelId,@Param("owner")String owner,@Param("fence")Long fence,@Param("imageId")Long imageId);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value="UPDATE scheduled_manga_panel p SET state='RUNNING',attempt_count=attempt_count+1,lease_owner=:panelOwner,lease_until=:until,fencing_token=:fence,next_attempt_at=NULL FROM scheduled_manga_generation t WHERE p.id=:panelId AND p.task_id=t.id AND t.id=:taskId AND t.state='RUNNING' AND t.lease_owner=:taskOwner AND t.fencing_token=:fence AND t.lease_until>:now AND p.state IN ('PENDING','RETRY_WAIT') AND (p.next_attempt_at IS NULL OR p.next_attempt_at<=:now)",nativeQuery=true)
    int startIfCurrent(@Param("panelId")Long panelId,@Param("taskId")java.util.UUID taskId,
                       @Param("taskOwner")String taskOwner,@Param("panelOwner")String panelOwner,
                       @Param("fence")Long fence,@Param("now")java.time.OffsetDateTime now,
                       @Param("until")java.time.OffsetDateTime until);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value="UPDATE scheduled_manga_panel SET state=:state,error_code=:code,error_message=:message,next_attempt_at=:nextAttempt,lease_owner=NULL,lease_until=NULL WHERE id=:panelId AND state='RUNNING' AND lease_owner=:owner AND fencing_token=:fence",nativeQuery=true)
    int updateOwnedState(@Param("panelId")Long panelId,@Param("owner")String owner,@Param("fence")Long fence,
                         @Param("state")String state,@Param("code")String code,@Param("message")String message,
                         @Param("nextAttempt")java.time.OffsetDateTime nextAttempt);
}
