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
    public SseEmitter generateMangaStream(@PathVariable Long chapterId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        User user = currentUser();
        GenerationGuardService.MangaStreamGuard guard = generationGuardService.guardMangaStream(user.getId(), chapterId);
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        Long assetGroupId = optionalLong(body == null ? null : body.get("assetGroupId"));
        return mangaGenerationService.generateMangaStream(chapterId, assetGroupId, user.getId(), imageApiKey, deepseekApiKey,
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

    @GetMapping("/image-request-preview")
    public Map<String, Object> previewImageRequest(@PathVariable Long chapterId,
                                                   @RequestParam(defaultValue = "1") int imageNumber,
                                                   @RequestParam(required = false) Long assetGroupId) {
        User user = currentUser();
        return mangaGenerationService.previewImageRequest(chapterId, assetGroupId, user.getId(), imageNumber);
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

    private Long optionalLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        String text = value.toString();
        if (text.isBlank()) return null;
        return Long.parseLong(text);
    }
}
