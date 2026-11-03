package com.artverse.application;

import com.artverse.agent.AgentMessage;

import java.util.List;
import java.util.UUID;

/** Fully materialized input; no persistence objects may cross this boundary. */
public record StoryChatContextSnapshot(
        Long userId, Long storyId, Long chapterId, UUID conversationId, UUID requestId,
        int chapterNumber, long chapterVersion, String novelContent, List<AgentMessage> history
) {
    public StoryChatContextSnapshot {
        novelContent = novelContent == null ? "" : novelContent;
        history = List.copyOf(history);
    }
}
