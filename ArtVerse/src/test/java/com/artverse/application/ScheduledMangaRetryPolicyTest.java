package com.artverse.application;

import com.artverse.domain.ScheduledMangaPanel;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.assertThat;

class ScheduledMangaRetryPolicyTest {
    @Test
    void retriesProviderRateLimitsAndServerErrorsUntilTheAttemptLimit() {
        var rateLimit=WebClientResponseException.create(429,"rate limited",null,null,null);
        var serverError=WebClientResponseException.create(503,"unavailable",null,null,null);

        assertThat(ScheduledMangaRetryPolicy.classify(rateLimit,1,3))
                .isEqualTo(new ScheduledMangaRetryPolicy.Decision(ScheduledMangaPanel.State.RETRY_WAIT,"PROVIDER_RETRYABLE",true));
        assertThat(ScheduledMangaRetryPolicy.classify(serverError,3,3))
                .isEqualTo(new ScheduledMangaRetryPolicy.Decision(ScheduledMangaPanel.State.FAILED,"PROVIDER_RETRYABLE",true));
    }

    @Test
    void marksNetworkTimeoutAsUncertainAndPermanentClientErrorsAsFailed() {
        assertThat(ScheduledMangaRetryPolicy.classify(new TimeoutException("late provider response"),1,3))
                .isEqualTo(new ScheduledMangaRetryPolicy.Decision(ScheduledMangaPanel.State.UNCERTAIN,"RESULT_UNCERTAIN",false));
        assertThat(ScheduledMangaRetryPolicy.classify(
                WebClientResponseException.create(400,"invalid request",null,null,null),1,3))
                .isEqualTo(new ScheduledMangaRetryPolicy.Decision(ScheduledMangaPanel.State.FAILED,"GENERATION_FAILED",false));
    }
}
