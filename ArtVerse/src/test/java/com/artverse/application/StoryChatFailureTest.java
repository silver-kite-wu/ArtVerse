package com.artverse.application;

import com.artverse.common.BusinessException;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StoryChatFailureTest {
    @Test
    void wrappedProvider400KeepsStatusWithoutLeakingResponseBody() {
        var error = new IllegalStateException(OpenAIException.create(400, "raw private prompt", "invalid_request", "private provider body"));
        var failure = StoryChatFailure.from(error, "WAITING_MODEL");
        assertThat(failure.message()).contains("HTTP 400").doesNotContain("private", "raw");
        assertThat(failure.stage()).isEqualTo("MODEL");
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.metadata()).containsEntry("upstreamStatus", 400);
    }

    @Test
    void paymentFailureExplainsProviderBalanceAndIsNotAutomaticallyRetryable() {
        var failure = StoryChatFailure.from(OpenAIException.create(402, "private", "payment_required", ""), "MODEL");
        assertThat(failure.message()).contains("HTTP 402", "余额或配额").doesNotContain("private");
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    void throttlingIsRetryableAndVersionConflictRemainsBusinessRejection() {
        assertThat(StoryChatFailure.from(OpenAIException.create(429, "busy", "rate_limit", ""), "MODEL").retryable()).isTrue();
        var conflict = StoryChatFailure.from(new BusinessException(409, "正文版本已变化"), "TOOL");
        assertThat(conflict.message()).isEqualTo("正文版本已变化");
        assertThat(conflict.code()).isEqualTo("STORY_CHAT_REJECTED");
    }
}
