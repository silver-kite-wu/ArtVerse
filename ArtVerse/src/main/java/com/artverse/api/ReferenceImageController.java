package com.artverse.api;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.StoryAssetGroup;
import com.artverse.media.MediaStorageService;
import com.artverse.application.ChapterAccessService;
import com.artverse.persistence.StoryAssetGroupRepository;
import com.artverse.persistence.StoryRepository;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReferenceImageController {

    private final StoryRepository storyRepository;
    private final StoryAssetGroupRepository assetGroupRepository;
    private final ChapterAccessService chapterAccessService;
    private final MediaStorageService mediaStorageService;
    private final ObjectStorageService objectStorageService;
    private final ArtVerseProperties properties;

    @Transactional(readOnly = true)
    @GetMapping("/stories/{storyId}/ref-images")
    public Map<String, Object> getStoryRefImages(@PathVariable Long storyId) {
        storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, currentUserId())
                .orElseThrow(() -> new BusinessException(404, "Story not found"));

        Map<String, Object> result = listImages(storyRefPrefix(storyId));
        result.put("source", imagesEmpty(result) ? "none" : "story");
        return result;
    }

    @Transactional(readOnly = true)
    @GetMapping("/chapters/{chapterId}/ref-images")
    public Map<String, Object> getChapterRefImages(@PathVariable Long chapterId) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, currentUserId());

        Map<String, Object> result = listImages(chapterRefPrefix(chapter));
        if (!imagesEmpty(result)) {
            result.put("source", "chapter");
            return result;
        }

        if (chapter.getRefImage() != null && !chapter.getRefImage().isBlank()) {
            result = legacyImage(chapter.getRefImage());
            result.put("source", "chapter");
            return result;
        }

        if (chapter.getAssetGroup() != null) {
            result = listImages(assetGroupRefPrefix(chapter.getStory().getId(), chapter.getAssetGroup().getId()));
            if (!imagesEmpty(result)) {
                result.put("source", "asset_group");
                return result;
            }
        }

        result = listImages(storyRefPrefix(chapter.getStory().getId()));
        if (!imagesEmpty(result)) {
            result.put("source", "story");
            return result;
        }

        if (chapter.getStory().getRefImage() != null && !chapter.getStory().getRefImage().isBlank()) {
            result = legacyImage(chapter.getStory().getRefImage());
            result.put("source", "story");
            return result;
        }

        result.put("source", "none");
        return result;
    }

    @PostMapping("/stories/{storyId}/ref-images")
    public Map<String, Object> addStoryRefImage(@PathVariable Long storyId, @RequestBody Map<String, String> body) {
        storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, currentUserId())
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
        uploadRefImage(storyRefPrefix(storyId), body);

        Map<String, Object> result = listImages(storyRefPrefix(storyId));
        result.put("source", "story");
        return result;
    }

    @DeleteMapping("/stories/{storyId}/ref-images/{filename}")
    public Map<String, Object> deleteStoryRefImage(@PathVariable Long storyId, @PathVariable String filename) {
        storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, currentUserId())
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
        deleteRefImage(storyRefPrefix(storyId), filename);

        Map<String, Object> result = listImages(storyRefPrefix(storyId));
        result.put("source", "story");
        return result;
    }

    @Transactional
    @PostMapping("/chapters/{chapterId}/ref-images")
    public Map<String, Object> addChapterRefImage(@PathVariable Long chapterId, @RequestBody Map<String, String> body) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, currentUserId());
        uploadRefImage(chapterRefPrefix(chapter), body);

        Map<String, Object> result = listImages(chapterRefPrefix(chapter));
        result.put("source", "chapter");
        return result;
    }

    @Transactional
    @DeleteMapping("/chapters/{chapterId}/ref-images/{filename}")
    public Map<String, Object> deleteChapterRefImage(@PathVariable Long chapterId, @PathVariable String filename) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, currentUserId());
        deleteRefImage(chapterRefPrefix(chapter), filename);

        Map<String, Object> result = listImages(chapterRefPrefix(chapter));
        result.put("source", "chapter");
        return result;
    }

    @Transactional
    @PostMapping("/stories/{storyId}/asset-groups/{groupId}/ref-images")
    public Map<String, Object> addAssetGroupRefImage(@PathVariable Long storyId, @PathVariable Long groupId,
                                                     @RequestBody Map<String, String> body) {
        StoryAssetGroup group = assetGroupRepository.findByIdAndUserId(groupId, currentUserId())
                .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
        if (!group.getStory().getId().equals(storyId)) {
            throw new BusinessException(400, "Asset group does not belong to this story");
        }
        String prefix = assetGroupRefPrefix(storyId, groupId);
        uploadRefImage(prefix, body);

        Map<String, Object> result = listImages(prefix);
        result.put("source", "asset_group");
        return result;
    }

    @Transactional
    @DeleteMapping("/stories/{storyId}/asset-groups/{groupId}/ref-images/{filename}")
    public Map<String, Object> deleteAssetGroupRefImage(@PathVariable Long storyId, @PathVariable Long groupId,
                                                        @PathVariable String filename) {
        StoryAssetGroup group = assetGroupRepository.findByIdAndUserId(groupId, currentUserId())
                .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
        if (!group.getStory().getId().equals(storyId)) {
            throw new BusinessException(400, "Asset group does not belong to this story");
        }
        String prefix = assetGroupRefPrefix(storyId, groupId);
        deleteRefImage(prefix, filename);

        Map<String, Object> result = listImages(prefix);
        result.put("source", "asset_group");
        return result;
    }

    private Map<String, Object> listImages(String prefix) {
        int max = properties.getRef().getMaxImagesPerLevel();
        List<Map<String, Object>> images = objectStorageService.list(properties.getMinio().getBucket(), prefix, max).stream()
                .filter(o -> isImageObject(o.objectKey()))
                .sorted(Comparator.comparing(StoredObject::objectKey))
                .map(o -> Map.<String, Object>of(
                        "filename", Path.of(o.objectKey()).getFileName().toString(),
                        "image_path", o.objectKey(),
                        "size_kb", o.sizeBytes() / 1024
                ))
                .toList();
        Map<String, Object> result = new HashMap<>();
        result.put("images", images);
        result.put("max", max);
        return result;
    }

    private Map<String, Object> legacyImage(String imagePath) {
        Map<String, Object> result = new HashMap<>();
        result.put("images", List.of(Map.of(
                "filename", Path.of(imagePath).getFileName().toString(),
                "image_path", imagePath,
                "size_kb", 0
        )));
        result.put("max", properties.getRef().getMaxImagesPerLevel());
        return result;
    }

    private void uploadRefImage(String prefix, Map<String, String> body) {
        int max = properties.getRef().getMaxImagesPerLevel();
        if (objectStorageService.list(properties.getMinio().getBucket(), prefix, max).stream()
                .filter(o -> isImageObject(o.objectKey()))
                .count() >= max) {
            throw new BusinessException(400, "已达到最大垫图数量限制 (" + max + " 张)");
        }

        byte[] imageData = mediaStorageService.decodeBase64Image(body.get("image"));
        mediaStorageService.validateImageBytes(imageData, properties.getUpload().getMaxImageBytes());
        String filename = mediaStorageService.generateUniqueFilename("ref", ".png");
        Path temp = null;
        try {
            temp = Files.createTempFile("artverse-ref-upload-", ".png");
            mediaStorageService.savePng(imageData, temp);
            objectStorageService.putPng(prefix + filename, temp, "image/png");
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload ref image: " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void deleteRefImage(String prefix, String filename) {
        mediaStorageService.validateImagePath(filename);
        String safeFilename = Path.of(filename).getFileName().toString();
        objectStorageService.deleteBestEffort(properties.getMinio().getBucket(), prefix + safeFilename);
    }

    @SuppressWarnings("unchecked")
    private boolean imagesEmpty(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("images")).isEmpty();
    }

    private String storyRefPrefix(Long storyId) {
        return "stories/" + storyId + "/ref_images/";
    }

    private String chapterRefPrefix(Chapter chapter) {
        return "stories/" + chapter.getStory().getId() + "/chapters/" + chapter.getId() + "/ref_images/";
    }

    private String assetGroupRefPrefix(Long storyId, Long groupId) {
        return "stories/" + storyId + "/asset_groups/" + groupId + "/ref_images/";
    }

    private boolean isImageObject(String objectKey) {
        String name = objectKey.toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    private Long currentUserId() {
        return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
    }
}
