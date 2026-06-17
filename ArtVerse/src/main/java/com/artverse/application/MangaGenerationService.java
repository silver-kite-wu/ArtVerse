package com.artverse.application;

import com.artverse.ai.GeneratedImage;
import com.artverse.ai.Image2Client;
import com.artverse.ai.ImageGenerationRequest;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaImageRepository;
import com.artverse.prompt.MangaPromptPolicy;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaGenerationService {

    private final ChapterRepository chapterRepository;
    private final MangaImageRepository mangaImageRepository;
    private final Image2Client image2Client;
    private final ObjectStorageService objectStorageService;
    private final MediaStorageService mediaStorageService;
    private final CharacterProfileService characterProfileService;
    private final ArtVerseProperties properties;
    private final ObjectMapper objectMapper;

    private final Map<Long, MangaGenerationJob> activeJobs = new ConcurrentHashMap<>();
    private ExecutorService executor;

    @PostConstruct
    void init() {
        executor = Executors.newCachedThreadPool();
    }

    @Transactional
    public SseEmitter generateMangaStream(Long chapterId, String imageApiKey, String deepseekApiKey) {
        return generateMangaStream(chapterId, imageApiKey, deepseekApiKey, () -> {}, error -> {});
    }

    @Transactional
    public SseEmitter generateMangaStream(Long chapterId, String imageApiKey, String deepseekApiKey,
                                          Runnable onComplete, Consumer<String> onError) {
        Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

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
        Long assetGroupId = chapter.getAssetGroup() != null ? chapter.getAssetGroup().getId() : null;

        MangaGenerationJob job = new MangaGenerationJob(chapterId, scenes);
        activeJobs.put(chapterId, job);

        SseEmitter emitter = new SseEmitter(0L);
        job.addSubscriber(emitter);

        // Start generation in background
        executor.submit(() -> runGenerationJob(job, chapter, storyId, mangaStyle, storyRefImage, assetGroupId,
                imageApiKey, deepseekApiKey, onComplete, onError));

        return emitter;
    }

    private void runGenerationJob(MangaGenerationJob job, Chapter chapter, Long storyId, String mangaStyle,
                                   String storyRefImage, Long assetGroupId, String imageApiKey, String deepseekApiKey,
                                   Runnable onComplete, Consumer<String> onError) {
        try {
            // Send scenes event
            job.broadcastEvent("scenes", objectMapper.writeValueAsString(Map.of("scenes", job.getScenes())));

            Map<String, Object> profileResult = characterProfileService.resolveEffective(chapter.getId());
            String profiles = (String) profileResult.get("content");
            if (mangaStyle == null || mangaStyle.isBlank()) mangaStyle = "japanese_manga";
            String colorMode = chapter.getColorMode().name().toLowerCase();

            List<Path> refImages = computeEffectiveRefImages(storyId, chapter.getId(), chapter.getRefImage(),
                    assetGroupId, storyRefImage);
            List<Path> tempRefImages = new ArrayList<>();
            List<Path> imageRequestRefs = materializeMinioRefs(refImages, tempRefImages);
            boolean hasRefImages = !imageRequestRefs.isEmpty();

            try {
                for (int i = 0; i < job.getScenes().size(); i++) {
                    if (!job.isRunning()) break;

                    int imageNumber = i + 1;
                    String scene = job.getScenes().get(i);

                    // Check if image already exists
                    Optional<MangaImage> existing = mangaImageRepository.findByChapterIdAndImageNumber(chapter.getId(), imageNumber);
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
                            GeneratedImage generated = generateImageForJob(chapter, imageRequestRefs, imageApiKey, prompt);

                            // Upload to MinIO
                            String filename = mediaStorageService.generateUniqueFilename("panel_" + String.format("%02d", imageNumber), ".png");
                            String objectKey = "stories/" + storyId + "/chapters/" + chapter.getId() + "/panels/" + filename;
                            StoredObject stored = objectStorageService.putPng(objectKey, generated.localFile(), "image/png");

                            // Find existing or create new — update in place to avoid dup key on retry
                            MangaImage mangaImage = mangaImageRepository
                                    .findByChapterIdAndImageNumber(chapter.getId(), imageNumber)
                                    .orElseGet(() -> {
                                        MangaImage m = new MangaImage();
                                        m.setChapter(chapter);
                                        m.setImageNumber(imageNumber);
                                        return m;
                                    });
                            mangaImage.setImagePath(stored.objectKey());
                            mangaImage.setStorageProvider(StorageProvider.MINIO);
                            mangaImage.setBucket(stored.bucket());
                            mangaImage.setObjectKey(stored.objectKey());
                            mangaImage.setContentType(stored.contentType());
                            mangaImage.setSizeBytes(stored.sizeBytes());
                            mangaImage.setPrompt(prompt);
                            mangaImageRepository.saveAndFlush(mangaImage);

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
                            try {
                                Files.deleteIfExists(generated.localFile());
                                Files.deleteIfExists(generated.localFile().getParent());
                            } catch (Exception ignored) {
                            }
                            success = true;
                            break;
                        } catch (Exception e) {
                            lastException = e;
                            log.warn("Failed to generate image {}/{} for chapter {} (attempt {}/3): {}",
                                    imageNumber, job.getScenes().size(), chapter.getId(), attempt + 1, e.getMessage());
                        }
                    }

                    if (!success && lastException != null) {
                        log.error("Failed to generate image {}/{} for chapter {} after 3 attempts: {}",
                                imageNumber, job.getScenes().size(), chapter.getId(), lastException.getMessage());
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
                cleanupTempFiles(tempRefImages);
            }

            // Send done
            job.broadcastEvent("done", objectMapper.writeValueAsString(Map.of("images", job.getScenes().size())));
            job.complete();
            onComplete.run();

        } catch (Exception e) {
            log.error("Manga generation failed for chapter {}: {}", chapter.getId(), e.getMessage(), e);
            try {
                job.broadcastEvent("error", objectMapper.writeValueAsString(Map.of("detail", e.getMessage())));
            } catch (Exception ignored) {
            }
            job.error(e.getMessage());
            onError.accept(e.getMessage());
        } finally {
            activeJobs.remove(chapter.getId());
        }
    }

    GeneratedImage generateImageForJob(Chapter chapter, List<Path> imageRequestRefs, String imageApiKey, String prompt) {
        ImageGenerationRequest request = new ImageGenerationRequest(
                prompt,
                properties.getImage().getModel(),
                properties.getImage().getSize(),
                imageRequestRefs,
                chapter.getColorMode().name().toLowerCase()
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

        Map<String, Object> profileResult = characterProfileService.resolveEffective(chapterId);
        String profiles = (String) profileResult.get("content");
        String mangaStyle = chapter.getStory().getMangaStyle();
        if (mangaStyle == null || mangaStyle.isBlank()) mangaStyle = "japanese_manga";
        String colorMode = chapter.getColorMode().name().toLowerCase();
        List<Path> refImages = computeEffectiveRefImages(
                chapter.getStory().getId(), chapter.getId(), chapter.getRefImage(),
                chapter.getAssetGroup() != null ? chapter.getAssetGroup().getId() : null,
                chapter.getStory().getRefImage());
        List<Path> tempRefImages = new ArrayList<>();
        List<Path> imageRequestRefs = materializeMinioRefs(refImages, tempRefImages);
        boolean hasRefImages = !imageRequestRefs.isEmpty();

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
            cleanupTempFiles(tempRefImages);
        }
        if (generated == null) {
            throw new BusinessException(502, "Image generation returned null");
        }

        // Upload to MinIO
        String filename = mediaStorageService.generateUniqueFilename("panel_" + String.format("%02d", imageNumber), ".png");
        String objectKey = "stories/" + chapter.getStory().getId() + "/chapters/" + chapterId + "/panels/" + filename;
        StoredObject stored = objectStorageService.putPng(objectKey, generated.localFile(), "image/png");

        // Update or create DB record
        Optional<MangaImage> existingOpt = mangaImageRepository.findByChapterIdAndImageNumber(chapterId, imageNumber);

        String oldObjectKey = null;
        String oldBucket = null;

        if (existingOpt.isPresent()) {
            MangaImage existing = existingOpt.get();
            oldObjectKey = existing.getObjectKey();
            oldBucket = existing.getBucket();
            existing.setImagePath(stored.objectKey());
            existing.setStorageProvider(StorageProvider.MINIO);
            existing.setBucket(stored.bucket());
            existing.setObjectKey(stored.objectKey());
            existing.setContentType(stored.contentType());
            existing.setSizeBytes(stored.sizeBytes());
            existing.setPrompt(optimizedPrompt);
            MangaImage saved = mangaImageRepository.save(existing);

            // Delete old object after successful save
            cleanupOldObject(oldBucket, oldObjectKey);

            // Cleanup temp
            cleanupTempFile(generated.localFile());

            return saved;
        } else {
            MangaImage mangaImage = new MangaImage();
            mangaImage.setChapter(chapter);
            mangaImage.setImageNumber(imageNumber);
            mangaImage.setImagePath(stored.objectKey());
            mangaImage.setStorageProvider(StorageProvider.MINIO);
            mangaImage.setBucket(stored.bucket());
            mangaImage.setObjectKey(stored.objectKey());
            mangaImage.setContentType(stored.contentType());
            mangaImage.setSizeBytes(stored.sizeBytes());
            mangaImage.setPrompt(optimizedPrompt);
            MangaImage saved = mangaImageRepository.save(mangaImage);

            cleanupTempFile(generated.localFile());

            return saved;
        }
    }

    private void cleanupOldObject(String bucket, String objectKey) {
        if (bucket != null && objectKey != null) {
            try {
                objectStorageService.deleteBestEffort(bucket, objectKey);
            } catch (Exception e) {
                log.warn("Failed to delete old MinIO object {}/{}: {}", bucket, objectKey, e.getMessage());
            }
        }
    }

    private void cleanupTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempFile.getParent());
        } catch (Exception ignored) {
        }
    }

    private List<Path> computeEffectiveRefImages(Long storyId, Long chapterId, String chapterRefImage,
                                                 Long assetGroupId, String storyRefImage) {
        List<Path> refs = new ArrayList<>();

        addMinioRefs(refs, "stories/" + storyId + "/chapters/" + chapterId + "/ref_images/");

        // Chapter old single ref
        if (refs.isEmpty() && chapterRefImage != null && !chapterRefImage.isBlank()) {
            Path ref = mediaStorageService.resolveRelativePath(chapterRefImage);
            if (ref != null && Files.exists(ref)) refs.add(ref);
        }

        if (refs.isEmpty() && assetGroupId != null) {
            addMinioRefs(refs, "stories/" + storyId + "/asset_groups/" + assetGroupId + "/ref_images/");
        }

        if (refs.isEmpty()) {
            addMinioRefs(refs, "stories/" + storyId + "/ref_images/");
        }

        // Story old single ref
        if (refs.isEmpty() && storyRefImage != null && !storyRefImage.isBlank()) {
            Path ref = mediaStorageService.resolveRelativePath(storyRefImage);
            if (ref != null && Files.exists(ref)) refs.add(ref);
        }

        return refs;
    }

    private void addMinioRefs(List<Path> refs, String prefix) {
        objectStorageService.list(properties.getMinio().getBucket(), prefix, 4).stream()
                .map(stored -> Path.of(stored.objectKey()))
                .filter(this::isImageFile)
                .limit(4 - refs.size())
                .forEach(refs::add);
    }

    private List<Path> materializeMinioRefs(List<Path> refs, List<Path> tempRefs) {
        List<Path> materialized = new ArrayList<>();
        for (Path ref : refs) {
            if (Files.exists(ref)) {
                materialized.add(ref);
            } else {
                Path temp = downloadRefObject(ref.toString().replace('\\', '/'));
                tempRefs.add(temp);
                materialized.add(temp);
            }
        }
        return materialized;
    }

    private Path downloadRefObject(String objectKey) {
        try (var in = objectStorageService.get(properties.getMinio().getBucket(), objectKey)) {
            Path temp = Files.createTempFile("artverse-ref-", suffixFor(objectKey));
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (Exception e) {
            throw new RuntimeException("Failed to download ref image: " + e.getMessage(), e);
        }
    }

    private String suffixFor(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".webp")) return ".webp";
        return ".png";
    }

    private void cleanupTempFiles(List<Path> tempFiles) {
        for (Path tempFile : tempFiles) {
            cleanupTempFile(tempFile);
        }
    }

    private boolean isImageFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    private String optimizePrompt(String prompt, String deepseekApiKey) {
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) return prompt;
        try {
            var body = Map.of(
                    "model", "deepseek-chat",
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "You are a professional AI art prompt optimizer. Enhance the given manga panel description into a more detailed, visually rich English prompt for image generation. Add composition, lighting, color, detail descriptions. Strictly preserve the original intent. IMPORTANT: Do NOT include any page numbers, panel numbers, fractions, or ordinal text in the prompt. Output only the optimized prompt, no explanations."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.7,
                    "max_tokens", 1024
            );

            WebClient client = WebClient.builder()
                    .baseUrl(properties.getDeepseek().getBaseUrl())
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build();

            String response = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + deepseekApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            JsonNode node = objectMapper.readTree(response);
            String optimized = node.path("choices").path(0).path("message").path("content").asText();
            if (optimized != null && !optimized.isBlank()) {
                log.info("Prompt optimized successfully for image, length: {} -> {}", prompt.length(), optimized.length());
                return optimized;
            }
        } catch (Exception e) {
            log.warn("Prompt optimization failed, using original: {}", e.getMessage());
        }
        return prompt;
    }

    private String buildImagePrompt(String scene, String profiles, String mangaStyle, String colorMode,
                                     boolean hasRefImages, List<String> allScenes, int imageNumber) {
        StringBuilder sb = new StringBuilder();

        if (hasRefImages) {
            sb.append("【最重要：人物一致性】\n");
            sb.append("本次提供了参考图，**必须严格保持参考图中主角的外貌特征**：");
            sb.append("包括发型、发色、瞳色、脸型、五官比例、服装风格——所有分镜格中的人物都必须是参考图中的同一批人物。\n");
            sb.append("禁止凭空创造新的人物外貌。\n\n");
        }

        if (!hasRefImages && profiles != null && !profiles.isBlank()) {
            sb.append("【角色外貌设定（每张图必须严格遵守）】\n");
            sb.append(profiles).append("\n\n");
        }

        int totalPages = allScenes.size();

        sb.append("你正在绘制一部").append(styleLabel(mangaStyle, colorMode)).append("的第").append(imageNumber).append("页（共").append(totalPages).append("页）。\n\n");

        sb.append("以下是完整的").append(totalPages).append("页分镜脚本，请保持人物外貌、服装、风格的一致性：\n\n");
        for (int i = 0; i < allScenes.size(); i++) {
            sb.append("第").append(i + 1).append("页：").append(allScenes.get(i)).append("\n");
        }
        sb.append("\n");

        sb.append("现在请绘制本页内容：\n");
        sb.append(styleTemplate(mangaStyle)).append("\n");
        sb.append(colorModifier(colorMode)).append("\n");
        sb.append(scene).append("\n\n");

        sb.append("【严格禁止】\n");
        sb.append("- 图片中绝对不能出现任何页码数字、编号、分数（如\"1/8\"\"第1页\"\"Page 1\"）等文字\n");
        sb.append("- 不能出现\"第几张\"\"几分之几\"等任何计数标记\n");
        sb.append("- 图片是纯粹的漫画画面，不含任何排版标记");

        return sb.toString();
    }

        private String styleLabel(String mangaStyle, String colorMode) {
        String base = switch (mangaStyle) {
            case "korean_webtoon" -> "韩式条漫";
            case "american_comic" -> "美式漫画";
            case "ligne_claire" -> "欧式清线漫画";
            case "chinese_ink" -> "水墨国风漫画";
            case "semi_realistic" -> "半厚涂写实漫画";
            case "realistic" -> "全写实漫画";
            case "oil_painting" -> "厚涂油画漫画";
            case "flat_design" -> "扁平极简漫画";
            case "pixel_art" -> "像素风漫画";
            case "watercolor" -> "水彩淡雅漫画";
            case "cyberpunk" -> "赛博朋克漫画";
            default -> "日式漫画";
        };
        String colorTag = switch (colorMode != null ? colorMode : "bw") {
            case "grayscale" -> "（灰度）";
            case "color" -> "（彩色）";
            case "duotone" -> "（双色调）";
            default -> "（黑白）";
        };
        return base + colorTag;
    }

    private String styleTemplate(String mangaStyle) {
        return switch (mangaStyle) {
            case "korean" -> """
                    韩式条漫风格，竖向滚动式构图，干净线条和自然渐变光影，\
                    人物比例修长，表情细腻丰富，背景简约但氛围感强，\
                    对话气泡现代简洁，适合手机竖屏阅读。""";
            case "american" -> """
                    美式漫画风格，粗重有力的线条，饱和鲜艳的色块，\
                    动态夸张的构图和透视角度，人物肌肉线条分明，\
                    表情夸张生动，巨大的拟声词字体（BOOM！POW！），\
                    高对比度的阴影和强光效果，动作场面视觉冲击力强。""";
            case "european" -> """
                    欧式清线（Ligne Claire）漫画风格，均匀一致的线条粗细，\
                    平涂色彩块面，无交叉阴影线，精细描绘的背景环境和建筑，\
                    人物造型简洁清晰，画面干净明朗，叙事感强。""";
            case "chinese_ink" -> """
                    中国水墨国风漫画，水墨渲染意境深远，工笔线条飘逸灵动，\
                    大面积的留白构图，传统山水花鸟元素融入场景，\
                    人物服饰和造型带有中国传统美学特色，\
                    墨色浓淡变化丰富，笔触写意洒脱。""";
            case "semi_realistic" -> """
                    半厚涂写实风格，日系角色比例结合写实材质渲染，\
                    皮肤质感细腻有渐变层次，布料金属等材质表现力强，\
                    光影柔和自然，色彩层次丰富，画面完成度高，\
                    兼具动漫美感与写实厚重感。""";
            default -> """
                    日式漫画风格，竖向多格分镜布局，每页包含4-6个分镜格，\
                    格子高度不等（动作场景用宽格，对话特写用窄格），\
                    每个分镜格之间有清晰的边框分隔，\
                    包含圆形/椭圆形对话气泡和中文台词，\
                    包含漫画音效字（如"唰—""铿！""嗡—"），\
                    精细的线条和网点，人物绘制精美，表情生动，动作有力度感。""";
        };
    }

    private String colorModifier(String colorMode) {
        return switch (colorMode != null ? colorMode : "bw") {
            case "grayscale" -> """
                    灰度色彩模式：使用灰阶过渡表现光影，柔和细腻的素描质感，\
                    避免纯黑纯白，保留丰富的中间灰色层次。""";
            case "color" -> """
                    全彩色彩模式：高饱和度配色，赛璐珞风格上色，\
                    柔和光影与高光，细腻的色彩渐变，丰富的色彩层次。""";
            case "duotone" -> """
                    双色调色彩模式：双色印刷风格，使用冷暖对比色调，\
                    复古印刷质感，有限的色彩范围创造强烈的情绪氛围。""";
            default -> """
                    黑白色彩模式：高对比度黑白，戏剧性光影，网点纸纹理，\
                    纯黑纯白基调，精细的线条表现力。""";
        };
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
