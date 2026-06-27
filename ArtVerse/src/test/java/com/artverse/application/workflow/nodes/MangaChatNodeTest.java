package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentModelSpec;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowContextSnapshot;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MangaChatNodeTest {

    @Test
    void savesUserMessageBeforeAssistantReply() {
        MangaAgentConversationService conversationService = mock(MangaAgentConversationService.class);
        MangaChatNode node = new MangaChatNode(conversationService);
        MangaWorkflowExecutionContext context = context("查看本章漫画进度", 4, 2);

        node.run(context);

        verify(conversationService).saveMessage(
                context.conversation(),
                MessageRole.USER,
                "查看本章漫画进度",
                context.requestId()
        );
        verify(conversationService).saveMessage(
                context.conversation(),
                MessageRole.ASSISTANT,
                """
                        当前章节：第1话
                        原文状态：已准备
                        分镜状态：已有 4 条分镜记录
                        图片状态：已有 2 张图片

                        下一步建议：已经有分镜和图片，可以继续质检、微调分镜，或查看生成结果。
                        """.trim(),
                context.requestId()
        );
    }

    @Test
    void progressReplyReflectsMissingStoryboard() {
        MangaAgentConversationService conversationService = mock(MangaAgentConversationService.class);
        MangaChatNode node = new MangaChatNode(conversationService);
        MangaWorkflowExecutionContext context = context("进度", 0, 0);

        String reply = String.valueOf(node.run(context).get("reply"));

        assertThat(reply).contains("分镜状态：尚未生成");
        assertThat(reply).contains("可以让我生成分镜");
    }

    private MangaWorkflowExecutionContext context(String message, int sceneCount, int imageCount) {
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(2L);
        story.setTitle("故事");
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(3L);
        chapter.setChapterNumber(1);
        chapter.setStory(story);
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(4L);
        conversation.setUser(user);
        conversation.setStory(story);
        conversation.setChapter(chapter);
        UUID requestId = UUID.randomUUID();
        MangaWorkflowContextSnapshot snapshot = new MangaWorkflowContextSnapshot(
                story.getId(),
                chapter.getId(),
                story.getTitle(),
                "第1话",
                "黑白漫画",
                sceneCount,
                imageCount,
                "章节正文",
                "",
                "user: " + message,
                MangaWorkflowRoute.CHAT,
                List.of()
        );
        return new MangaWorkflowExecutionContext(
                conversation,
                message,
                requestId,
                "deepseek-key",
                new AgentModelSpec("deepseek", "https://api.deepseek.com", "deepseek-chat", "hash"),
                mock(AgentRunToolStatus.RunState.class),
                user,
                chapter,
                snapshot
        );
    }
}
