package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity
@Table(name="scheduled_manga_panel", uniqueConstraints=@UniqueConstraint(name="uq_scheduled_manga_panel", columnNames={"task_id","image_number"}))
@Getter @Setter
public class ScheduledMangaPanel {
    public enum State { PENDING, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED, UNCERTAIN }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="task_id", nullable=false) private ScheduledMangaGeneration task;
    @Column(name="image_number", nullable=false) private Integer imageNumber;
    @Column(name="prompt_snapshot", nullable=false, columnDefinition="text") private String promptSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private State state;
    @Column(name="attempt_count", nullable=false) private Integer attemptCount=0;
    @Column(name="next_attempt_at") private OffsetDateTime nextAttemptAt;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="result_image_id") private MangaImage resultImage;
    @Column(name="error_code") private String errorCode;
    @Column(name="error_message") private String errorMessage;
    @Column(name="lease_owner") private String leaseOwner;
    @Column(name="lease_until") private OffsetDateTime leaseUntil;
    @Column(name="fencing_token", nullable=false) private Long fencingToken=0L;
}
