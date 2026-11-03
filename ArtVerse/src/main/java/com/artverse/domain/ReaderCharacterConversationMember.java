package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "reader_character_conversation_members",
        uniqueConstraints = @UniqueConstraint(name = "uq_reader_character_conversation_member", columnNames = {"conversation_id", "character_profile_id"}))
@Getter
@Setter
public class ReaderCharacterConversationMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ReaderCharacterConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_profile_id", nullable = false)
    private CharacterProfile characterProfile;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) joinedAt = OffsetDateTime.now();
    }
}
