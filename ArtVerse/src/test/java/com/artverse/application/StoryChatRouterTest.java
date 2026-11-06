package com.artverse.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryChatRouterTest {

    private final StoryChatRouter router = new StoryChatRouter();

    @Test
    void explicitSaveRequestRoutesToWrite() {
        assertThat(router.classify("请续写这一段并保存到小说原文"))
                .isEqualTo(StoryChatRoute.WRITE);
    }

    @Test
    void reviewAndPlainDraftStayReadOnly() {
        assertThat(router.classify("帮我审阅这一章的问题"))
                .isEqualTo(StoryChatRoute.REVIEW);
        assertThat(router.classify("继续写一段给我看看"))
                .isEqualTo(StoryChatRoute.DRAFT);
    }

    @Test
    void discussionRoutesToRead() {
        assertThat(router.classify("你觉得这个角色动机合理吗"))
                .isEqualTo(StoryChatRoute.READ);
    }

    @Test
    void optimizationAndRevisionPrepareReviewableEdits() {
        assertThat(router.classify("将本章节进行优化一下吧，要求人物角色立体，语言逻辑清晰合理")).isEqualTo(StoryChatRoute.POLISH);
        assertThat(router.classify("帮我修改这一章")).isEqualTo(StoryChatRoute.POLISH);
    }

    @Test
    void qualityAuditUsesReviewRouteAndReviewSkill() {
        String input = "审核本章创作质量";
        assertThat(router.classify(input)).isEqualTo(StoryChatRoute.REVIEW);
        assertThat(new NovelBusinessSkillRouter().classify(input).mode()).isEqualTo(NovelBusinessSkillMode.REVIEW);
        String advice = "评审一下本章节的创作质量如何，有什么优化的方向吗";
        assertThat(router.classify(advice)).isEqualTo(StoryChatRoute.REVIEW);
        assertThat(new NovelBusinessSkillRouter().classify(advice).mode()).isEqualTo(NovelBusinessSkillMode.REVIEW);
    }

    @Test void historicalRestorePreparesReviewWhileHistoryQuestionsRemainReadOnly() {
        assertThat(router.classify("请恢复本章正文到历史版本 3")).isEqualTo(StoryChatRoute.WRITE);
        assertThat(router.classify("Restore the prose from revision 3")).isEqualTo(StoryChatRoute.WRITE);
        assertThat(router.classify("如何恢复历史版本？")).isEqualTo(StoryChatRoute.READ);
        assertThat(router.classify("查看历史正文版本")).isEqualTo(StoryChatRoute.READ);
        assertThat(router.classify("不要恢复旧稿")).isEqualTo(StoryChatRoute.READ);
    }
}
