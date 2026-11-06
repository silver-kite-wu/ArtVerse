package com.artverse.application.workflow;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentAnswerDelivery;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.gateway.AgentSessionTurns;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.User;

import java.util.UUID;

public record MangaWorkflowExecutionContext(
        MangaAgentConversation conversation,
        String message,
        UUID requestId,
        String llmApiKey,
        AgentModelSpec modelSpec,
        AgentRunToolStatus.RunState toolState,
        User user,
        Chapter chapter,
        MangaWorkflowContextSnapshot workflowContext,
        boolean persistConversationMessages,
        String stepId,
        ExecutionClaim executionClaim,
        AnswerResume answerResume
) {
    public record AnswerResume(AgentAnswerDelivery delivery, AgentSessionTurns.Lease turn,
                                AgentTaskType taskType, String stepId) {
        public boolean matches(AgentTaskType task, String step) { return taskType == task && java.util.Objects.equals(stepId, step); }
    }
    /** Captured once from the server's claimed run; derived steps keep this exact claim. */
    public record ExecutionClaim(String owner, Long fencingToken) {
        public static ExecutionClaim from(MangaAgentRun run) {
            return run == null ? null : new ExecutionClaim(run.getOwnerInstanceId(), run.getFencingToken());
        }

        public boolean valid() {
            return owner != null && !owner.isBlank() && fencingToken != null && fencingToken > 0;
        }
    }

    public MangaWorkflowExecutionContext(MangaAgentConversation conversation, String message, UUID requestId,
                                         String llmApiKey, AgentModelSpec modelSpec,
                                         AgentRunToolStatus.RunState toolState, User user, Chapter chapter,
                                         MangaWorkflowContextSnapshot workflowContext,
                                         boolean persistConversationMessages, String stepId, ExecutionClaim executionClaim) {
        this(conversation, message, requestId, llmApiKey, modelSpec, toolState, user, chapter,
                workflowContext, persistConversationMessages, stepId, executionClaim, null);
    }

    public MangaWorkflowExecutionContext(MangaAgentConversation conversation, String message, UUID requestId,
                                         String llmApiKey, AgentModelSpec modelSpec,
                                         AgentRunToolStatus.RunState toolState, User user, Chapter chapter,
                                         MangaWorkflowContextSnapshot workflowContext,
                                         boolean persistConversationMessages, String stepId) {
        this(conversation, message, requestId, llmApiKey, modelSpec, toolState, user, chapter,
                workflowContext, persistConversationMessages, stepId, null);
    }

    public MangaWorkflowExecutionContext(MangaAgentConversation conversation, String message, UUID requestId,
                                         String llmApiKey, AgentModelSpec modelSpec,
                                         AgentRunToolStatus.RunState toolState, User user, Chapter chapter,
                                         MangaWorkflowContextSnapshot workflowContext) {
        this(conversation, message, requestId, llmApiKey, modelSpec, toolState, user, chapter,
                workflowContext, true, null, null);
    }

    public MangaWorkflowExecutionContext(MangaAgentConversation conversation, String message, UUID requestId,
                                         String llmApiKey, AgentModelSpec modelSpec,
                                         AgentRunToolStatus.RunState toolState, User user, Chapter chapter,
                                         MangaWorkflowContextSnapshot workflowContext,
                                         boolean persistConversationMessages) {
        this(conversation, message, requestId, llmApiKey, modelSpec, toolState, user, chapter,
                workflowContext, persistConversationMessages, null, null);
    }
}
