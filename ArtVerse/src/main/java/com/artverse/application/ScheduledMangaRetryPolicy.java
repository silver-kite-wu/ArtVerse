package com.artverse.application;

import com.artverse.domain.ScheduledMangaPanel;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

/** Pure classification of one completed provider attempt. */
public final class ScheduledMangaRetryPolicy {
    public record Decision(ScheduledMangaPanel.State state, String errorCode, boolean retryable) {}

    private ScheduledMangaRetryPolicy() {}

    public static Decision classify(Throwable error, int attemptCount, int maxAttempts) {
        boolean retryable=error instanceof WebClientResponseException response
                &&(response.getStatusCode().value()==429||response.getStatusCode().is5xxServerError());
        boolean uncertain=hasCause(error,com.artverse.ai.ImageResultUncertainException.class)||!retryable&&(hasCause(error,TimeoutException.class)
                ||hasCause(error,WebClientRequestException.class));
        if(uncertain)return new Decision(ScheduledMangaPanel.State.UNCERTAIN,"RESULT_UNCERTAIN",false);
        if(retryable&&attemptCount<maxAttempts)
            return new Decision(ScheduledMangaPanel.State.RETRY_WAIT,"PROVIDER_RETRYABLE",true);
        return new Decision(ScheduledMangaPanel.State.FAILED,retryable?"PROVIDER_RETRYABLE":"GENERATION_FAILED",retryable);
    }

    private static boolean hasCause(Throwable error,Class<? extends Throwable> type) {
        for(Throwable current=error;current!=null;current=current.getCause())if(type.isInstance(current))return true;
        return false;
    }
}
