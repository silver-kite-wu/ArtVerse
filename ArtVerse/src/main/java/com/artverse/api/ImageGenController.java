package com.artverse.api;

import com.artverse.application.ImageGenService;
import com.artverse.application.ApiKeyService;
import com.artverse.guard.GenerationGuardService;
import com.artverse.application.CurrentUserService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/image-gen")
@RequiredArgsConstructor
public class ImageGenController {

    private final ImageGenService imageGenService;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final CurrentUserService currentUserService;

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        String prompt = body.get("prompt") != null ? body.get("prompt").toString() : "";
        @SuppressWarnings("unchecked")
        List<String> referenceImages = (List<String>) body.get("reference_images");
        User user = currentUser();
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        return generationGuardService.executeImageGeneration(
                user.getId(),
                prompt,
                referenceImages,
                () -> imageGenService.generate(prompt, referenceImages, imageApiKey)
        );
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return imageGenService.listHistory(page, size);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        imageGenService.delete(id);
    }

    private User currentUser() {
        return currentUserService.requireCurrentUser();
    }
}
