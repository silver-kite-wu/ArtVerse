package com.artverse.ai;

import com.artverse.application.UserProviderConfig;
import reactor.core.publisher.Mono;

public interface Image2Client {

    Mono<GeneratedImage> generate(ImageGenerationRequest request, UserProviderConfig providerConfig);

    /** Durable callers own retries; a disconnected paid request must never be replayed internally. */
    default Mono<GeneratedImage> generateScheduled(ImageGenerationRequest request, UserProviderConfig providerConfig) {
        return generate(request, providerConfig);
    }
}
