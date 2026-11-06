package com.artverse.application.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Native annotation facade; the injected reader owns the transaction and consistent snapshot. */
@Component
@RequiredArgsConstructor
public class MangaContextTools {
    private final MangaChapterContextReader reader;

    public enum Section { overview, source, storyboard }

    @Tool(name = "get_chapter_context",
            description = "Read current chapter overview or exact source/storyboard pages. Follow next_cursor with the same read_ref until both sections are complete before drafting. Offsets count UTF-16 characters; overview is only a 1200-character source excerpt.",
            readOnly = true)
    public Map<String,Object> getChapterContext(
            @ToolParam(name = "section", required = false, description = "overview (default), source, or storyboard") Section section,
            @ToolParam(name = "cursor", required = false, description = "Opaque next_cursor from the preceding page of this section") String cursor,
            @ToolParam(name = "limit", required = false, description = "Source/storyboard page size, 1-8000 UTF-16 characters; defaults to 8000") Integer limit,
            @ToolParam(name = "read_ref", required = false, description = "Use the original read_ref for all pages; reject a changed chapter snapshot") String readRef,
            RuntimeContext runtimeContext) {
        return reader.read(section, cursor, limit, readRef, runtimeContext);
    }
}
