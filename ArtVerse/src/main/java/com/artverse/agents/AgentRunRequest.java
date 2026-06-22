package com.artverse.agents;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentRunRequest(
    String userId,
    Long storyId,
    Long chapterId,
    AgentTaskType taskType,
    List<AgentMessage> messages,
    Map<String, Object> variables,
    AgentModelSpec modelSpec,
    String userApiKey,
    UUID requestId,
    UUID conversationId
) {
    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables) {
        this(userId, storyId, chapterId, taskType, messages, variables, null, null, null, null);
    }

    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables, String userApiKey) {
        this(userId, storyId, chapterId, taskType, messages, variables, null, userApiKey, null, null);
    }

    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables,
                           AgentModelSpec modelSpec, String userApiKey) {
        this(userId, storyId, chapterId, taskType, messages, variables, modelSpec, userApiKey, null, null);
    }
}
