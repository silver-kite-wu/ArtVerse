package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.application.MangaAgentRunService;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Immutable parent/step/expert-to-native-task mapping, not a second task state machine. */
@Component
@RequiredArgsConstructor
public class ReviewExpertBindings {
    public static final String ATTRIBUTE = "review_expert_tasks";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MangaAgentRunService runs;
    private final AgentScopeRuntimeContextFactory contexts;

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 15)
    public boolean register(AgentRunRequest parent, AgentReviewExpert identity, String spawnCallId) {
        requireParent(parent);
        requireIdentity(parent,identity);
        if(spawnCallId==null||spawnCallId.isBlank())throw denied("missing spawn call");
        var attributes=attributes(parent,true);
        // Revalidate after acquiring the same root lock used by cancellation/lease transfer.
        requireParent(parent);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT status='RUNNING' AND owner_instance_id=? AND fencing_token=?
                    AND lease_until>clock_timestamp()
                FROM manga_agent_runs WHERE user_id=? AND request_id=? AND story_id=? AND chapter_id=?
                """, Boolean.class, parent.variables().get("execution_owner"),parent.variables().get("fencing_token"),
                Long.valueOf(parent.userId()),parent.requestId(),parent.storyId(),parent.chapterId())))
            throw denied("parent execution changed while reserving expert");
        ObjectNode steps=attributes.withObject(ATTRIBUTE);
        ObjectNode slots=steps.withObject(identity.parentStepId());
        JsonNode previous=slots.get(identity.expertId());
        if(previous!=null) {
            if(!identity.taskId().equals(previous.path("task_id").asText()))throw denied("expert already has a task");
            return false;
        }
        if(slots.size()>=4)throw denied("review expert quota exhausted");
        var binding=slots.putObject(identity.expertId());
        binding.put("expert_id",identity.expertId()).put("task_id",identity.taskId())
                .put("parent_request_id",identity.parentRequestId().toString()).put("parent_step_id",identity.parentStepId())
                .put("profile","local-tools-v1").put("spawn_call_id",spawnCallId);
        jdbc.update("UPDATE manga_agent_runs SET run_attributes_json=?::jsonb WHERE request_id=? AND user_id=?",
                json(attributes),parent.requestId(),Long.valueOf(parent.userId()));
        return true;
    }

    @Transactional(readOnly = true)
    public AgentReviewExpert find(AgentRunRequest parent,String expert) {
        requireParent(parent);
        var slot=attributes(parent,false).path(ATTRIBUTE).path(step(parent)).path(expert);
        return slot.isObject()?identity(parent,slot):null;
    }

    @Transactional(readOnly = true)
    public void requireTask(AgentRunRequest parent,String taskId) {
        requireParent(parent);
        for(var slot:attributes(parent,false).path(ATTRIBUTE).path(step(parent))) {
            var identity=identity(parent,slot);
            if(identity.taskId().equals(taskId))return;
        }
        throw denied("task does not belong to this review run");
    }

    public void requireParent(AgentRunRequest parent) {
        if(parent==null||parent.taskType()!=AgentTaskType.MANGA_REVIEW||parent.requestId()==null
                ||parent.variables().containsKey("review_expert")||Boolean.TRUE.equals(parent.variables().get("review_completed")))
            throw denied("only a parent review can manage experts");
        runs.requireToolExecution(contexts.create(parent).get(MangaAgentRuntimeContext.class),false);
    }

    private ObjectNode attributes(AgentRunRequest parent,boolean lock) {
        var rows=jdbc.queryForList("SELECT run_attributes_json::text AS attributes FROM manga_agent_runs WHERE user_id=? AND request_id=? AND story_id=? AND chapter_id=?"+(lock?" FOR UPDATE":""),
                Long.valueOf(parent.userId()),parent.requestId(),parent.storyId(),parent.chapterId());
        if(rows.size()!=1)throw denied("review root missing");
        try{return (ObjectNode)mapper.readTree(Objects.toString(rows.getFirst().get("attributes"),"{}"));}
        catch(java.io.IOException error){throw denied("invalid review root");}
    }
    private AgentReviewExpert identity(AgentRunRequest parent,JsonNode slot) {
        var identity=new AgentReviewExpert(slot.path("expert_id").asText(),slot.path("task_id").asText(),
                UUID.fromString(slot.path("parent_request_id").asText()),slot.path("parent_step_id").asText());
        requireIdentity(parent,identity);if(!"local-tools-v1".equals(slot.path("profile").asText()))throw denied("review profile changed");
        return identity;
    }
    private void requireIdentity(AgentRunRequest parent,AgentReviewExpert identity) {
        if(identity==null||!parent.requestId().equals(identity.parentRequestId())||!step(parent).equals(identity.parentStepId())
                ||identity.taskId()==null||identity.taskId().isBlank()||MangaReviewSubagentDeclarations.all().stream().noneMatch(d->d.getName().equals(identity.expertId())))
            throw denied("invalid review expert identity");
    }
    static String step(AgentRunRequest request){return Objects.toString(request.variables().get("step_id"),request.taskType().sessionSuffix());}
    private String json(Object object){try{return mapper.writeValueAsString(object);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private static BusinessException denied(String message){return new BusinessException(403,"REVIEW_EXPERT_DENIED: "+message);}
}
