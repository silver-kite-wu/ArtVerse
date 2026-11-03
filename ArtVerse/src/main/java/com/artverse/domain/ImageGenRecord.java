package com.artverse.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "image_gen_records")
@Getter
@Setter
public class ImageGenRecord {
    @Column(name="request_id") private java.util.UUID requestId;
    @Column(name="root_request_id") private java.util.UUID rootRequestId;
    @Column(name="request_hash",length=64) private String requestHash;
    @Column(length=32) private String operation;
    @Column(name="execution_lane",length=24) private String executionLane;
    @Column(name="profile_model_key",length=96) private String profileModelKey;
    @Column(name="chapter_id") private Long chapterId;
    @Column(name="image_number") private Integer imageNumber;
    @Column(name="target_image_id") private Long targetImageId;
    @Column(name="target_base_version") private Long targetBaseVersion;
    @Column(name="target_expected_absent") private Boolean targetExpectedAbsent;
    @Column(name="manga_image_id") private Long mangaImageId;
    @Column(name="result_image_version") private Long resultImageVersion;
    @Column(name="execution_token",nullable=false) private long executionToken;
    @Column(name="provider_attempt",nullable=false) private int providerAttempt;
    @Column(name="provider_started_at") private OffsetDateTime providerStartedAt;
    @Column(name="attempt_decision",length=24) private String attemptDecision;
    @Column(name="retry_not_before") private OffsetDateTime retryNotBefore;
    @Column(name="candidate_object_key",length=700) private String candidateObjectKey;
    @Column(name="candidate_hash",length=64) private String candidateHash;
    @Column(name="candidate_content_type",length=100) private String candidateContentType;
    @Column(name="candidate_size") private Long candidateSize;
    @Column(length=32) private String outcome;
    @Column(name="result_code",length=80) private String resultCode;
    @Column(name="first_started_at") private OffsetDateTime firstStartedAt;
    @Column(name="operation_deadline") private OffsetDateTime operationDeadline;
    @Column(name="recovery_version",nullable=false) private long recoveryVersion;
    @Column(name="review_requested_at") private OffsetDateTime reviewRequestedAt;
    @Column(name="recovery_decision",length=40) private String recoveryDecision;
    @Column(name="recovery_audit",columnDefinition="TEXT") private String recoveryAudit = "[]";
    @Column(name="replacement_request_id") private java.util.UUID replacementRequestId;
    @Column(name="automation_target_key",length=160) private String automationTargetKey;
    @Column(name="automation_dispatch_state",length=24) private String automationDispatchState;
    @Column(name="automation_attempts",nullable=false) private int automationAttempts;
    @Column(name="automation_error_code",length=80) private String automationErrorCode;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    @JsonIgnore
    private MangaAgentConversation conversation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    @Column(length = 64, nullable = false)
    private String model = "";

    @Column(length = 32, nullable = false)
    private String size = "1024x1024";

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ImageGenStatus status = ImageGenStatus.RUNNING;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
