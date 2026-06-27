package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import org.springframework.stereotype.Component;

@Component
public class MangaHitlNode extends AbstractStaticReplyNode {

    public MangaHitlNode(MangaAgentConversationService mangaAgentConversationService) {
        super(mangaAgentConversationService);
    }

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.HITL;
    }

    @Override
    protected String responseText(MangaWorkflowExecutionContext context) {
        return """
                当前处于「决策」模式，这是一个需要你收束选择的工作节点。

                请说明需要确认什么（例如：选择分支、确认创意方向、决定是否执行工具操作），
                然后切回「导演」模式，我会根据你的选择继续推进。
                """.trim();
    }
}