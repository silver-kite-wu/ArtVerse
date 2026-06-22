package com.artverse.api;

import com.artverse.api.dto.ChapterDto;
import com.artverse.application.ApiKeyService;
import com.artverse.application.CurrentUserService;
import com.artverse.application.NovelService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class NovelController {

    private final NovelService novelService;
    private final CurrentUserService currentUserService;
    private final ApiKeyService apiKeyService;

    @PostMapping("/generate-novel")
    public Map<String, String> generateNovel(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        String content = novelService.generateNovel(chapterId, user.getId(), deepseekApiKey);
        return Map.of("novel_content", content);
    }

    @PostMapping("/import-novel")
    public ChapterDto importNovel(@PathVariable Long chapterId, @RequestBody Map<String, String> body) {
        return ChapterDto.from(novelService.importNovel(chapterId, body.get("content")));
    }
}
