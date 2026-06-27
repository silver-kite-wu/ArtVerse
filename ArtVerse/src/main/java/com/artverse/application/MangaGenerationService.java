package com.artverse.application;

import com.artverse.ai.GeneratedImage;
import com.artverse.ai.Image2Client;
import com.artverse.ai.ImageGenerationRequest;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.StoryAssetGroupRepository;
import com.artverse.prompt.MangaPromptPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaGenerationService {

    private final ChapterRepository chapterRepository;
    private final Image2Client image2Client;
    private final MangaImageStorageService mangaImageStorageService;
    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;
    private final CharacterProfileService characterProfileService;
    private final StoryAssetGroupRepository storyAssetGroupRepository;
    private final ArtVerseProperties properties;
    private final ObjectMapper objectMapper;

    private final Map<Long, MangaGenerationJob> activeJobs = new ConcurrentHashMap<>();

    @Transactional
    public SseEmitter generateMangaStream(Long chapterId, String imageApiKey, String deepseekApiKey) {
        return generateMangaStream(chapterId, null, null, imageApiKey, deepseekApiKey, () -> {}, error -> {});
    }

    @Transactional
    public SseEmitter generateMangaStream(Long chapterId, Long assetGroupId, Long userId,
                                          String imageApiKey, String deepseekApiKey,
                                          Runnable onComplete, Consumer<String> onError) {
        Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        if (assetGroupId != null) {
            StoryAssetGroup assetGroup = storyAssetGroupRepository.findByIdAndUserIdWithCharacters(assetGroupId, userId)
                    .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
            if (!assetGroup.getStory().getId().equals(chapter.getStory().getId())) {
                throw new BusinessException(400, "Asset group does not belong to this chapter's story");
            }
            chapter.setAssetGroup(assetGroup);
        }

        // Check if already running
        MangaGenerationJob existingJob = activeJobs.get(chapterId);
        if (existingJob != null && existingJob.isRunning()) {
            SseEmitter emitter = new SseEmitter(0L);
            existingJob.addSubscriber(emitter);
            return emitter;
        }

        List<String> scenes = resolveScenesForImageGeneration(chapter);
        if (scenes.size() != chapter.getImageCount()) {
            throw new BusinessException(400, "Scenes count (" + scenes.size() + ") does not match image count (" + chapter.getImageCount() + ")");
        }
        validateScenesForMangaGeneration(scenes);

        // Eagerly resolve lazy proxies before handing off to background thread
        Long storyId = chapter.getStory().getId();
        String mangaStyle = chapter.getStory().getMangaStyle();
        String storyRefImage = chapter.getStory().getRefImage();
        Long effectiveAssetGroupId = chapter.getAssetGroup() != null ? chapter.getAssetGroup().getId() : null;
        String generationProfiles = buildGenerationProfiles(chapter, chapter.getAssetGroup());
        String chapterRefImage = chapter.getRefImage();

        MangaGenerationJob job = new MangaGenerationJob(chapterId, scenes);
        activeJobs.put(chapterId, job);

        SseEmitter emitter = new SseEmitter(0L);
        job.addSubscriber(emitter);

        try {
            executor.submit(() -> runGenerationJob(job, storyId, mangaStyle, storyRefImage, effectiveAssetGroupId,
                    chapter.getId(), chapter.getColorMode().name().toLowerCase(), chapterRefImage, generationProfiles,
                    imageApiKey, deepseekApiKey, onComplete, onError));
        } catch (RuntimeException e) {
            activeJobs.remove(chapterId);
            throw e;
        }

        return emitter;
    }

    private void runGenerationJob(MangaGenerationJob job, Long storyId, String mangaStyle,
                                   String storyRefImage, Long assetGroupId, Long chapterId, String colorMode,
                                   String chapterRefImage, String profiles,
                                   String imageApiKey, String deepseekApiKey,
                                   Runnable onComplete, Consumer<String> onError) {
        try {
            // Send scenes event
            job.broadcastEvent("scenes", objectMapper.writeValueAsString(Map.of("scenes", job.getScenes())));

            if (mangaStyle == null || mangaStyle.isBlank()) mangaStyle = "japanese_manga";

            MangaImageStorageService.ReferenceImages referenceImages = mangaImageStorageService.prepareReferenceImages(
                    storyId, chapterId, chapterRefImage, assetGroupId, storyRefImage);
            List<Path> imageRequestRefs = referenceImages.requestRefs();
            boolean hasRefImages = referenceImages.hasRefs();

            try {
                for (int i = 0; i < job.getScenes().size(); i++) {
                    if (!job.isRunning()) break;

                    int imageNumber = i + 1;
                    String scene = job.getScenes().get(i);

                    // Check if image already exists
                    Optional<MangaImage> existing = mangaImageStorageService.findPanel(chapterId, imageNumber);
                    if (existing.isPresent()) {
                        MangaImage img = existing.get();
                        String url = "/static/manga/" + img.getImagePath();
                        job.broadcastEvent("progress", objectMapper.writeValueAsString(Map.of(
                                "image_number", imageNumber,
                                "total", job.getScenes().size()
                        )));
                        job.broadcastEvent("image", objectMapper.writeValueAsString(Map.of(
                                "image_number", imageNumber,
                                "image_path", img.getImagePath(),
                                "url", url
                        )));
                        continue;
                    }

                    String prompt = MangaPromptPolicy.buildImagePrompt(
                            scene, profiles, mangaStyle, colorMode, hasRefImages, job.getScenes(), imageNumber);

                    // Retry up to 3 times
                    Exception lastException = null;
                    boolean success = false;
                    for (int attempt = 0; attempt < 3; attempt++) {
                        if (!job.isRunning()) break;
                        try {
                            // Generate image
                            GeneratedImage generated = generateImageForJob(imageRequestRefs, imageApiKey, prompt, colorMode);

                            // Upload to MinIO
                            MangaImage mangaImage = mangaImageStorageService.saveGeneratedPanel(
                                    chapterId, storyId, imageNumber, generated.localFile(), prompt);

                            // Find existing or create new — update in place to avoid dup key on retry

                            // Send progress (after successful generation)
                            job.broadcastEvent("progress", objectMapper.writeValueAsString(Map.of(
                                    "image_number", imageNumber,
                                    "total", job.getScenes().size()
                            )));

                            // Send image event
                            String url = "/static/manga/" + mangaImage.getImagePath();
                            job.broadcastEvent("image", objectMapper.writeValueAsString(Map.of(
                                    "image_number", imageNumber,
                                    "image_path", mangaImage.getImagePath(),
                                    "url", url
                            )));

                            // Cleanup temp file
                            mangaImageStorageService.cleanupTempFile(generated.localFile());
                            success = true;
                            break;
                        } catch (Exception e) {
                            lastException = e;
                            log.warn("Failed to generate image {}/{} for chapter {} (attempt {}/3): {}",
                                    imageNumber, job.getScenes().size(), chapterId, attempt + 1, e.getMessage());
                        }
                    }

                    if (!success && lastException != null) {
                        log.error("Failed to generate image {}/{} for chapter {} after 3 attempts: {}",
                                imageNumber, job.getScenes().size(), chapterId, lastException.getMessage());
                        try {
                            job.broadcastEvent("image_error", objectMapper.writeValueAsString(Map.of(
                                    "image_number", imageNumber,
                                    "total", job.getScenes().size(),
                                    "error", lastException.getMessage()
                            )));
                        } catch (Exception ignored) {
                        }
                    }
                }
            } finally {
                mangaImageStorageService.cleanupTempFiles(referenceImages.tempRefs());
            }

            // Send done
            job.broadcastEvent("done", objectMapper.writeValueAsString(Map.of("images", job.getScenes().size())));
            job.complete();
            onComplete.run();

        } catch (Exception e) {
            log.error("Manga generation failed for chapter {}: {}", chapterId, e.getMessage(), e);
            try {
                job.broadcastEvent("error", objectMapper.writeValueAsString(Map.of("detail", e.getMessage())));
            } catch (Exception ignored) {
            }
            job.error(e.getMessage());
            onError.accept(e.getMessage());
        } finally {
            activeJobs.remove(chapterId);
        }
    }

    GeneratedImage generateImageForJob(List<Path> imageRequestRefs, String imageApiKey, String prompt, String colorMode) {
        ImageGenerationRequest request = new ImageGenerationRequest(
                prompt,
                properties.getImage().getModel(),
                properties.getImage().getSize(),
                imageRequestRefs,
                colorMode
        );

        GeneratedImage generated;
        try {
            generated = image2Client.generate(request, imageApiKey).block(Duration.ofSeconds(600));
        } catch (Exception e) {
            throw new BusinessException(502, "Image generation timed out or failed: " + e.getMessage());
        }
        if (generated == null) {
            throw new BusinessException(502, "Image generation returned null");
        }
        return generated;
    }

    @Transactional
    public MangaImage regenerateImage(Long chapterId, int imageNumber, String prompt, String imageApiKey, String deepseekApiKey) {
        Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        if (imageNumber < 1 || imageNumber > chapter.getImageCount()) {
            throw new BusinessException(400, "Image number must be between 1 and " + chapter.getImageCount());
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(400, "Prompt cannot be empty");
        }

        // Update scene if full scenes exist
        List<String> scenes = parseScenes(chapter.getScenesText());
        if (scenes.size() == chapter.getImageCount()) {
            scenes.set(imageNumber - 1, prompt);
            chapter.setScenesText(objectMapper.valueToTree(scenes).toString());
            chapterRepository.save(chapter);
        }

        String profiles = resolveGenerationProfiles(chapter);
        String mangaStyle = chapter.getStory().getMangaStyle();
        if (mangaStyle == null || mangaStyle.isBlank()) mangaStyle = "japanese_manga";
        String colorMode = chapter.getColorMode().name().toLowerCase();
        MangaImageStorageService.ReferenceImages referenceImages = mangaImageStorageService.prepareReferenceImages(
                chapter.getStory().getId(), chapter.getId(), chapter.getRefImage(),
                chapter.getAssetGroup() != null ? chapter.getAssetGroup().getId() : null,
                chapter.getStory().getRefImage());
        List<Path> imageRequestRefs = referenceImages.requestRefs();
        boolean hasRefImages = referenceImages.hasRefs();

        String optimizedPrompt = MangaPromptPolicy.buildImagePrompt(
                prompt, profiles, mangaStyle, colorMode, hasRefImages, scenes.isEmpty() ? List.of(prompt) : scenes, imageNumber);

        ImageGenerationRequest request = new ImageGenerationRequest(
                optimizedPrompt,
                properties.getImage().getModel(),
                properties.getImage().getSize(),
                imageRequestRefs,
                chapter.getColorMode().name().toLowerCase()
        );

        GeneratedImage generated;
        try {
            generated = image2Client.generate(request, imageApiKey).block();
        } finally {
            mangaImageStorageService.cleanupTempFiles(referenceImages.tempRefs());
        }
        if (generated == null) {
            throw new BusinessException(502, "Image generation returned null");
        }

        try {
            return mangaImageStorageService.replaceGeneratedPanel(chapter, imageNumber, generated.localFile(), optimizedPrompt);
        } finally {
            mangaImageStorageService.cleanupTempFile(generated.localFile());
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewImageRequest(Long chapterId, Long assetGroupId, Long userId, int imageNumber) {
        Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        StoryAssetGroup selectedAssetGroup = resolveRequestedAssetGroup(chapter, assetGroupId, userId);

        List<String> scenes = resolveScenesForImageGeneration(chapter);
        if (imageNumber < 1 || imageNumber > scenes.size()) {
            throw new BusinessException(400, "Image number must be between 1 and " + scenes.size());
        }

        Long effectiveAssetGroupId = selectedAssetGroup != null ? selectedAssetGroup.getId() :
                chapter.getAssetGroup() != null ? chapter.getAssetGroup().getId() : null;
        String mangaStyle = chapter.getStory().getMangaStyle();
        if (mangaStyle == null || mangaStyle.isBlank()) mangaStyle = "japanese_manga";
        String colorMode = chapter.getColorMode().name().toLowerCase();
        String profiles = buildGenerationProfiles(chapter, selectedAssetGroup);
        List<String> referenceImageKeys = mangaImageStorageService.previewReferenceImageKeys(
                chapter.getStory().getId(), chapter.getId(), chapter.getRefImage(),
                effectiveAssetGroupId, chapter.getStory().getRefImage());
        String prompt = MangaPromptPolicy.buildImagePrompt(
                scenes.get(imageNumber - 1), profiles, mangaStyle, colorMode,
                !referenceImageKeys.isEmpty(), scenes, imageNumber);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chapter_id", chapter.getId());
        result.put("image_number", imageNumber);
        result.put("asset_group_id", effectiveAssetGroupId);
        result.put("asset_group_name", selectedAssetGroup != null ? selectedAssetGroup.getName() :
                chapter.getAssetGroup() != null ? chapter.getAssetGroup().getName() : null);
        result.put("model", properties.getImage().getModel());
        result.put("size", properties.getImage().getSize());
        result.put("color_mode", colorMode);
        result.put("scene", scenes.get(imageNumber - 1));
        result.put("character_profiles", profiles);
        result.put("reference_images", referenceImageKeys);
        result.put("prompt", prompt);
        return result;
    }

    private StoryAssetGroup resolveRequestedAssetGroup(Chapter chapter, Long assetGroupId, Long userId) {
        if (assetGroupId == null) {
            return null;
        }
        StoryAssetGroup assetGroup = storyAssetGroupRepository.findByIdAndUserIdWithCharacters(assetGroupId, userId)
                .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
        if (!assetGroup.getStory().getId().equals(chapter.getStory().getId())) {
            throw new BusinessException(400, "Asset group does not belong to this chapter's story");
        }
        return assetGroup;
    }

    private String resolveGenerationProfiles(Chapter chapter) {
        return buildGenerationProfiles(chapter, null);
    }

    private String buildGenerationProfiles(Chapter chapter, StoryAssetGroup selectedAssetGroup) {
        StoryAssetGroup assetGroup = selectedAssetGroup != null ? selectedAssetGroup : chapter.getAssetGroup();
        if (assetGroup != null) {
            StringBuilder builder = new StringBuilder();
            if (assetGroup.getName() != null && !assetGroup.getName().isBlank()) {
                builder.append("Asset group: ").append(assetGroup.getName()).append("\n");
            }
            if (assetGroup.getDescription() != null && !assetGroup.getDescription().isBlank()) {
                builder.append("Asset group description: ").append(assetGroup.getDescription()).append("\n\n");
            }
            if (assetGroup.getCharacterProfiles() != null && !assetGroup.getCharacterProfiles().isBlank()) {
                builder.append(assetGroup.getCharacterProfiles()).append("\n\n");
            }
            Set<CharacterProfile> characters = assetGroup.getCharacters();
            if (characters != null && !characters.isEmpty()) {
                for (CharacterProfile character : characters) {
                    builder.append("Character: ").append(character.getName() == null ? "" : character.getName()).append("\n");
                    if (character.getDescription() != null && !character.getDescription().isBlank()) {
                        builder.append("Description: ").append(character.getDescription()).append("\n");
                    }
                    builder.append("\n");
                }
            }
            String content = builder.toString().trim();
            if (!content.isBlank()) {
                return content;
            }
        }

        Map<String, Object> profileResult = characterProfileService.resolveEffective(chapter.getId());
        return String.valueOf(profileResult.getOrDefault("content", ""));
    }

    private List<String> resolveScenesForImageGeneration(Chapter chapter) {
        List<String> scenes = parseScenes(chapter.getScenesText());
        if (!scenes.isEmpty()) {
            return scenes;
        }

        throw new BusinessException(400,
                "请先生成分镜再生成漫画。点击「生成分镜」按钮，AI 将为小说内容生成详细分镜脚本后再生成图片。");
    }

    private List<String> parseScenes(String scenesText) {
        if (scenesText == null || scenesText.isBlank()) return List.of();
        try {
            return objectMapper.readValue(scenesText, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private void validateScenesForMangaGeneration(List<String> scenes) {
        for (int i = 0; i < scenes.size(); i++) {
            String scene = scenes.get(i);
            if (!MangaPromptPolicy.isStoryboardPage(scene) || MangaPromptPolicy.hasForbiddenStoryboardCue(scene)) {
                throw new BusinessException(400,
                        "第 " + (i + 1) + " 页分镜仍是单图提示词或缺少多格结构，请先重新生成分镜");
            }
        }
    }
}
