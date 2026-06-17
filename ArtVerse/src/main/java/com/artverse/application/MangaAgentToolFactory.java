package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MangaAgentToolFactory {

    private final ChapterRepository chapterRepository;
    private final MangaImageRepository mangaImageRepository;
    private final SceneService sceneService;

    public Object create(String cozeApiKey, Long chapterId) {
        return new Tools(cozeApiKey, chapterId);
    }

    @RequiredArgsConstructor
    public class Tools {

        private final String cozeApiKey;
        private final Long chapterId;

        @Tool(
                name = "get_chapter_context",
                description = "Read the current chapter, story settings, source text, storyboard status, and generated image status.",
                readOnly = true
        )
        @Transactional(readOnly = true)
        public Map<String, Object> getChapterContext() {
            Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                    .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
            List<String> scenes = sceneService.getScenes(chapterId);
            List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(chapterId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("story_title", chapter.getStory().getTitle());
            result.put("chapter_number", chapter.getChapterNumber());
            result.put("chapter_display_name", "第" + chapter.getChapterNumber() + "话");
            result.put("image_count", chapter.getImageCount());
            result.put("color_mode", chapter.getColorMode().name().toLowerCase());
            result.put("manga_style", chapter.getStory().getMangaStyle());
            result.put("has_source_content", !chapter.novelContentOrJoinedMessages().isBlank());
            result.put("source_excerpt", excerpt(chapter.novelContentOrJoinedMessages(), 1200));
            result.put("scenes_count", scenes.size());
            result.put("scenes", scenes);
            result.put("generated_images", images.stream()
                    .map(image -> Map.of(
                            "image_number", image.getImageNumber(),
                            "image_path", image.getImagePath(),
                            "has_prompt", image.getPrompt() != null && !image.getPrompt().isBlank()
                    ))
                    .toList());
            return result;
        }

        @Tool(
                name = "generate_storyboard",
                description = "Generate storyboard scenes from the chapter source content and save them to the chapter.",
                concurrencySafe = false
        )
        @Transactional
        public Map<String, Object> generateStoryboard() {
            List<String> scenes = sceneService.generateScenes(chapterId, cozeApiKey);
            return Map.of(
                    "chapter_display_name", chapterDisplayName(chapterId),
                    "scenes_count", scenes.size(),
                    "scenes", scenes
            );
        }

        @Tool(
                name = "save_storyboard",
                description = "Save edited storyboard scenes to the chapter.",
                concurrencySafe = false
        )
        @Transactional
        public Map<String, Object> saveStoryboard(
                @ToolParam(name = "scenes", description = "Complete storyboard scene list") List<String> scenes) {
            List<String> updated = sceneService.updateScenes(chapterId, scenes);
            return Map.of(
                    "chapter_display_name", chapterDisplayName(chapterId),
                    "scenes_count", updated.size(),
                    "scenes", updated
            );
        }
    }

    private String chapterDisplayName(Long chapterId) {
        return chapterRepository.findByIdForIdempotency(chapterId)
                .map(chapter -> "第" + chapter.getChapterNumber() + "话")
                .orElse("当前章节");
    }

    private String excerpt(String text, int maxChars) {
        if (text == null || text.isBlank()) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }
}
