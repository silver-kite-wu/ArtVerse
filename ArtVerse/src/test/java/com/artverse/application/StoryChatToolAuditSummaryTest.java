package com.artverse.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StoryChatToolAuditSummaryTest {
    @Test
    void activityMetadataNeverIncludesChapterDraftOrNestedRawResult() {
        Map<String, Object> result = Map.of("chapter_id", 6L, "chapter_number", 1,
                "chapter_version", 3L, "novel_content", "private original", "content", "private draft",
                "data", Map.of("novel_content", "nested private original"));
        for (String tool : new String[]{"get_novel_context", "draft_novel_content", "draft_novel_restore", "get_novel_revision",
                "list_novel_revisions", "get_artifact", "list_artifacts", "discard_artifact", "commit_novel_content", "ask_user"}) {
            assertThat(StoryChatToolAuditSummary.project(tool, result))
                    .containsEntry("chapter_id", 6L).containsEntry("chapter_number", 1)
                    .doesNotContainKeys("novel_content", "content", "data");
        }
        assertThat(result).containsEntry("novel_content", "private original");
    }

    @Test
    void otherToolContractsArePreserved() {
        Map<String, Object> result = Map.of("scenes_count", 5, "saved", true);
        assertThat(StoryChatToolAuditSummary.project("commit_storyboard", result)).isSameAs(result);
    }
}
