package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.agent.gateway.tools.AgentToolCapability;
import com.artverse.application.MangaAgentRunService;
import java.util.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Native-only tests mock SQL authority explicitly; SQL integration tests use the production service. */
public final class ToolCapabilityFixtures {
    private ToolCapabilityFixtures() { }
    public static void mockScopes(MangaAgentRunService runs) {
        when(runs.captureToolExecutionScope(any())).thenAnswer(invocation -> scope(invocation.getArgument(0)));
    }
    public static AgentToolCapability.ExecutionScope scope(AgentRunRequest request) {
        var context = new AgentScopeRuntimeContextFactory().create(request).get(MangaAgentRuntimeContext.class);
        var expert = request.variables().get("review_expert") instanceof AgentReviewExpert identity ? identity : null;
        boolean summary = Boolean.TRUE.equals(request.variables().get("review_completed"));
        var source = summary ? AgentToolCapability.Source.REVIEW_SUMMARY : expert != null ? AgentToolCapability.Source.EXPERT
                : context.automationClaim() != null ? AgentToolCapability.Source.AUTOMATION : AgentToolCapability.Source.INTERACTIVE;
        return new AgentToolCapability.ExecutionScope(null, context.userId(), context.storyId(), context.chapterId(),
                "OWNED_STORY_CHAPTERS", request.requestId(), 1L, request.conversationId(),
                expert == null ? AgentScopeRuntimeContextFactory.createSessionId(request) : request.variables().get("review_expert_session").toString(),
                context.stepId(), request.taskType().name(), source, "local-tools-v1", "fixture", context.executionOwner(),
                context.fencingToken(), context.automationClaim(), expert, summary ? "fixture-continuation" : "",
                summary ? "fixture-receipt" : "", "fixture-binding", "fixture-model");
    }
}
