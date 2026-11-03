package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import com.artverse.domain.Role;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    @Column(nullable = false, length = 200, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "avatar_bucket", length = 200)
    private String avatarBucket;

    @Column(name = "avatar_key", length = 500)
    private String avatarKey;

    @Column(name = "avatar_content_type", length = 50)
    private String avatarContentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
