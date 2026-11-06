package com.artverse.application.workflow;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.common.BusinessException;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

class ResultFinalizerTest {

    private final MangaWorkflowExecutionContext.ExecutionClaim claim =
            new MangaWorkflowExecutionContext.ExecutionClaim("original-worker", 1L);

    @Test
    void persistsReplyOnlyAfterFactVerificationAndMarksRunVerified() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        ExecutionFactVerifier facts = mock(ExecutionFactVerifier.class);
        ResultFinalizer finalizer = new ResultFinalizer(conversations, runs, facts);
        MangaAgentConversation conversation = mock(MangaAgentConversation.class);
        UUID requestId = UUID.randomUUID();
        MangaWorkflowResult candidate = MangaWorkflowResult.success("已完成");
        ExecutionFactVerifier.VerifiedFacts verified = new ExecutionFactVerifier.VerifiedFacts(
                "review.report.v1", Map.of("route", "REVIEW", "reply_present", true));
        when(facts.verify(conversation, requestId, MangaWorkflowRoute.REVIEW, candidate)).thenReturn(verified);

        MangaWorkflowResult result = finalizer.finalizeResult(
                conversation, requestId, MangaWorkflowRoute.REVIEW, candidate, claim);

        assertThat(result.attributes()).containsEntry("result_verification_status", "PASSED")
                .containsEntry("result_schema", "review.report.v1")
                .containsEntry("verified_result", verified.facts());
        var order = inOrder(runs, facts, conversations);
        order.verify(runs).requireFinalizable(conversation, requestId, claim);
        order.verify(facts).verify(conversation, requestId, MangaWorkflowRoute.REVIEW, candidate);
        order.verify(conversations).saveMessage(conversation, MessageRole.ASSISTANT, "已完成", requestId);
        order.verify(runs).completeVerified(conversation, requestId, "已完成", false,
                "review.report.v1", verified.facts(), claim);
    }

    @Test
    void doesNotPersistCandidateReplyWhenFactVerificationFails() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        ExecutionFactVerifier facts = mock(ExecutionFactVerifier.class);
        ResultFinalizer finalizer = new ResultFinalizer(conversations, runs, facts);
        MangaAgentConversation conversation = mock(MangaAgentConversation.class);
        UUID requestId = UUID.randomUUID();
        MangaWorkflowResult candidate = MangaWorkflowResult.success("已完成");
        BusinessException failure = new BusinessException(409, "No committed storyboard artifact exists for this run");
        when(facts.verify(conversation, requestId, MangaWorkflowRoute.STORYBOARD, candidate)).thenThrow(failure);

        assertThatThrownBy(() -> finalizer.finalizeResult(
                conversation, requestId, MangaWorkflowRoute.STORYBOARD, candidate, claim)).isSameAs(failure);

        verify(conversations, never()).saveMessage(conversation, MessageRole.ASSISTANT, "已完成", requestId);
        verify(runs, never()).completeVerified(conversation, requestId, "已完成", false,
                "storyboard.outcome.v1", Map.of(), claim);
    }

    @Test
    void rejectsAnExpiredClaimBeforeReadingFactsOrPersistingTheReply() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        ExecutionFactVerifier facts = mock(ExecutionFactVerifier.class);
        ResultFinalizer finalizer = new ResultFinalizer(conversations, runs, facts);
        MangaAgentConversation conversation = mock(MangaAgentConversation.class);
        UUID requestId = UUID.randomUUID();
        BusinessException stale = new BusinessException(409, "RUN_NOT_ACTIVE");
        when(runs.requireFinalizable(conversation, requestId, claim)).thenThrow(stale);
        assertThatThrownBy(() -> finalizer.finalizeResult(conversation, requestId, MangaWorkflowRoute.REVIEW,
                MangaWorkflowResult.success("old reply"), claim)).isSameAs(stale);
        verifyNoInteractions(conversations, facts);
    }

    @Test
    void failureLocksItsOriginalClaimBeforeSavingOrChangingTheRun() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        ResultFinalizer finalizer = new ResultFinalizer(conversations, runs, mock(ExecutionFactVerifier.class));
        MangaAgentConversation conversation = mock(MangaAgentConversation.class);
        UUID requestId = UUID.randomUUID();
        finalizer.finalizeFailure(conversation, requestId, "model failed", claim);
        var order = inOrder(runs, conversations);
        order.verify(runs).requireExecutionClaim(conversation, requestId, claim);
        order.verify(conversations).saveFailureMessage(conversation, "model failed", requestId);
        order.verify(runs).markFailed(conversation, requestId, "model failed", claim);
        org.mockito.Mockito.clearInvocations(conversations);
        when(runs.requireExecutionClaim(conversation, requestId, claim)).thenThrow(new BusinessException(409, "stale"));
        assertThatThrownBy(() -> finalizer.finalizeFailure(conversation, requestId, "old failed", claim)).hasMessage("stale");
        verifyNoInteractions(conversations);
    }

    @Test
    void aDeterministicDecisionLocksItsExactPendingBeforeSavingAndCompleting() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        ResultFinalizer finalizer = new ResultFinalizer(conversations, runs, mock(ExecutionFactVerifier.class));
        MangaAgentConversation conversation = mock(MangaAgentConversation.class);
        UUID requestId = UUID.randomUUID();
        var expected = new com.artverse.application.AgentUserInputRequest("cancel?", java.util.List.of(), true, "", "CONTEXT_MISSING");
        finalizer.finalizeWaitingDecision(conversation, requestId, expected, "cancelled");
        var order = inOrder(runs, conversations);
        order.verify(runs).requireWaitingDecision(conversation, requestId, expected);
        order.verify(conversations).saveMessage(conversation, MessageRole.ASSISTANT, "cancelled", requestId);
        order.verify(runs).markSucceeded(conversation, requestId, "cancelled");

        doThrow(new BusinessException(409, "pending changed")).when(runs).requireWaitingDecision(conversation, requestId, expected);
        org.mockito.Mockito.clearInvocations(conversations);
        assertThatThrownBy(() -> finalizer.finalizeWaitingDecision(conversation, requestId, expected, "cancelled"))
                .hasMessageContaining("pending changed");
        verifyNoInteractions(conversations);
    }
}
