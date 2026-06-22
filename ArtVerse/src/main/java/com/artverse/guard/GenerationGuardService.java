package com.artverse.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class GenerationGuardService {

    private final IdempotencyService idempotencyService;
    private final GenerationRequestKeyBuilder keyBuilder;
    private final MangaGenerationConcurrencyGate mangaGenerationConcurrencyGate;

    public Map<String, Object> executeImageGeneration(Long userId, String prompt, java.util.List<String> referenceImages,
                                                      Callable<Map<String, Object>> leader) {
        return idempotencyService.executeHttp(
                "image-gen",
                userKey(userId),
                keyBuilder.imageGeneration(userId, prompt, referenceImages),
                leader
        );
    }

    public Map<String, Object> executeSceneGeneration(Long userId, Long chapterId,
                                                      Callable<Map<String, Object>> leader) {
        return idempotencyService.executeHttp(
                "generate-scenes",
                userKey(userId),
                keyBuilder.sceneGeneration(userId, chapterId),
                leader
        );
    }

    public Map<String, Object> executeMangaAgentRun(Long userId, Long chapterId, String requestId, String message,
                                                    String provider, String model, String baseUrlHash,
                                                    Callable<Map<String, Object>> leader) {
        return idempotencyService.executeHttp(
                "manga-agent-run",
                userKey(userId),
                keyBuilder.mangaAgentRun(userId, chapterId, requestId, message, provider, model, baseUrlHash),
                leader
        );
    }

    public Map<String, Object> executeImageRegeneration(Long userId, Long chapterId, int imageNumber, String prompt,
                                                        Callable<Map<String, Object>> leader) {
        return idempotencyService.executeHttp(
                "regenerate-image",
                userKey(userId),
                keyBuilder.imageRegeneration(userId, chapterId, imageNumber, prompt),
                leader
        );
    }

    public MangaStreamGuard guardMangaStream(Long userId, Long chapterId) {
        Map<String, Object> canonical = keyBuilder.mangaGeneration(userId, chapterId);
        idempotencyService.rejectIfProcessing("generate-manga", userKey(userId), canonical);
        idempotencyService.markProcessing("generate-manga", userKey(userId), canonical);
        try {
            mangaGenerationConcurrencyGate.acquireOrReject();
        } catch (RuntimeException e) {
            idempotencyService.markFailed("generate-manga", userKey(userId), canonical, e.getMessage());
            throw e;
        }
        return new MangaStreamGuard(
                () -> {
                    try {
                        idempotencyService.markSucceeded("generate-manga", userKey(userId), canonical, Map.of("chapter_id", chapterId));
                    } finally {
                        mangaGenerationConcurrencyGate.release();
                    }
                },
                error -> {
                    try {
                        idempotencyService.markFailed("generate-manga", userKey(userId), canonical, error);
                    } finally {
                        mangaGenerationConcurrencyGate.release();
                    }
                }
        );
    }

    private String userKey(Long userId) {
        return "u" + userId;
    }

    public record MangaStreamGuard(Runnable onComplete, Consumer<String> onError) {
    }
}
