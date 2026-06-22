package com.artverse.application.workflow;

import java.util.List;

public record MangaWorkflowContextSnapshot(
        Long storyId,
        Long chapterId,
        String storyTitle,
        String chapterDisplayName,
        String storyStyle,
        int sceneCount,
        int imageCount,
        String sourceExcerpt,
        String characterSummary,
        String conversationSummary,
        MangaWorkflowRoute route,
        List<String> warnings
) {
}
