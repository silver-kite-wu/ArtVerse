package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowContextSnapshot;
import com.artverse.application.workflow.MangaWorkflowRoute;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class MangaChatNode extends AbstractStaticReplyNode {

    public MangaChatNode(MangaAgentConversationService mangaAgentConversationService) {
        super(mangaAgentConversationService);
    }

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.CHAT;
    }

    @Override
    protected String responseText(MangaWorkflowExecutionContext context) {
        if (isProgressQuestion(context.message())) {
            return progressReply(context.workflowContext());
        }
        return """
                我会先按普通对话处理这个问题，不会修改章节分镜或保存内容。

                如果你想让我生成、改写或保存分镜，请直接说明要执行的动作；如果要检查现有分镜，请说“质检”或“检查分镜”。
                """.trim();
    }

    private boolean isProgressQuestion(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return text.contains("进度")
                || text.contains("状态")
                || text.contains("查看本章")
                || text.contains("本章漫画")
                || text.contains("完成了吗")
                || text.contains("到哪")
                || text.contains("ready")
                || text.contains("progress")
                || text.contains("status");
    }

    private String progressReply(MangaWorkflowContextSnapshot context) {
        boolean hasSource = context.sourceExcerpt() != null && !context.sourceExcerpt().isBlank();
        boolean hasStoryboard = context.sceneCount() > 0;
        boolean hasImages = context.imageCount() > 0;
        String nextStep;
        if (!hasSource) {
            nextStep = "先在当前章节写入聊天内容或导入小说正文。";
        } else if (!hasStoryboard) {
            nextStep = "可以让我生成分镜，或切到导演模式继续推进。";
        } else if (!hasImages) {
            nextStep = "分镜已经具备，可以检查分镜后点击 Generate Manga 生成漫画图片。";
        } else {
            nextStep = "已经有分镜和图片，可以继续质检、微调分镜，或查看生成结果。";
        }

        return """
                当前章节：%s
                原文状态：%s
                分镜状态：%s
                图片状态：%s

                下一步建议：%s
                """.formatted(
                context.chapterDisplayName(),
                hasSource ? "已准备" : "缺失",
                hasStoryboard ? "已有 " + context.sceneCount() + " 条分镜记录" : "尚未生成",
                hasImages ? "已有 " + context.imageCount() + " 张图片" : "尚未生成图片",
                nextStep
        ).trim();
    }
}
