package com.artverse.application;

import com.artverse.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StructuredStoryboardService {

    private static final int MIN_PANELS_PER_PAGE = 4;
    private static final int MAX_PANELS_PER_PAGE = 6;

    private final SceneService sceneService;

    public List<String> normalize(Object payload, int expectedPageCount) {
        List<?> rawPages = extractPages(payload);
        if (rawPages.size() != expectedPageCount) {
            throw new BusinessException(400, "Storyboard page count must equal image count (" + expectedPageCount + ")");
        }

        List<String> scenes = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < rawPages.size(); pageIndex++) {
            Object rawPage = rawPages.get(pageIndex);
            List<?> rawPanels = extractPanels(rawPage, pageIndex + 1);
            if (rawPanels.size() < MIN_PANELS_PER_PAGE || rawPanels.size() > MAX_PANELS_PER_PAGE) {
                throw new BusinessException(400, "Page " + (pageIndex + 1) + " must contain 4-6 panels");
            }
            scenes.add(renderPage(pageIndex + 1, rawPanels));
        }
        sceneService.validateScenes(scenes);
        return scenes;
    }

    private List<?> extractPages(Object payload) {
        if (payload instanceof List<?> list) {
            return list;
        }
        if (payload instanceof Map<?, ?> map) {
            Object pages = map.get("pages");
            if (pages instanceof List<?> list) {
                return list;
            }
        }
        throw new BusinessException(400, "Structured storyboard must be a page list or an object with pages");
    }

    private List<?> extractPanels(Object rawPage, int pageNumber) {
        if (rawPage instanceof Map<?, ?> map) {
            Object panels = map.get("panels");
            if (panels instanceof List<?> list) {
                return list;
            }
        }
        throw new BusinessException(400, "Page " + pageNumber + " must contain panels");
    }

    private String renderPage(int pageNumber, List<?> rawPanels) {
        StringBuilder sb = new StringBuilder("第").append(pageNumber).append("页：");
        for (int i = 0; i < rawPanels.size(); i++) {
            sb.append(renderPanel(i + 1, rawPanels.get(i)));
        }
        return sb.toString();
    }

    private String renderPanel(int panelNumber, Object rawPanel) {
        if (rawPanel instanceof String text) {
            return "【第" + panelNumber + "格】" + requireText(text, "Panel " + panelNumber);
        }
        if (rawPanel instanceof Map<?, ?> map) {
            String shot = optionalText(map.get("shot"));
            String description = firstPresentText(map, List.of("description", "visual", "content"));
            String dialogue = optionalText(map.get("dialogue"));
            String narration = optionalText(map.get("narration"));
            String sfx = optionalText(map.get("sfx"));

            StringBuilder sb = new StringBuilder("【第").append(panelNumber).append("格");
            if (!shot.isBlank()) {
                sb.append("（").append(shot).append("）");
            }
            sb.append("】").append(requireText(description, "Panel " + panelNumber + " description"));
            if (!dialogue.isBlank()) {
                sb.append(" 对话气泡：「").append(dialogue).append("」。");
            }
            if (!narration.isBlank()) {
                sb.append(" 旁白框：「").append(narration).append("」。");
            }
            if (!sfx.isBlank()) {
                sb.append(" 音效字：").append(sfx).append("。");
            }
            return sb.toString();
        }
        throw new BusinessException(400, "Panel " + panelNumber + " must be text or object");
    }

    private String firstPresentText(Map<?, ?> map, List<String> keys) {
        for (String key : keys) {
            String value = optionalText(map.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String optionalText(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
    }

    private String requireText(String value, String field) {
        String text = optionalText(value);
        if (text.isBlank()) {
            throw new BusinessException(400, field + " cannot be empty");
        }
        return text;
    }
}
