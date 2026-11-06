package com.artverse.application.tools;

import com.artverse.application.AgentStoryKnowledgeReadService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReferenceAssetReadTools {
    private final AgentStoryKnowledgeReadService reads;
    private final MangaToolSupport support;

    @Tool(name = "list_reference_assets", description = "List persisted character reference image IDs visible at the selected chapter and appearance stage. Returns metadata, never credentials or arbitrary object paths; does not scan or mutate object storage.", readOnly = true, concurrencySafe = true)
    public AgentStoryKnowledgeReadService.ReadPage list(
            @ToolParam(name = "character_id", required = false, description = "Optional revealed character ID") Long characterId,
            @ToolParam(name = "chapter_id", required = false, description = "Read-only chapter projection in current story") Long chapterId,
            @ToolParam(name = "cursor", required = false, description = "Opaque nextCursor from this exact query") String cursor,
            @ToolParam(name = "limit", required = false, description = "Page size, 1-50") Integer limit,
            RuntimeContext runtime) {
        return reads.assets(support.resolveContext(runtime), characterId, chapterId, cursor, limit);
    }
}
