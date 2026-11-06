package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.application.knowledge.KnowledgeSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service @RequiredArgsConstructor
public class KnowledgeProposalService {
    private final CreativeProposalArtifacts proposals;
    private final KnowledgeCandidateService candidates;
    private final KnowledgeSchema json;
    private final JdbcTemplate jdbc;
    private final AgentToolEffectService effects;

    @Transactional
    public Map<String,Object> draft(MangaAgentRuntimeContext c,KnowledgeService.UnitInput input,Long target,Integer base,
            String sourceHash,String evidence,AgentToolAttempt attempt) {
        proposals.active(c);
        String hash=json.hash(Arrays.asList(c.requestId(),c.stepId(),input,target,base,sourceHash,evidence));
        UUID action=UUID.nameUUIDFromBytes(("knowledge-proposal:"+hash).getBytes(StandardCharsets.UTF_8));
        var existing=jdbc.queryForList("SELECT id FROM knowledge_candidates WHERE story_id=? AND user_id=? AND source_type='AI_PROPOSAL' AND source_id=? ORDER BY id",c.storyId(),c.userId(),action.toString());
        KnowledgeCandidateService.CandidateView candidate;
        if(!existing.isEmpty()) candidate=candidates.detail(c.storyId(),((Number)existing.getFirst().get("id")).longValue(),c.userId());
        else candidate=candidates.appendAiProposal(c.storyId(),c.chapterId(),c.userId(),action.toString(),input,target,base,sourceHash,evidence);
        effects.record(attempt,c,"KNOWLEDGE_PROPOSAL",action,"KNOWLEDGE_CANDIDATE","DRAFT",hash,Map.of("candidate_id",candidate.id().toString(),"requires_author_review",true));
        return Map.of("candidate_id",candidate.id().toString(),"status",candidate.status(),"version",candidate.version().toString(),"source_stale",candidate.sourceStale(),"confirmed_fact",false,"review_location","知识库 / 待审候选");
    }
}
