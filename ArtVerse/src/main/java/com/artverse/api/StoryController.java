package com.artverse.api;

import com.artverse.application.StoryService;
import com.artverse.api.dto.StoryDtos.PublishRequest;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Story;
import com.artverse.media.MediaStorageService;
import com.artverse.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/stories")
@RequiredArgsConstructor
public class StoryController {

    private static final Set<String> ALLOWED_MANGA_STYLES = com.artverse.application.StorySettingsPolicy.MANGA_STYLES;

    private final StoryService storyService;
    private final MediaStorageService mediaStorageService;
    private final ObjectStorageService objectStorageService;
    private final ArtVerseProperties properties;

    @GetMapping
    public List<Story> list() {
        return storyService.listAll();
    }

    @GetMapping("/{id}")
    public Story get(@PathVariable Long id) {
        return storyService.getRequired(id);
    }

    @PostMapping
    public Story create(@RequestBody Map<String, String> body) {
        return storyService.create(body.get("title"), body.get("description"));
    }

    @PutMapping("/{id}")
    public Story update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return storyService.update(id, body.get("title"), body.get("description"), body.get("character_profiles"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        storyService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/upload-cover")
    public Map<String, String> uploadCover(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String base64 = body.get("cover_image");
        if (base64 == null || base64.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Cover image data cannot be empty");
        }

        byte[] imageData = mediaStorageService.decodeBase64Image(base64);
        mediaStorageService.validateImageBytes(imageData, properties.getUpload().getMaxImageBytes());

        String filename = mediaStorageService.generateUniqueFilename("cover", ".png");
        String objectKey = "stories/" + id + "/cover/" + filename;

        Path temp = null;
        try {
            temp = Files.createTempFile("artverse-cover-upload-", ".png");
            mediaStorageService.savePng(imageData, temp);
            objectStorageService.putPng(objectKey, temp, "image/png");
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload cover image: " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
        }

        storyService.updateCoverImage(id, objectKey);
        return Map.of("cover_image", objectKey);
    }

    @GetMapping("/{id}/manga-style")
    public Map<String, String> getMangaStyle(@PathVariable Long id) {
        return Map.of("manga_style", storyService.getMangaStyle(id));
    }

    @PutMapping("/{id}/manga-style")
    public Map<String, String> setMangaStyle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String style = body.get("manga_style");
        if (style == null || style.isBlank()) style = "japanese_manga";
        if (!ALLOWED_MANGA_STYLES.contains(style)) throw new com.artverse.common.BusinessException(400, "Invalid manga style");
        storyService.setMangaStyle(id, style);
        return Map.of("manga_style", style);
    }

    @PutMapping("/{id}/publish")
    public Story publish(@PathVariable Long id, @RequestBody PublishRequest body) {
        return storyService.publish(
                id,
                body.publicationFormat(),
                body.published(),
                body.chapterIds());
    }

    @PutMapping("/{id}/chapter-order")
    public Map<String, Object> updateChapterOrder(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) body.get("orders");
        storyService.updateChapterOrder(id, orders);
        return Map.of("success", true);
    }
}
