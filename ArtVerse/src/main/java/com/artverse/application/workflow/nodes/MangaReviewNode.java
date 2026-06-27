package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import org.springframework.stereotype.Component;

@Component
public class MangaReviewNode extends AbstractStaticReplyNode {

    public MangaReviewNode(MangaAgentConversationService mangaAgentConversationService) {
        super(mangaAgentConversationService);
    }

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.REVIEW;
    }

    @Override
    protected String responseText(MangaWorkflowExecutionContext context) {
        return """
                当前处于「质检」模式，用于检查现有分镜的状态、风险和下一步动作。

                我还没有接入自动质检能力——你可以先查看当前的章节上下文和分镜列表，
                确认需要修改或调整的地方后，切回「导演」模式告诉我要改什么。
                """.trim();
    }
}