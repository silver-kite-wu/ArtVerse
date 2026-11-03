package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "reader_character_stage_snapshots")
@Getter
@Setter
public class ReaderCharacterStageSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_profile_id", nullable = false)
    private CharacterProfile characterProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "anchor_chapter_id", nullable = false)
    private Chapter anchorChapter;

    @Column(name = "anchor_chapter_number", nullable = false)
    private Integer anchorChapterNumber;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String persona = "";

    @Column(name = "growth_summary", columnDefinition = "TEXT", nullable = false)
    private String growthSummary = "";

    @Column(name = "emotional_state", columnDefinition = "TEXT", nullable = false)
    private String emotionalState = "";

    @Column(name = "known_facts", columnDefinition = "TEXT", nullable = false)
    private String knownFacts = "";

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(nullable = false, length = 20)
    private String status = "READY";

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
