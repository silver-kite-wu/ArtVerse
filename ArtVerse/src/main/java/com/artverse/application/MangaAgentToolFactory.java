package com.artverse.application;

import com.artverse.agents.AgentRunContext;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MangaAgentToolFactory {

    private final MangaImageRepository mangaImageRepository;
    private final SceneService sceneService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final AgentToolAuditService agentToolAuditService;
    private final AgentRunToolStatus agentRunToolStatus;

    public Object create(String cozeApiKey, Long chapterId, Long userId) {
        return new Tools(cozeApiKey, chapterId, userId);
    }

    @RequiredArgsConstructor
    public class Tools {

        private final String cozeApiKey;
        private final Long chapterId;
        private final Long userId;

        @Tool(
                name = "get_chapter_context",
                description = "Read the current chapter, story settings, source text, storyboard status, and generated image status.",
                readOnly = true
        )
        @Transactional(readOnly = true)
        public Map<String, Object> getChapterContext(RuntimeContext runtimeContext) {
            return agentToolAuditService.around("get_chapter_context", userId, chapterId, runtimeContext, () -> {
                Chapter chapter = chapterAccessService.requireVisible(chapterId, userId);
                List<String> scenes = sceneService.getScenes(chapterId);
                List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(chapterId);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("story_title", chapter.getStory().getTitle());
                result.put("chapter_number", chapter.getChapterNumber());
                result.put("chapter_display_name", chapterDisplayName(chapter));
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
            });
        }

        public Map<String, Object> getChapterContext() {
            return getChapterContext(null);
        }

        @Tool(
                name = "generate_storyboard",
                description = "Generate storyboard scenes from the chapter source content and save them to the chapter.",
                concurrencySafe = false
        )
        @Transactional
        public Map<String, Object> generateStoryboard(RuntimeContext runtimeContext) {
            return agentToolAuditService.around("generate_storyboard", userId, chapterId, runtimeContext, () -> {
                Chapter chapter = chapterAccessService.requireVisible(chapterId, userId);
                return generationGuardService.executeSceneGeneration(
                        userId,
                        chapterId,
                        () -> {
                            List<String> scenes = sceneService.generateScenes(chapterId, cozeApiKey);
                            return Map.of(
                                    "chapter_display_name", chapterDisplayName(chapter),
                                    "saved", true,
                                    "changed", true,
                                    "scenes_count", scenes.size(),
                                    "scenes", scenes
                            );
                        }
                );
            });
        }

        public Map<String, Object> generateStoryboard() {
            return generateStoryboard(null);
        }

        @Tool(
                name = "save_storyboard",
                description = "Save edited storyboard scenes to the chapter.",
                concurrencySafe = false
        )
        @Transactional
        public Map<String, Object> saveStoryboard(
                @ToolParam(name = "scenes", description = "Complete storyboard scene list") List<String> scenes,
                RuntimeContext runtimeContext) {
            return agentToolAuditService.around("save_storyboard", userId, chapterId, runtimeContext, () -> {
                Chapter chapter = chapterAccessService.requireVisible(chapterId, userId);
                List<String> updated = sceneService.updateScenes(chapterId, scenes);
                return Map.of(
                        "chapter_display_name", chapterDisplayName(chapter),
                        "saved", true,
                        "changed", true,
                        "scenes_count", updated.size(),
                        "scenes", updated
                );
            });
        }

        public Map<String, Object> saveStoryboard(List<String> scenes) {
            return saveStoryboard(scenes, null);
        }

        @Tool(
                name = "save_structured_storyboard",
                description = "Save storyboard pages as structured page/panel data. Input may be a list of pages or an object with pages. Each page must contain 4-6 panels.",
                concurrencySafe = false
        )
        @Transactional
        public Map<String, Object> saveStructuredStoryboard(
                @ToolParam(name = "pages", description = "Storyboard pages with panels") Object pages,
                RuntimeContext runtimeContext) {
            return agentToolAuditService.around("save_structured_storyboard", userId, chapterId, runtimeContext, () -> {
                Chapter chapter = chapterAccessService.requireVisible(chapterId, userId);
                List<String> scenes = structuredStoryboardService.normalize(pages, chapter.getImageCount());
                List<String> updated = sceneService.updateScenes(chapterId, scenes);
                return Map.of(
                        "chapter_display_name", chapterDisplayName(chapter),
                        "saved", true,
                        "changed", true,
                        "scenes_count", updated.size(),
                        "scenes", updated
                );
            });
        }

        public Map<String, Object> saveStructuredStoryboard(Object pages) {
            return saveStructuredStoryboard(pages, null);
        }

        @Tool(
                name = "ask_user",
                description = "Pause the manga agent and ask the user to choose between options before continuing. Use this when a creative or workflow decision cannot be made safely.",
                readOnly = true
        )
        public Map<String, Object> askUser(
                @ToolParam(name = "question", description = "Question to show to the user") String question,
                @ToolParam(name = "options", description = "Options as a list of strings or objects with label/description/recommended") Object options,
                @ToolParam(name = "allow_free_text", description = "Whether the user may type a custom answer") Boolean allowFreeText,
                @ToolParam(name = "reason", description = "Short reason why user input is needed") String reason,
                RuntimeContext runtimeContext) {
            return agentToolAuditService.around("ask_user", userId, chapterId, runtimeContext, () -> {
                AgentUserInputRequest request = buildUserInputRequest(question, options, allowFreeText, reason);
                requestUserInput(userId, chapterId, runtimeContext, request);
                throw new ToolSuspendException("Waiting for user input");
            });
        }

        public Map<String, Object> askUser(String question, Object options, Boolean allowFreeText, String reason) {
            return askUser(question, options, allowFreeText, reason, null);
        }
    }

    private void requestUserInput(Long userId, Long chapterId, RuntimeContext runtimeContext,
                                  AgentUserInputRequest request) {
        AgentRunContext context = runtimeContext == null ? null : runtimeContext.get(AgentRunContext.class);
        if (context != null && context.requestId() != null) {
            agentRunToolStatus.requestUserInput(userId, chapterId, context.requestId(), request);
            return;
        }
        agentRunToolStatus.requestUserInputForActiveRun(userId, chapterId, request);
    }

    private AgentUserInputRequest buildUserInputRequest(String question, Object rawOptions,
                                                        Boolean allowFreeText, String reason) {
        List<AgentUserInputRequest.Option> options = normalizeOptions(rawOptions);
        if (options.isEmpty()) {
            options = List.of(
                    new AgentUserInputRequest.Option("a", "继续默认方案", "让智能体按当前上下文选择一个稳妥方案", true),
                    new AgentUserInputRequest.Option("b", "先给出建议", "先不要执行，让智能体说明推荐路径", false)
            );
        }
        return new AgentUserInputRequest(
                question == null || question.isBlank() ? "需要你确认下一步怎么处理。" : question.trim(),
                options,
                Boolean.TRUE.equals(allowFreeText),
                reason == null ? "" : reason.trim()
        );
    }

    private List<AgentUserInputRequest.Option> normalizeOptions(Object rawOptions) {
        if (!(rawOptions instanceof List<?> list)) {
            return List.of();
        }
        List<AgentUserInputRequest.Option> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String id = String.valueOf((char) ('a' + Math.min(i, 25)));
            if (item instanceof Map<?, ?> map) {
                String label = optionalText(map.get("label"));
                if (label.isBlank()) {
                    label = optionalText(map.get("title"));
                }
                if (!label.isBlank()) {
                    result.add(new AgentUserInputRequest.Option(
                            optionalText(map.get("id")).isBlank() ? id : optionalText(map.get("id")),
                            label,
                            optionalText(map.get("description")),
                            Boolean.TRUE.equals(map.get("recommended"))
                    ));
                }
            } else {
                String label = optionalText(item);
                if (!label.isBlank()) {
                    result.add(new AgentUserInputRequest.Option(id, label, "", i == 0));
                }
            }
        }
        return result;
    }

    private String chapterDisplayName(Chapter chapter) {
        if (chapter.getDisplayTitle() != null && !chapter.getDisplayTitle().isBlank()) {
            return chapter.getDisplayTitle();
        }
        return "第" + chapter.getChapterNumber() + "话";
    }

    private String excerpt(String text, int maxChars) {
        if (text == null || text.isBlank()) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }

    private String optionalText(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
    }
}
