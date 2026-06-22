package com.artverse.api.dto;

import com.artverse.domain.MangaAgentMessage;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.MangaAgentRunService;
import com.artverse.domain.MangaAgentConversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MangaAgentDtos {
    private MangaAgentDtos() {
    }

    public record RunRequest(String message, UUID requestId) {
    }

    public record RunResponse(String reply, UUID requestId) {
    }

    public record ResumeRequest(String answer) {
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
                                   String status,
                                   String inputMessage,
                                   String finalReply,
                                   String errorMessage,
                                   AgentUserInputRequest userInputRequest,
                                   List<RunEventDto> events,
                                   OffsetDateTime createdAt,
                                   OffsetDateTime updatedAt,
                                   OffsetDateTime completedAt) {
        public static RunStateResponse from(MangaAgentRunService.RunSnapshot snapshot) {
            return new RunStateResponse(
                    snapshot.requestId(),
                    snapshot.status().name(),
                    snapshot.inputMessage(),
                    snapshot.finalReply(),
                    snapshot.errorMessage(),
                    snapshot.userInputRequest(),
                    snapshot.events().stream().map(RunEventDto::from).toList(),
                    snapshot.createdAt(),
                    snapshot.updatedAt(),
                    snapshot.completedAt()
            );
        }
    }

    public record RunEventDto(String eventName, Map<String, Object> data, OffsetDateTime createdAt) {
        public static RunEventDto from(MangaAgentRunService.RunEventSnapshot event) {
            return new RunEventDto(event.eventName(), event.data(), event.createdAt());
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
