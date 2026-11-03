package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reader_character_conversations")
@Getter
@Setter
public class ReaderCharacterConversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_uuid", nullable = false, unique = true)
    private UUID conversationUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reader_id", nullable = false)
    private User reader;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_profile_id")
    private CharacterProfile characterProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "anchor_chapter_id", nullable = false)
    private Chapter anchorChapter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id")
    private ReaderCharacterStageSnapshot snapshot;

    @Column(name = "conversation_type", nullable = false, length = 20)
    private String conversationType = "SINGLE";

    @Column(length = 255)
    private String title;

    @Column(name = "last_message_at", nullable = false)
    private OffsetDateTime lastMessageAt;

    @Column(name = "is_friend", nullable = false)
    private Boolean friend = false;

    @Column(name = "knowledge_anchor_chapter_number")
    private Integer knowledgeAnchorChapterNumber;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (lastMessageAt == null) lastMessageAt = now;
        if (conversationUuid == null) conversationUuid = UUID.randomUUID();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
