package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "stories")
@Getter
@Setter
public class Story {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    @Column(name = "ref_image", length = 500)
    private String refImage;

    @Column(name = "character_profiles", columnDefinition = "TEXT")
    private String characterProfiles;

    @Column(name = "is_published", nullable = false)
    private Boolean isPublished = false;

    @Column(name = "published_at")
    private java.time.OffsetDateTime publishedAt;

    @Column(name = "novel_is_published", nullable = false)
    private Boolean novelIsPublished = false;

    @Column(name = "novel_published_at")
    private java.time.OffsetDateTime novelPublishedAt;

    @Column(name = "manga_style", length = 32, nullable = false)
    private String mangaStyle = "japanese_manga";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "story", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("chapterNumber ASC")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<Chapter> chapters = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    @OneToMany(mappedBy = "story", cascade = CascadeType.ALL, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Set<StoryAssetGroup> assetGroups = new LinkedHashSet<>();

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
