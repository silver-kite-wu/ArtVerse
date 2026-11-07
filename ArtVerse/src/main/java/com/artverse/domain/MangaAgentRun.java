package com.artverse.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.artverse.application.workflow.MangaRouteSource;
import com.artverse.application.workflow.MangaWorkflowRoute;

@Entity
@DynamicUpdate
@Table(
        name = "manga_agent_runs",
        uniqueConstraints = @UniqueConstraint(name = "uk_manga_agent_runs_user_request", columnNames = {"user_id", "request_id"})
)
@Getter
@Setter
public class MangaAgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @JsonIgnore
    private Story story;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    @JsonIgnore
    private Chapter chapter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    @JsonIgnore
    private MangaAgentConversation conversation;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 32)
    private AgentRunType runType = AgentRunType.MANGA_AGENT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MangaAgentRunStatus status = MangaAgentRunStatus.RUNNING;

    @Column(name = "input_message", nullable = false, columnDefinition = "TEXT")
    private String inputMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MangaWorkflowRoute route = MangaWorkflowRoute.DIRECTOR;

    @Column(name = "route_key", length = 64)
    private String routeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "route_source", nullable = false, length = 32)
    private MangaRouteSource routeSource = MangaRouteSource.AUTO;

    @Column(name = "route_confidence")
    private Double routeConfidence;

    @Column(name = "router_version", length = 32)
    private String routerVersion;

    @Column(name = "routing_decision_json", columnDefinition = "TEXT")
    private String routingDecisionJson;

    @Column(name = "execution_plan_json", columnDefinition = "TEXT")
    private String executionPlanJson;

    @Column(name = "workflow_version", nullable = false, length = 64)
    private String workflowVersion = "manga-workflow-v1";

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "trace_id", nullable = false)
    private UUID traceId;

    @Column(name = "skill_versions_json", nullable = false, columnDefinition = "jsonb")
    private String skillVersionsJson = "{}";

    @Column(name = "model_config_id")
    private Long modelConfigId;

    @Column(name = "knowledge_snapshot_id")
    private Long knowledgeSnapshotId;

    @Column(name = "budget_usage_json", nullable = false, columnDefinition = "jsonb")
    private String budgetUsageJson = "{}";

    @Column(name = "context_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String contextSnapshotJson = "{}";

    @Column(name = "run_attributes_json", nullable = false, columnDefinition = "jsonb")
    private String runAttributesJson = "{}";

    @Column(name = "owner_instance_id", length = 160)
    private String ownerInstanceId;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "fencing_token", nullable = false)
    private Long fencingToken = 0L;

    @Column(name = "final_reply", columnDefinition = "TEXT")
    private String finalReply;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "user_input_request_json", columnDefinition = "TEXT")
    private String userInputRequestJson;

    @Column(name = "result_schema", length = 128)
    private String resultSchema;

    @Column(name = "verified_result_json", columnDefinition = "jsonb")
    private String verifiedResultJson;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "last_progress_at", nullable = false)
    private OffsetDateTime lastProgressAt;

    @Column(name = "current_phase", nullable = false, length = 32)
    private String currentPhase = "MODEL";

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        lastProgressAt = now;
        if (requestId == null) requestId = UUID.randomUUID();
        if (runType == null) runType = AgentRunType.MANGA_AGENT;
        if (routeKey == null && route != null) routeKey = route.name();
        if (traceId == null) traceId = UUID.randomUUID();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
