package com.artverse.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "manga_images",
       uniqueConstraints = @UniqueConstraint(name = "uq_manga_images_chapter_number", columnNames = {"chapter_id", "image_number"}))
@Getter
@Setter
public class MangaImage {

    @Version
    @Column(nullable = false)
    @JsonIgnore
    private Long version;

    @com.fasterxml.jackson.annotation.JsonProperty("image_version")
    public String imageVersion() { return version == null ? null : version.toString(); }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    @JsonIgnore
    private Chapter chapter;

    @Column(name = "image_number", nullable = false)
    private Integer imageNumber;

    @Column(name = "image_path", nullable = false, length = 500)
    private String imagePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_provider", nullable = false, length = 20)
    private StorageProvider storageProvider = StorageProvider.LOCAL;

    @Column(length = 120)
    private String bucket;

    @Column(name = "object_key", length = 700)
    private String objectKey;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
