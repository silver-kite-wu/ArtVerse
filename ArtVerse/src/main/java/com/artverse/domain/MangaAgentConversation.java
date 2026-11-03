package com.artverse.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@DynamicUpdate
@Table(
        name = "ai_conversations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_conversations_uuid",
                columnNames = {"conversation_uuid"}
        )
)
@Getter
@Setter
public class MangaAgentConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_uuid", nullable = false)
    private UUID conversationUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id")
    @JsonIgnore
    private Story story;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id")
    @JsonIgnore
    private Chapter chapter;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_type", nullable = false, length = 32)
    private AiConversationType conversationType = AiConversationType.MANGA_AGENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_source", nullable = false, length = 16)
    private AiConversationTitleSource titleSource = AiConversationTitleSource.DEFAULT;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_state", nullable = false, length = 16)
    private AiConversationTitleState titleState = AiConversationTitleState.WAITING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MangaAgentConversationStatus status = MangaAgentConversationStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_activity_at", nullable = false)
    private OffsetDateTime lastActivityAt;

    @Column(name = "title_generation_started_at")
    private OffsetDateTime titleGenerationStartedAt;

    @Column(name = "legacy_import_key", length = 160)
    private String legacyImportKey;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (conversationUuid == null) conversationUuid = UUID.randomUUID();
        if (status == null) status = MangaAgentConversationStatus.ACTIVE;
        if (conversationType == null) conversationType = AiConversationType.MANGA_AGENT;
        if (titleSource == null) titleSource = AiConversationTitleSource.DEFAULT;
        if (titleState == null) titleState = AiConversationTitleState.WAITING;
        if (lastActivityAt == null) lastActivityAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
