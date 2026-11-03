package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "scheduled_manga_generation")
@Getter @Setter
public class ScheduledMangaGeneration {
    @Column(name="automation_id") private UUID automationId;
    @Column(name="automation_run_id") private UUID automationRunId;
    public enum State { SCHEDULED, RUNNING, SUCCEEDED, PARTIAL_FAILED, FAILED, CANCELLED, STALE_REQUIRES_CONFIRMATION }
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name="user_id", nullable=false) private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name="chapter_id", nullable=false) private Chapter chapter;
    @Column(name="requested_at", nullable=false) private OffsetDateTime requestedAt;
    @Column(name="timezone_id", nullable=false) private String timezoneId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private State state;
    @Column(name="chapter_version", nullable=false) private Long chapterVersion;
    @Column(name="model_config_id") private Long modelConfigId;
    @Column(name="model_name", nullable=false) private String modelName;
    @Column(name="scenes_snapshot", nullable=false, columnDefinition="text") private String scenesSnapshot;
    @Column(name="input_snapshot", nullable=false, columnDefinition="text") private String inputSnapshot;
    @Column(name="snapshot_hash", nullable=false, length=64) private String snapshotHash;
    @Column(name="prompt_version", nullable=false, length=80) private String promptVersion;
    @Column(name="idempotency_key", nullable=false) private String idempotencyKey;
    @Column(name="lease_owner") private String leaseOwner;
    @Column(name="lease_until") private OffsetDateTime leaseUntil;
    @Column(name="fencing_token", nullable=false) private Long fencingToken = 0L;
    @Column(name="error_code") private String errorCode;
    @Column(name="error_message") private String errorMessage;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
    @Column(name="started_at") private OffsetDateTime startedAt;
    @Column(name="completed_at") private OffsetDateTime completedAt;
    @PrePersist void created() { if (createdAt == null) createdAt = OffsetDateTime.now(); }
}
