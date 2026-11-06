package com.artverse.application.workflow;

import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.MangaAgentRunService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolContractVerifierTest {
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID ARTIFACT = UUID.randomUUID();

    private final ToolContractVerifier verifier = new ToolContractVerifier(mock(MangaAgentRunService.class));

    @Test
    void storyboardStepRequiresValidatedDraftBeforeSingleCommit() {
        ToolExpectation expectation = new ToolExpectation(
                "plan-a:0",
                MangaWorkflowRoute.STORYBOARD,
                RoutingDecision.ExpectedToolPolicy.WRITE_WITH_HITL,
                java.util.Set.of("draft_structured_storyboard", "commit_storyboard"),
                java.util.Set.of("generate_storyboard", "save_storyboard", "save_structured_storyboard"),
                Map.of("commit_storyboard", 1),
                true
        );
        List<AgentRunToolStatus.ToolEvent> events = List.of(
                success("plan-a:0", "draft_structured_storyboard", receipt(ARTIFACT, false)),
                success("plan-a:0", "commit_storyboard", receipt(ARTIFACT, true))
        );

        ToolContractVerifier.VerificationOutcome outcome =
                verifier.verify(expectation, events, MangaWorkflowResult.success("done"));

        assertThat(outcome.status()).isEqualTo(ToolContractVerifier.VerificationStatus.PASSED);
    }

    @Test
    void validDraftCannotProveACommitOfAnotherArtifact() {
        var outcome = verifier.verify(storyboardExpectation(), List.of(
                success("plan-a:0", "draft_structured_storyboard", receipt(ARTIFACT, false)),
                success("plan-a:0", "commit_storyboard", receipt(UUID.randomUUID(), true))), MangaWorkflowResult.success("done"));
        assertThat(outcome.status()).isEqualTo(ToolContractVerifier.VerificationStatus.FAILED);
        assertThat(outcome.reasons()).contains("missing_valid_draft");
    }

    @Test
    void failedOrUncommittedResultCannotProveASave() {
        var outcome = verifier.verify(storyboardExpectation(), List.of(
                success("plan-a:0", "draft_structured_storyboard", receipt(ARTIFACT, false)),
                success("plan-a:0", "commit_storyboard", receipt(ARTIFACT, false))), MangaWorkflowResult.success("done"));
        assertThat(outcome.reasons()).contains("missing_required_tool:commit_storyboard");
    }

    @Test
    void changedHashBaseOrRunCannotBorrowDraftEvidence() {
        for (String field : List.of("content_hash", "base_version", "request_id", "step_id")) {
            var commit = new java.util.LinkedHashMap<>(receipt(ARTIFACT, true));
            commit.put(field, "changed");
            var outcome = verifier.verify(storyboardExpectation(), List.of(
                    success("plan-a:0", "draft_structured_storyboard", receipt(ARTIFACT, false)),
                    success("plan-a:0", "commit_storyboard", commit)), MangaWorkflowResult.success("done"));
            assertThat(outcome.status()).as(field).isEqualTo(ToolContractVerifier.VerificationStatus.FAILED);
        }
    }

    @Test
    void replayOfTheSameCommittedReceiptIsOneBusinessEffect() {
        var commit = success("plan-a:0", "commit_storyboard", receipt(ARTIFACT, true));
        var outcome = verifier.verify(storyboardExpectation(), List.of(
                success("plan-a:0", "draft_structured_storyboard", receipt(ARTIFACT, false)), commit, commit),
                MangaWorkflowResult.success("done"));
        assertThat(outcome.status()).isEqualTo(ToolContractVerifier.VerificationStatus.PASSED);
    }

    private ToolExpectation storyboardExpectation() {
        return new ToolExpectation("plan-a:0", MangaWorkflowRoute.STORYBOARD,
                RoutingDecision.ExpectedToolPolicy.WRITE_WITH_HITL,
                java.util.Set.of("draft_structured_storyboard", "commit_storyboard"), java.util.Set.of(),
                Map.of("commit_storyboard", 1), true);
    }

    private Map<String,Object> receipt(UUID id, boolean committed) {
        return Map.of("artifact_id", id.toString(), "content_hash", "hash", "base_version", 7L,
                "request_id", REQUEST.toString(), "step_id", "plan-a:0", "validated", true,
                "saved", committed, "artifact_status", committed ? "COMMITTED" : "VALIDATED");
    }

    @Test
    void storyboardStepFailsWithoutCommit() {
        ToolExpectation expectation = new ToolExpectation(
                "plan-a:0",
                MangaWorkflowRoute.STORYBOARD,
                RoutingDecision.ExpectedToolPolicy.WRITE_WITH_HITL,
                java.util.Set.of("draft_structured_storyboard", "commit_storyboard"),
                java.util.Set.of("generate_storyboard", "save_storyboard", "save_structured_storyboard"),
                Map.of("commit_storyboard", 1),
                true
        );
        List<AgentRunToolStatus.ToolEvent> events = List.of(
                success("plan-a:0", "draft_structured_storyboard", Map.of("validated", true))
        );

        ToolContractVerifier.VerificationOutcome outcome =
                verifier.verify(expectation, events, MangaWorkflowResult.success("done"));

        assertThat(outcome.status()).isEqualTo(ToolContractVerifier.VerificationStatus.FAILED);
        assertThat(outcome.reasons()).contains("missing_required_tool:commit_storyboard");
    }

    @Test
    void readOnlyStepDegradesWhenToolFailureIsMaskedBySuccessReply() {
        ToolExpectation expectation = new ToolExpectation(
                "manga-conversation",
                MangaWorkflowRoute.CONVERSATION,
                RoutingDecision.ExpectedToolPolicy.READ_ONLY_CONTEXT,
                java.util.Set.of(),
                java.util.Set.of("generate_storyboard", "save_storyboard", "save_structured_storyboard", "commit_storyboard"),
                Map.of(),
                false
        );
        List<AgentRunToolStatus.ToolEvent> events = List.of(
                failed("manga-conversation", "get_chapter_context", "context missing")
        );

        ToolContractVerifier.VerificationOutcome outcome =
                verifier.verify(expectation, events, MangaWorkflowResult.success("all good"));

        assertThat(outcome.status()).isEqualTo(ToolContractVerifier.VerificationStatus.DEGRADED);
        assertThat(outcome.reasons()).contains("tool_failure_masked_by_success:get_chapter_context");
    }

    private AgentRunToolStatus.ToolEvent success(String stepId, String toolName, Map<String, Object> result) {
        return new AgentRunToolStatus.ToolEvent(
                REQUEST, stepId, toolName, "SUCCEEDED", true,
                10L, "hash", null, "audit", result, OffsetDateTime.now());
    }

    private AgentRunToolStatus.ToolEvent failed(String stepId, String toolName, String error) {
        return new AgentRunToolStatus.ToolEvent(
                UUID.randomUUID(), stepId, toolName, "FAILED", false,
                10L, null, error, "audit", Map.of(), OffsetDateTime.now());
    }
}
