package com.artverse.application.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MangaIntentClassifierServiceTest {

    private final MangaIntentClassifierService service = new MangaIntentClassifierService();

    @Test
    void classifiesStoryboardGenerationAsDirector() {
        MangaIntentResult result = service.classify("请帮我生成分镜并保存", context(0));

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.DIRECTOR);
        assertThat(result.requiresConfirmation()).isFalse();
    }

    @Test
    void classifiesStoryboardReviewAsReview() {
        MangaIntentResult result = service.classify("质检一下当前分镜有什么风险", context(3));

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.REVIEW);
        assertThat(result.requiresConfirmation()).isFalse();
    }

    @Test
    void classifiesProgressQueryAsChat() {
        MangaIntentResult result = service.classify("查看本章漫画进度", context(3));

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CHAT);
        assertThat(result.requiresConfirmation()).isFalse();
    }

    @Test
    void unknownIntentRequiresConfirmation() {
        MangaIntentResult result = service.classify("嗯嗯", context(1));

        assertThat(result.requiresConfirmation()).isTrue();
    }

    private MangaWorkflowContextSnapshot context(int sceneCount) {
        return new MangaWorkflowContextSnapshot(
                1L,
                2L,
                "故事",
                "第1话",
                "黑白漫画",
                sceneCount,
                0,
                "章节正文",
                "",
                "",
                MangaWorkflowRoute.AUTO,
                List.of()
        );
    }
}
