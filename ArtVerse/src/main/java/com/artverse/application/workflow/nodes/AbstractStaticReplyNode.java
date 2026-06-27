package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.domain.MessageRole;
import java.util.Map;

abstract class AbstractStaticReplyNode implements MangaWorkflowNodeHandler {

    private final MangaAgentConversationService mangaAgentConversationService;

    protected AbstractStaticReplyNode(MangaAgentConversationService mangaAgentConversationService) {
        this.mangaAgentConversationService = mangaAgentConversationService;
    }

    @Override
    public final Map<String, Object> run(MangaWorkflowExecutionContext context) {
        return reply(context).toPayload();
    }

    @Override
    public final Map<String, Object> stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
        return reply(context).toPayload();
    }

    protected final MangaWorkflowResult reply(MangaWorkflowExecutionContext context) {
        mangaAgentConversationService.saveMessage(
                context.conversation(),
                MessageRole.USER,
                context.message(),
                context.requestId()
        );
        String reply = responseText(context);
        mangaAgentConversationService.saveMessage(
                context.conversation(),
                MessageRole.ASSISTANT,
                reply,
                context.requestId()
        );
        return MangaWorkflowResult.success(reply);
    }

    protected abstract String responseText(MangaWorkflowExecutionContext context);
}
