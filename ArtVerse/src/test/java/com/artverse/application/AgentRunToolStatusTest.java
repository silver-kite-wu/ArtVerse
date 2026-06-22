package com.artverse.application;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunToolStatusTest {

    private final AgentRunToolStatus status = new AgentRunToolStatus();

    @Test
    void recordsToolEventOnlyForMatchingRequestId() {
        UUID firstRequest = UUID.randomUUID();
        UUID secondRequest = UUID.randomUUID();

        try (AgentRunToolStatus.RunScope first = status.start(1L, 7L, firstRequest);
             AgentRunToolStatus.RunScope second = status.start(1L, 7L, secondRequest)) {
            status.recordSucceeded("save_structured_storyboard", 1L, 7L, secondRequest, 12L,
                    Map.of("saved", true));

            assertThat(first.state().events()).isEmpty();
            assertThat(second.state().events()).hasSize(1);
            assertThat(second.state().hasSuccessfulMutatingTool()).isTrue();
        }
    }

    @Test
    void fallbackRecordingDoesNotBroadcastWhenMultipleRunsAreActive() {
        UUID firstRequest = UUID.randomUUID();
        UUID secondRequest = UUID.randomUUID();

        try (AgentRunToolStatus.RunScope first = status.start(1L, 7L, firstRequest);
             AgentRunToolStatus.RunScope second = status.start(1L, 7L, secondRequest)) {
            status.recordSucceeded("save_structured_storyboard", 1L, 7L, 12L, Map.of("saved", true));

            assertThat(first.state().events()).isEmpty();
            assertThat(second.state().events()).isEmpty();
        }
    }

    @Test
    void fallbackRecordingStillSupportsSingleActiveRun() {
        UUID requestId = UUID.randomUUID();

        try (AgentRunToolStatus.RunScope scope = status.start(1L, 7L, requestId)) {
            status.recordSucceeded("save_structured_storyboard", 1L, 7L, 12L, Map.of("saved", true));

            assertThat(scope.state().events()).hasSize(1);
        }
    }
}
