package com.artverse.api;

import com.artverse.application.ApiKeyService;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.CurrentUserService;
import com.artverse.guard.GenerationGuardService;
import com.artverse.application.SceneService;
import com.artverse.common.aspect.RateLimit;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class StoryboardController {

    private final SceneService sceneService;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final CurrentUserService currentUserService;
    private final ChapterAccessService chapterAccessService;

    @PostMapping("/generate-scenes")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "scenes")
    public Map<String, Object> generateScenes(@PathVariable Long chapterId) {
        User user = currentUser();
        chapterAccessService.requireVisible(chapterId, user.getId());
        String cozeApiKey = apiKeyService.getDecryptedKey(user, "coze");
        return generationGuardService.executeSceneGeneration(
                user.getId(),
                chapterId,
                () -> Map.of("scenes", sceneService.generateScenes(chapterId, cozeApiKey))
        );
    }

    @GetMapping("/scenes")
    public Map<String, List<String>> getScenes(@PathVariable Long chapterId) {
        User user = currentUser();
        chapterAccessService.requireVisible(chapterId, user.getId());
        List<String> scenes = sceneService.getScenes(chapterId);
        return Map.of("scenes", scenes);
    }

    @PutMapping("/scenes")
    public Map<String, List<String>> updateScenes(@PathVariable Long chapterId, @RequestBody List<String> scenes) {
        User user = currentUser();
        chapterAccessService.requireVisible(chapterId, user.getId());
        List<String> updated = sceneService.updateScenes(chapterId, scenes);
        return Map.of("scenes", updated);
    }

    private User currentUser() {
        return currentUserService.requireCurrentUser();
    }
}
