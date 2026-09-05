package com.artverse.agent;

import com.artverse.application.UserProviderConfig;

import java.util.UUID;

public record MangaAgentRuntimeContext(
        Long userId,
        Long storyId,
        Long chapterId,
        UUID conversationId,
        UUID requestId,
        UserProviderConfig workflowConfig,
        AgentTaskType taskType,
        String stepId,
        Long fencingToken,
        UUID tenantId
) {
    public MangaAgentRuntimeContext(Long userId, Long storyId, Long chapterId,
                                    UUID conversationId, UUID requestId, UserProviderConfig workflowConfig) {
        this(userId, storyId, chapterId, conversationId, requestId, workflowConfig,
                AgentTaskType.MANGA_DIRECTOR, "manga-director", 0L, null);
    }

    public MangaAgentRuntimeContext(Long userId, Long storyId, Long chapterId,
                                    UUID conversationId, UUID requestId, UserProviderConfig workflowConfig,
                                    AgentTaskType taskType) {
        this(userId, storyId, chapterId, conversationId, requestId, workflowConfig,
                taskType, taskType == null ? "unknown" : taskType.sessionSuffix(), 0L, null);
    }

    public MangaAgentRuntimeContext(Long userId, Long storyId, Long chapterId,
                                    UUID conversationId, UUID requestId, UserProviderConfig workflowConfig,
                                    AgentTaskType taskType, String stepId) {
        this(userId, storyId, chapterId, conversationId, requestId, workflowConfig,
                taskType, stepId, 0L, null);
    }

    public MangaAgentRuntimeContext(Long userId, Long storyId, Long chapterId,
                                    UUID conversationId, UUID requestId, UserProviderConfig workflowConfig,
                                    AgentTaskType taskType, String stepId, Long fencingToken) {
        this(userId, storyId, chapterId, conversationId, requestId, workflowConfig,
                taskType, stepId, fencingToken, null);
    }
}
