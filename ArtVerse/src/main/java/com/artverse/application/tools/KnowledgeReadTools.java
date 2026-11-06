package com.artverse.application.tools;

import com.artverse.application.AgentStoryKnowledgeReadService;
import com.artverse.domain.KnowledgeUnitType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KnowledgeReadTools {
    private final AgentStoryKnowledgeReadService reads;
    private final MangaToolSupport support;

    @Tool(name = "search_story_knowledge", description = "Search confirmed knowledge using the shared chapter and knowing-character fact projection. Structured retrieval with explicit summary truncation and stable snapshot pages. Detail reads must use the same chapter/character filters.", readOnly = true, concurrencySafe = true)
    public AgentStoryKnowledgeReadService.ReadPage search(
            @ToolParam(name = "query", required = false, description = "Text query, at most 500 characters; empty lists visible knowledge") String query,
            @ToolParam(name = "type", required = false, description = "Optional confirmed knowledge type") KnowledgeUnitType type,
            @ToolParam(name = "chapter_id", required = false, description = "Read-only chapter projection in current story; defaults to the bound chapter") Long chapterId,
            @ToolParam(name = "knowing_character_id", required = false, description = "Optional revealed character; narrows results to facts that character knows") Long knowingCharacterId,
            @ToolParam(name = "cursor", required = false, description = "Opaque nextCursor from this exact query and projection") String cursor,
            @ToolParam(name = "limit", required = false, description = "Page size, 1-50") Integer limit,
            RuntimeContext runtime) {
        return reads.search(support.resolveContext(runtime), query, type, chapterId, knowingCharacterId, cursor, limit);
    }

    @Tool(name = "get_knowledge_detail", description = "Read a confirmed knowledge item through exactly the same chapter/character projection as search. Returns paginated JSON fragments with visible fact revisions and source references; never reveals future or private facts through a direct ID.", readOnly = true, concurrencySafe = true)
    public AgentStoryKnowledgeReadService.DetailPage detail(
            @ToolParam(name = "knowledge_id", description = "Visible knowledge_id from search") Long knowledgeId,
            @ToolParam(name = "chapter_id", required = false, description = "Same read-only chapter projection used in search") Long chapterId,
            @ToolParam(name = "knowing_character_id", required = false, description = "Same knowing-character projection used in search") Long knowingCharacterId,
            @ToolParam(name = "cursor", required = false, description = "Opaque nextCursor; concatenate contentJsonFragment until empty") String cursor,
            @ToolParam(name = "limit", required = false, description = "Fragment length, 1-8000 UTF-16 characters") Integer limit,
            RuntimeContext runtime) {
        return reads.knowledge(support.resolveContext(runtime), knowledgeId, chapterId, knowingCharacterId, cursor, limit);
    }

    @Tool(name = "get_character_context", description = "Read a revealed character card, stage facts and directed relationships through chapter/knowing-character projection. Returns paginated JSON fragments and never copies unprojected profile descriptions.", readOnly = true, concurrencySafe = true)
    public AgentStoryKnowledgeReadService.DetailPage character(
            @ToolParam(name = "character_id", description = "Stable character profile ID in the current story") Long characterId,
            @ToolParam(name = "chapter_id", required = false, description = "Read-only chapter projection; defaults to current") Long chapterId,
            @ToolParam(name = "knowing_character_id", required = false, description = "Optional character whose knowledge limits the result") Long knowingCharacterId,
            @ToolParam(name = "cursor", required = false, description = "Opaque nextCursor; concatenate JSON fragments") String cursor,
            @ToolParam(name = "limit", required = false, description = "Fragment length, 1-8000 UTF-16 characters") Integer limit,
            RuntimeContext runtime) {
        return reads.character(support.resolveContext(runtime), characterId, chapterId, knowingCharacterId, cursor, limit);
    }
}
