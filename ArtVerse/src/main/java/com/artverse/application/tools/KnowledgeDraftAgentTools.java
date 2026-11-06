package com.artverse.application.tools;

import com.artverse.agent.AgentToolAttempt;
import com.artverse.application.*;
import com.artverse.domain.KnowledgeUnitType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component @RequiredArgsConstructor
public class KnowledgeDraftAgentTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService audit;
    private final KnowledgeProposalService proposals;
    @Tool(name="draft_knowledge_change",description="Append an independent AI_PROPOSAL knowledge candidate with real current-chapter source and optional exact target baseline. Does not replace extraction candidates or approve facts. Review through the existing knowledge candidate UI.",concurrencySafe=false)
    public Map<String,Object> draft(@ToolParam(name="type",description="Existing KnowledgeSchema type") KnowledgeUnitType type,
            @ToolParam(name="title",description="Candidate title, 1-255 characters") String title,
            @ToolParam(name="body",description="Candidate description, 1-12000 characters") String body,
            @ToolParam(name="structured_data",description="Fields of the existing KnowledgeSchema type, preserving references, reveal chapter and evidence") Map<String,Object> data,
            @ToolParam(name="source_hash",description="Exact knowledge_source_hash returned by get_story_context") String source,
            @ToolParam(name="evidence",description="Source evidence, 1-4000 characters") String evidence,
            @ToolParam(name="target_unit_id",required=false,description="Optional current-story same-type formal knowledge ID") Long target,
            @ToolParam(name="base_version",required=false,description="Required exact formal version when updating a target") Integer base,
            RuntimeContext runtime) {
        var c=support.resolveContext(runtime);
        return audit.around("draft_knowledge_change",c.userId(),c.chapterId(),runtime,()->proposals.draft(c,new KnowledgeService.UnitInput(type.name(),title,body,"",data,3,null,null),target,base,source,evidence,AgentToolAttempt.require(runtime,"draft_knowledge_change")));
    }
}
