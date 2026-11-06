package com.artverse.application.tools;

import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Draft-only model surface. Committing is registered separately. */
@Component
@RequiredArgsConstructor
public class StoryboardAgentTools {
    private final MangaStoryboardTools commands;
    private final ObjectMapper mapper;

    public record Panel(String shot, String description, String dialogue, String narration, String sfx) {}
    public record Page(List<Panel> panels) {}

    @Tool(name = "draft_structured_storyboard",
            description = "Create a complete structured storyboard draft after reading all source and existing storyboard pages. Supply their exact read_ref. Each page requires 4-6 panels. This never saves the chapter.",
            concurrencySafe = false)
    public Map<String, Object> draftStructuredStoryboard(
            @ToolParam(name = "pages", description = "Complete page list; each panel requires description") List<Page> pages,
            @ToolParam(name = "read_ref", description = "Exact get_chapter_context read_ref with complete source and storyboard coverage") String readRef,
            RuntimeContext context) {
        if (readRef == null || !readRef.matches("[a-f0-9]{64}")) {
            throw new BusinessException(400, "A complete chapter read_ref is required");
        }
        if (pages == null || pages.isEmpty() || pages.size() > 100) {
            throw new BusinessException(400, "Storyboard requires 1-100 pages");
        }
        for (Page page : pages) {
            if (page == null || page.panels() == null || page.panels().size() < 4 || page.panels().size() > 6) {
                throw new BusinessException(400, "Each storyboard page requires 4-6 panels");
            }
            for (Panel panel : page.panels()) {
                if (panel == null || panel.description() == null || panel.description().isBlank()
                        || panel.description().length() > 8000) {
                    throw new BusinessException(400, "Each panel requires a description of at most 8000 characters");
                }
            }
        }
        return commands.draftStructuredStoryboard(mapper.convertValue(pages, List.class), readRef, context);
    }
}
