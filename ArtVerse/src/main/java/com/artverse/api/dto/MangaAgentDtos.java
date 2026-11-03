package com.artverse.api.dto;

import com.artverse.domain.MangaAgentMessage;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.MangaAgentRunSnapshots;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaRouteSource;
import com.artverse.domain.AgentRunType;
import com.artverse.domain.MangaAgentConversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MangaAgentDtos {
    private MangaAgentDtos() {
    }

    public record RunRequest(String message, UUID requestId, String model, String provider, String label, String apiKey, String baseUrl, Long configId) {
    }

    public record RunResponse(String reply, UUID requestId) {
    }

    public record ResumeRequest(String answer, String model, String provider, String label, String apiKey, String baseUrl, Long configId) {
    }

    public record StoryChatResumeRequest(String decision, UUID artifactId, String answer, String model,
                                         String provider, String label, String apiKey, String baseUrl,
                                         Long configId) {
    }

    public record OpenRunResponse(RunStateResponse run) {
    }

    public record ConversationDto(UUID conversationId,
                                  String title,
                                  String status,
                                  OffsetDateTime createdAt,
                                  OffsetDateTime updatedAt,
                                  OffsetDateTime archivedAt) {
        public static ConversationDto from(MangaAgentConversation conversation) {
            return new ConversationDto(
                    conversation.getConversationUuid(),
                    conversation.getTitle(),
                    conversation.getStatus().name(),
                    conversation.getCreatedAt(),
                    conversation.getUpdatedAt(),
                    conversation.getArchivedAt()
            );
        }
    }

    public record ConversationsResponse(List<ConversationDto> conversations) {
    }

    public record RunStateResponse(UUID requestId,
                                   AgentRunType runType,
                                   String status,
                                   String inputMessage,
                                   String finalReply,
                                   String errorMessage,
                                   MangaWorkflowRoute route,
                                   String routeKey,
                                   AgentUserInputRequest userInputRequest,
                                   List<RunEventDto> events,
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
                                   MangaAgentRunSnapshots.RunContextSnapshot contextSnapshot,
                                   List<MangaAgentRunSnapshots.RunStepSnapshot> steps,
                                   List<MangaAgentRunSnapshots.RunArtifactSnapshot> artifacts) {
        public static RunStateResponse from(MangaAgentRunSnapshots.RunSnapshot snapshot) {
            return new RunStateResponse(
                    snapshot.requestId(),
                    snapshot.runType(),
                    snapshot.status().name(),
                    snapshot.inputMessage(),
                    snapshot.finalReply(),
                    snapshot.errorMessage(),
                    snapshot.route(),
                    snapshot.routeKey(),
                    snapshot.userInputRequest(),
                    snapshot.events().stream().map(RunEventDto::from).toList(),
                    snapshot.createdAt(),
                    snapshot.updatedAt(),
                    snapshot.completedAt(),
                    snapshot.lastProgressAt(),
                    snapshot.currentPhase(),
                    snapshot.routeSource(),
                    snapshot.routeConfidence(),
                    snapshot.routerVersion(),
                    snapshot.workflowVersion(),
                    snapshot.traceId(),
                    snapshot.skillVersions(),
                    snapshot.modelConfigId(),
                    snapshot.knowledgeSnapshotId(),
                    snapshot.budgetUsage(),
                    snapshot.runAttributes(),
                    snapshot.contextSnapshot(),
                    snapshot.steps(),
                    snapshot.artifacts()
            );
        }
    }

    public record RunEventDto(Long eventId, String eventName, Map<String, Object> data, OffsetDateTime createdAt) {
        public static RunEventDto from(MangaAgentRunSnapshots.RunEventSnapshot event) {
            return new RunEventDto(event.eventId(), event.eventName(), event.data(), event.createdAt());
        }
    }

    public record MessageDto(Long id, String role, String content, UUID requestId, OffsetDateTime createdAt) {
        public static MessageDto from(MangaAgentMessage message) {
            return new MessageDto(
                    message.getId(),
                    message.getRole().name().toLowerCase(),
                    message.getContent(),
                    message.getRequestId(),
                    message.getCreatedAt()
            );
        }
    }

    public record MessagesResponse(List<MessageDto> messages) {
    }
}
