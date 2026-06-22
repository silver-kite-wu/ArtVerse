package com.artverse.guard;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.persistence.ChapterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GenerationRequestKeyBuilder {

    private final ChapterRepository chapterRepository;
    private final RequestCanonicalizer canonicalizer;
    private final ArtVerseProperties properties;

    public Map<String, Object> imageGeneration(Long userId, String prompt, List<String> referenceImages) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("action", "image-gen");
        canonical.put("userId", userId);
        canonical.put("model", properties.getImage().getModel());
        canonical.put("size", properties.getImage().getSize());
        canonical.put("prompt", canonicalizer.normalizeText(prompt));
        canonical.put("refImages", referenceImages == null ? List.of() : referenceImages.stream()
                .map(canonicalizer::imageHash)
                .toList());
        return canonical;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> mangaGeneration(Long userId, Long chapterId) {
        Chapter chapter = chapterForIdempotency(chapterId);
        Map<String, Object> canonical = chapterBase("generate-manga", userId, chapter);
        canonical.put("scenes", chapter.getScenesText() == null ? "" : chapter.getScenesText());
        return canonical;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> imageRegeneration(Long userId, Long chapterId, int imageNumber, String prompt) {
        Chapter chapter = chapterForIdempotency(chapterId);
        Map<String, Object> canonical = chapterBase("regenerate-image", userId, chapter);
        canonical.put("imageNumber", imageNumber);
        canonical.put("prompt", canonicalizer.normalizeText(prompt));
        return canonical;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> sceneGeneration(Long userId, Long chapterId) {
        Chapter chapter = chapterForIdempotency(chapterId);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("action", "generate-scenes");
        canonical.put("userId", userId);
        canonical.put("chapterId", chapter.getId());
        canonical.put("storyId", chapter.getStory().getId());
        canonical.put("imageCount", chapter.getImageCount());
        canonical.put("workflowId", properties.getCoze().getWorkflowId());
        canonical.put("material", canonicalizer.normalizeText(chapter.novelContentOrJoinedMessages()));
        return canonical;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> mangaAgentRun(Long userId, Long chapterId, String requestId, String message,
                                             String provider, String model, String baseUrlHash) {
        Chapter chapter = chapterForIdempotency(chapterId);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("action", "manga-agent-run");
        canonical.put("userId", userId);
        canonical.put("chapterId", chapter.getId());
        canonical.put("storyId", chapter.getStory().getId());
        canonical.put("requestId", requestId);
        canonical.put("message", canonicalizer.normalizeText(message));
        canonical.put("provider", provider == null ? "" : provider);
        canonical.put("model", model == null ? "" : model);
        canonical.put("baseUrlHash", baseUrlHash == null ? "" : baseUrlHash);
        return canonical;
    }

    private Chapter chapterForIdempotency(Long chapterId) {
        return chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
    }

    private Map<String, Object> chapterBase(String action, Long userId, Chapter chapter) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("action", action);
        canonical.put("userId", userId);
        canonical.put("chapterId", chapter.getId());
        canonical.put("storyId", chapter.getStory().getId());
        canonical.put("imageCount", chapter.getImageCount());
        canonical.put("colorMode", String.valueOf(chapter.getColorMode()));
        canonical.put("mangaStyle", chapter.getStory().getMangaStyle() == null ? "" : chapter.getStory().getMangaStyle());
        canonical.put("assetGroupId", chapter.getAssetGroup() == null ? 0 : chapter.getAssetGroup().getId());
        return canonical;
    }
}
