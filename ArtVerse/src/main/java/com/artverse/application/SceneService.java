package com.artverse.application;

import com.artverse.ai.CozeClient;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.persistence.ChapterRepository;
import com.artverse.prompt.MangaPromptPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SceneService {

    private final ChapterRepository chapterRepository;
    private final CozeClient cozeClient;
    private final ObjectMapper objectMapper;

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*\\]", Pattern.DOTALL);

    @Transactional(readOnly = true)
    public List<String> getScenes(Long chapterId) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        return parseScenesText(chapter.getScenesText());
    }

    @Transactional
    public List<String> generateScenes(Long chapterId, String cozeApiKey) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        String material = chapter.novelContentOrJoinedMessages();
        if (material.isBlank()) {
            throw new BusinessException(400, "No content to generate scenes from");
        }

        List<String> scenes = cozeClient.generateScenes(
                material,
                chapter.getImageCount(),
                cozeApiKey,
                MangaPromptPolicy.storyboardInstruction(chapter.getImageCount()));

        if (scenes.size() != chapter.getImageCount()) {
            throw new BusinessException(502,
                    "Coze returned " + scenes.size() + " scenes but expected " + chapter.getImageCount());
        }
        validateStoryboardScenes(scenes);
        chapter.setScenesText(objectMapper.valueToTree(scenes).toString());
        chapterRepository.save(chapter);

        return scenes;
    }

    @Transactional
    public List<String> updateScenes(Long chapterId, List<String> scenes) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        if (scenes == null || scenes.isEmpty()) {
            throw new BusinessException(400, "Scenes cannot be empty");
        }
        if (scenes.size() != chapter.getImageCount()) {
            throw new BusinessException(400, "Scenes count must equal image count (" + chapter.getImageCount() + ")");
        }
        for (int i = 0; i < scenes.size(); i++) {
            if (scenes.get(i) == null || scenes.get(i).isBlank()) {
                throw new BusinessException(400, "Scene " + (i + 1) + " cannot be empty");
            }
        }
        chapter.setScenesText(objectMapper.valueToTree(scenes).toString());
        chapterRepository.save(chapter);

        return scenes;
    }

    public List<String> parseScenesText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Strategy 1: Direct parse as string array
        List<String> result = tryParseStringArray(text);
        if (result != null) return result;

        // Strategy 2: Parse as object array [{page, panels}] — AI often returns this format
        result = tryParseObjectArray(text);
        if (result != null) return result;

        // Strategy 3: Extract JSON from code fence and retry both formats
        String extracted = extractJsonFromCodeFence(text);
        if (extracted != null) {
            result = tryParseStringArray(extracted);
            if (result != null) return result;
            result = tryParseObjectArray(extracted);
            if (result != null) return result;
        }

        // Strategy 4: Regex find JSON array and retry both formats (with sanitization)
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(text);
        if (matcher.find()) {
            String candidate = matcher.group();
            result = tryParseStringArray(candidate);
            if (result != null) return result;
            result = tryParseObjectArray(candidate);
            if (result != null) return result;
            String cleaned = candidate
                    .replaceAll(",(\\s*[}\\]])", "$1")     // trailing commas
                    .replaceAll("(?m)^\\s*//.*$", "");     // comment lines
            result = tryParseStringArray(cleaned);
            if (result != null) return result;
            result = tryParseObjectArray(cleaned);
            if (result != null) return result;
        }

        // Last resort: split by page markers
        List<String> byPages = splitByPageMarkers(text);
        if (!byPages.isEmpty()) return byPages;

        log.error("Failed to parse scenes. Raw text (first 2000 chars): {}",
                text.length() > 2000 ? text.substring(0, 2000) + "..." : text);
        throw new BusinessException(502, "AI returned invalid scene JSON");
    }

    private void validateStoryboardScenes(List<String> scenes) {
        for (int i = 0; i < scenes.size(); i++) {
            String scene = scenes.get(i);
            if (!MangaPromptPolicy.isStoryboardPage(scene)) {
                throw new BusinessException(502, "第 " + (i + 1) + " 页分镜缺少多格结构，请重新生成");
            }
            if (MangaPromptPolicy.hasForbiddenStoryboardCue(scene)) {
                throw new BusinessException(502, "第 " + (i + 1) + " 页分镜包含单图提示词或英文标记，请重新生成");
            }
        }
    }

    private List<String> tryParseStringArray(String text) {
        try {
            return objectMapper.readValue(text, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> tryParseObjectArray(String text) {
        try {
            List<Map<String, Object>> objects = objectMapper.readValue(
                    text, new TypeReference<List<Map<String, Object>>>() {});
            List<String> scenes = new ArrayList<>();
            for (Map<String, Object> obj : objects) {
                Object panelsObj = obj.get("panels");
                if (panelsObj instanceof List<?> panels) {
                    StringBuilder sb = new StringBuilder();
                    for (Object p : panels) {
                        if (p instanceof String s && !s.isBlank()) {
                            if (!sb.isEmpty()) sb.append("\n");
                            sb.append(s);
                        }
                    }
                    if (!sb.isEmpty()) {
                        scenes.add(sb.toString());
                    }
                }
            }
            return scenes.isEmpty() ? null : scenes;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> splitByPageMarkers(String text) {
        String[] parts = text.split("(?=\"第\\d+页)");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && trimmed.contains("第") && trimmed.contains("页")) {
                trimmed = trimmed.replaceAll("^[\"'，,\\s]+|[\"'，,\\s]+$", "");
                if (trimmed.length() > 10) {
                    result.add(trimmed);
                }
            }
        }
        return result.size() >= 2 ? result : List.of();
    }

    private String extractJsonFromCodeFence(String text) {
        int start = text.indexOf("```json");
        if (start == -1) start = text.indexOf("```");
        if (start == -1) return null;

        int codeStart = text.indexOf("\n", start);
        if (codeStart == -1) return null;
        codeStart++;

        int codeEnd = text.indexOf("```", codeStart);
        if (codeEnd == -1) return null;

        return text.substring(codeStart, codeEnd).trim();
    }
}
