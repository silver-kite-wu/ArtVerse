package com.artverse.application.tools;

import com.artverse.application.AgentStoryKnowledgeReadService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoryContextReadTools {
    private final AgentStoryKnowledgeReadService reads;
    private final MangaToolSupport support;

    @Tool(name = "get_story_context", description = "Read author-owned story settings as paginated JSON fragments. These are author settings, not chapter-known facts. Concatenate fragments until nextCursor is empty.", readOnly = true, concurrencySafe = true)
    public AgentStoryKnowledgeReadService.DetailPage story(
            @ToolParam(name = "cursor", required = false, description = "Opaque nextCursor from the preceding fragment") String cursor,
            @ToolParam(name = "limit", required = false, description = "Fragment length, 1-8000 UTF-16 characters") Integer limit,
            RuntimeContext runtime) {
        return reads.story(support.resolveContext(runtime), cursor, limit);
    }

    @Tool(name = "list_chapters", description = "List current-story chapter identities, titles, versions and publication flags. Stable snapshot pagination; reading a chapter never changes the write target.", readOnly = true, concurrencySafe = true)
    public AgentStoryKnowledgeReadService.ReadPage chapters(
            @ToolParam(name = "cursor", required = false, description = "Opaque nextCursor from this same query") String cursor,
            @ToolParam(name = "limit", required = false, description = "Page size, 1-50; defaults to 20") Integer limit,
            RuntimeContext runtime) {
        return reads.listChapters(support.resolveContext(runtime), cursor, limit);
    }
}
