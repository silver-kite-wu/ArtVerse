package com.artverse.api;

import com.artverse.application.ApiKeyService;
import com.artverse.application.CurrentUserService;
import com.artverse.guard.GenerationGuardService;
import com.artverse.application.MangaGenerationService;
import com.artverse.domain.MangaImage;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class MangaGenerationController {

    private final MangaGenerationService mangaGenerationService;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final CurrentUserService currentUserService;

    @PostMapping("/generate-manga-stream")
    public SseEmitter generateMangaStream(@PathVariable Long chapterId) {
        User user = currentUser();
        GenerationGuardService.MangaStreamGuard guard = generationGuardService.guardMangaStream(user.getId(), chapterId);
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        return mangaGenerationService.generateMangaStream(chapterId, imageApiKey, deepseekApiKey,
                guard.onComplete(),
                guard.onError());
    }

    @PostMapping("/regenerate-image/{imageNumber}")
    public MangaImage regenerateImage(@PathVariable Long chapterId,
                                      @PathVariable int imageNumber,
                                      @RequestBody Map<String, String> body) {
        User user = currentUser();
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        String prompt = body.get("prompt");
        Map<String, Object> result = generationGuardService.executeImageRegeneration(
                user.getId(),
                chapterId,
                imageNumber,
                prompt,
                () -> mangaImageToMap(mangaGenerationService.regenerateImage(chapterId, imageNumber, prompt, imageApiKey, deepseekApiKey))
        );
        return mapToMangaImage(result);
    }

    private Map<String, Object> mangaImageToMap(MangaImage image) {
        return Map.of(
                "id", image.getId(),
                "image_number", image.getImageNumber(),
                "image_path", image.getImagePath(),
                "prompt", image.getPrompt() == null ? "" : image.getPrompt()
        );
    }

    private MangaImage mapToMangaImage(Map<String, Object> map) {
        MangaImage image = new MangaImage();
        image.setId(((Number) map.get("id")).longValue());
        image.setImageNumber(((Number) map.get("image_number")).intValue());
        image.setImagePath(String.valueOf(map.get("image_path")));
        image.setPrompt(String.valueOf(map.getOrDefault("prompt", "")));
        return image;
    }

    private User currentUser() {
        return currentUserService.requireCurrentUser();
    }
}
