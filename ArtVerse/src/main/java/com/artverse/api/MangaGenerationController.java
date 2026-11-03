package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.MangaGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class MangaGenerationController {
    private final ImageGenerationRequestController requests;
    private final MangaGenerationService mangaGenerationService;
    private final CurrentUserService currentUserService;

    /** Compatibility URL; POST now ends at 202. Results use the returned GET/SSE URLs. */
    @PostMapping("/generate-manga-stream")
    public ResponseEntity<Map<String,Object>> generateMangaStream(@PathVariable Long chapterId,
            @RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return requests.submit(currentUserService.requireCurrentUser().getId(),chapterId,null,"MANGA_GENERATE",body,key);
    }

    @PostMapping("/regenerate-image/{imageNumber}")
    public ResponseEntity<Map<String,Object>> regenerateImage(@PathVariable Long chapterId,@PathVariable int imageNumber,
            @RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return requests.submit(currentUserService.requireCurrentUser().getId(),chapterId,imageNumber,"MANGA_REGENERATE",body,key);
    }

    @GetMapping("/image-request-preview")
    public Map<String,Object> previewImageRequest(@PathVariable Long chapterId,
            @RequestParam(defaultValue="1") int imageNumber,@RequestParam(required=false) Long assetGroupId) {
        return mangaGenerationService.previewImageRequest(chapterId,assetGroupId,currentUserService.requireCurrentUser().getId(),imageNumber);
    }
}
