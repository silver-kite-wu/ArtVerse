package com.artverse.application;

import com.artverse.application.workflow.MangaRouteSource;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.domain.AgentRunType;
import com.artverse.domain.MangaAgentRunStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models returned for manga-agent run state and replay APIs. */
public final class MangaAgentRunSnapshots {
    private MangaAgentRunSnapshots() { }

    public record ResumeContext(
            MangaAgentRunStatus status,
            MangaWorkflowRoute route,
            AgentUserInputRequest userInputRequest
    ) {
    }

    public record RunSnapshot(
            UUID requestId,
            AgentRunType runType,
            MangaAgentRunStatus status,
            String inputMessage,
            String finalReply,
            String errorMessage,
            MangaWorkflowRoute route,
            String routeKey,
            AgentUserInputRequest userInputRequest,
            List<RunEventSnapshot> events,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime completedAt,
            OffsetDateTime lastProgressAt,
            String currentPhase,
            MangaRouteSource routeSource,
            Double routeConfidence,
            String routerVersion,
            String workflowVersion,
            UUID traceId,
            Map<String, Object> skillVersions,
            Long modelConfigId,
            Long knowledgeSnapshotId,
            Map<String, Object> budgetUsage,
            Map<String, Object> runAttributes,
            RunContextSnapshot contextSnapshot,
            List<RunStepSnapshot> steps,
            List<RunArtifactSnapshot> artifacts
    ) {
        public RunSnapshot(UUID requestId, MangaAgentRunStatus status, String inputMessage, String finalReply,
                           String errorMessage, MangaWorkflowRoute route, AgentUserInputRequest userInputRequest,
                           List<RunEventSnapshot> events, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                           OffsetDateTime completedAt, OffsetDateTime lastProgressAt, String currentPhase) {
            this(requestId, AgentRunType.MANGA_AGENT, status, inputMessage, finalReply, errorMessage, route,
                    route == null ? MangaWorkflowRoute.DIRECTOR.name() : route.name(), userInputRequest, events,
                    createdAt, updatedAt, completedAt, lastProgressAt, currentPhase, MangaRouteSource.AUTO, null, null,
                    "manga-workflow-v1", null, Map.of(), null, null, Map.of(), Map.of(), null, List.of(), List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunContextSnapshot(
            Long storyId,
            Long chapterId,
            String storyTitle,
            String chapterDisplayName,
            int sceneCount,
            int imageCount,
            String contextHash,
            String knowledgeRecallHash,
            List<String> requiredFields,
            List<String> warnings
    ) {
        public RunContextSnapshot {
            requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        @JsonIgnore
        public boolean isEmpty() {
            return storyId == null
                    && chapterId == null
                    && (storyTitle == null || storyTitle.isBlank())
                    && (chapterDisplayName == null || chapterDisplayName.isBlank())
                    && sceneCount == 0
                    && imageCount == 0
                    && (contextHash == null || contextHash.isBlank())
                    && (knowledgeRecallHash == null || knowledgeRecallHash.isBlank())
                    && requiredFields.isEmpty()
                    && warnings.isEmpty();
        }
    }

    public record RunStepSnapshot(
            String planId,
            int sequence,
            MangaWorkflowRoute route,
            String status,
            boolean mutating,
            String skillKey,
            String skillVersion,
            String inputSummary,
            String outputSummary,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) { }

    public record RunArtifactSnapshot(
            UUID artifactId,
            String type,
            String status,
            String schemaVersion,
            Map<String, Object> evaluation,
            String checksum
    ) { }

    public record RunEventSnapshot(
            Long eventId,
            String eventName,
            Map<String, Object> data,
            OffsetDateTime createdAt
    ) {
    }

    public record RunEventReplayPage(
            MangaAgentRunStatus status,
            long lastEventId,
            List<RunEventSnapshot> events
    ) {
    }
}
