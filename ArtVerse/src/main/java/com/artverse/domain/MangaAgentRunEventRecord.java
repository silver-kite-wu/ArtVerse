package com.artverse.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "manga_agent_run_events")
@Getter
@Setter
public class MangaAgentRunEventRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    @JsonIgnore
    private MangaAgentRun run;

    @Column(name = "event_name", nullable = false, length = 64)
    private String eventName;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(length = 64)
    private String phase;

    @Column(length = 255)
    private String label;

    @Column(length = 64)
    private String status;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
