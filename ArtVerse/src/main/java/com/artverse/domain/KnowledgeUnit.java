package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity @Table(name = "knowledge_units") @Getter @Setter
public class KnowledgeUnit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "story_id", nullable = false) private Story story;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "character_profile_id") private CharacterProfile characterProfile;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private KnowledgeUnitType type;
    @Column(nullable = false) private String title;
    @Column(columnDefinition = "TEXT", nullable = false) private String body = "";
    @Column(columnDefinition = "TEXT", nullable = false) private String summary = "";
    @Column(name = "structured_data", columnDefinition = "jsonb", nullable = false) private String structuredData = "{}";
    @Column(nullable = false) private Integer importance = 3;
    @Column(name = "effective_from_chapter") private Integer effectiveFromChapter;
    @Column(name = "effective_to_chapter") private Integer effectiveToChapter;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private KnowledgeUnitStatus status = KnowledgeUnitStatus.ACTIVE;
    @Column(nullable = false) private Integer version = 1;
    @Column(name = "schema_version", nullable = false) private Integer schemaVersion = 2;
    @Column(name = "content_hash", nullable = false, length = 64) private String contentHash;
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @PrePersist void onCreate() { OffsetDateTime now = OffsetDateTime.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
