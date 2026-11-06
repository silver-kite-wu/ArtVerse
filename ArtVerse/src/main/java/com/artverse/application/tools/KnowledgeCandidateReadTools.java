package com.artverse.application.tools;

import com.artverse.application.AgentStoryKnowledgeReadService;
import com.artverse.domain.KnowledgeCandidateStatus;
import com.artverse.domain.KnowledgeUnitType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KnowledgeCandidateReadTools {
    private final AgentStoryKnowledgeReadService reads;
    private final MangaToolSupport support;

    @Tool(name = "list_knowledge_candidates", description = "List the current author's current-chapter knowledge proposals, versions and source-staleness flags. Proposals are not confirmed facts. Stable snapshot pagination; never approves or replaces candidates.", readOnly = true, concurrencySafe = true)
    public AgentStoryKnowledgeReadService.ReadPage list(
            @ToolParam(name = "type", required = false, description = "Optional proposal knowledge type") KnowledgeUnitType type,
            @ToolParam(name = "status", required = false, description = "Optional persisted proposal status") KnowledgeCandidateStatus status,
            @ToolParam(name = "cursor", required = false, description = "Opaque nextCursor from this exact query") String cursor,
            @ToolParam(name = "limit", required = false, description = "Page size, 1-50") Integer limit,
            RuntimeContext runtime) {
        return reads.listCandidates(support.resolveContext(runtime), type, status, cursor, limit);
    }

    @Tool(name = "get_knowledge_candidate", description = "Read the current author's current-chapter proposal, base version, source hashes, staleness and validation issues as paginated JSON fragments. This read does not approve a proposal or expose source manuscripts.", readOnly = true, concurrencySafe = true)
    public AgentStoryKnowledgeReadService.DetailPage detail(
            @ToolParam(name = "candidate_id", description = "Candidate ID returned by the scoped list") Long candidateId,
            @ToolParam(name = "cursor", required = false, description = "Opaque nextCursor; concatenate contentJsonFragment until empty") String cursor,
            @ToolParam(name = "limit", required = false, description = "Fragment length, 1-8000 UTF-16 characters") Integer limit,
            RuntimeContext runtime) {
        return reads.candidate(support.resolveContext(runtime), candidateId, cursor, limit);
    }
}
