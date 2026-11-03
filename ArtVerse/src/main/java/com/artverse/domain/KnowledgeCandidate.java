package com.artverse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_candidates")
@Getter
@Setter
public class KnowledgeCandidate {

    @jakarta.persistence.Version
    @Column(nullable = false)
    private Long version = 0L;
    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 2;
    @Column(name = "target_unit_id")
    private Long targetUnitId;
    @Column(name = "base_version")
    private Integer baseVersion;
    @Column(name = "source_hash", length = 64)
    private String sourceHash;
    @Column(name = "source_snapshot", columnDefinition = "jsonb", nullable = false)
    private String sourceSnapshot = "{}";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id")
    private Chapter chapter;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_id", length = 120)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_type", nullable = false, length = 32)
    private KnowledgeUnitType knowledgeType;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary = "";

    @Column(name = "structured_data", nullable = false, columnDefinition = "jsonb")
    private String structuredData = "{}";

    @Column(nullable = false)
    private Integer importance = 3;

    @Column(name = "effective_from_chapter")
    private Integer effectiveFromChapter;

    @Column(name = "effective_to_chapter")
    private Integer effectiveToChapter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private KnowledgeCandidateStatus status = KnowledgeCandidateStatus.PENDING;

    @Column(name = "proposed_hash", nullable = false, length = 64)
    private String proposedHash;

    @Column(name = "approved_knowledge_unit_id")
    private Long approvedKnowledgeUnitId;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
