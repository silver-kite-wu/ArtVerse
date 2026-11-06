package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.common.BusinessException;
import com.artverse.application.knowledge.KnowledgeSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Scoped persistence for the three creative proposal kinds. It grants no execution authority. */
@Service
@RequiredArgsConstructor
public class CreativeProposalArtifacts {
    public static final Set<String> TYPES = Set.of("SETTINGS_CHANGE", "CHARACTER_CHANGE", "IMAGE_PROPOSAL", "IMAGE_RECOVERY");
    private final JdbcTemplate jdbc;
    private final KnowledgeSchema json;
    private final MangaAgentRunService runs;
    private final AgentToolEffectService effects;

    @Transactional
    public Map<String,Object> active(MangaAgentRuntimeContext c) {
        if(c==null || c.userId()==null || c.stepId()==null || c.automationClaim()!=null
                || !Set.of(AgentTaskType.MANGA_CONVERSATION,AgentTaskType.STORY_CHAT_READ,AgentTaskType.STORY_CHAT_WRITE).contains(c.taskType())) throw denied();
        var rows=jdbc.queryForList("""
                SELECT r.*,v.conversation_uuid,v.status AS conversation_status,v.legacy_import_key FROM manga_agent_runs r
                JOIN ai_conversations v ON v.id=r.conversation_id AND v.user_id=r.user_id AND v.story_id=r.story_id AND v.chapter_id=r.chapter_id AND v.conversation_type=r.run_type
                JOIN stories s ON s.id=r.story_id AND s.user_id=r.user_id
                JOIN chapters ch ON ch.id=r.chapter_id AND ch.story_id=r.story_id
                WHERE r.user_id=? AND r.story_id=? AND r.chapter_id=? AND r.request_id=? AND v.conversation_uuid=? FOR UPDATE OF r
                """,c.userId(),c.storyId(),c.chapterId(),c.requestId(),c.conversationId());
        if(rows.size()!=1) throw denied();
        var r=rows.getFirst(); requireInteractive(r);
        runs.requireToolExecution(c,false);
        if(!"RUNNING".equals(r.get("status")))throw conflict("RUN_NOT_ACTIVE");
        binding(r,c.stepId(),c.taskType().name());
        return r;
    }

    @Transactional
    public Map<String,Object> create(MangaAgentRuntimeContext c,String type,Map<String,Object> body,AgentToolAttempt attempt) {
        if(!TYPES.contains(type)) throw denied();
        var root=active(c); var payload=new LinkedHashMap<>(body);
        payload.put("step_id",c.stepId());payload.put("task_type",c.taskType().name());
        payload.put("execution_binding",binding(root,c.stepId(),c.taskType().name()));
        payload.put("actor_id",c.userId());payload.put("story_id",c.storyId());payload.put("chapter_id",c.chapterId());
        payload.put("source_request_id",c.requestId().toString());payload.put("conversation_id",c.conversationId().toString());
        // Hash a normalized JSON tree, so typed records and jsonb key ordering share one contract.
        payload=new LinkedHashMap<>(json.read(json.write(payload)));
        String checksum=json.hash(payload);
        var previous=jdbc.queryForList("SELECT artifact_uuid FROM manga_agent_run_artifacts WHERE run_id=? AND artifact_type=? AND checksum=? ORDER BY id LIMIT 1",root.get("id"),type,checksum);
        UUID id;
        if(previous.isEmpty()) {
            if(jdbc.queryForObject("SELECT count(*) FROM manga_agent_run_artifacts WHERE run_id=? AND artifact_type IN ('SETTINGS_CHANGE','CHARACTER_CHANGE','IMAGE_PROPOSAL','IMAGE_RECOVERY')",Integer.class,root.get("id"))>=16) throw conflict("本轮提案过多，请审阅已有提案");
            id=UUID.randomUUID();
            jdbc.update("INSERT INTO manga_agent_run_artifacts(artifact_uuid,run_id,artifact_type,status,schema_version,payload,evaluation,checksum,created_at,updated_at) VALUES (?,?,?,'DRAFT','1',?::jsonb,'{}'::jsonb,?,clock_timestamp(),clock_timestamp())",id,root.get("id"),type,json.write(payload),checksum);
        } else id=(UUID)previous.getFirst().get("artifact_uuid");
        var row=owned(c.userId(),id,false);
        effects.record(attempt,c,type,id,type,"DRAFT",checksum,Map.of("requires_author_review",true));
        return view(row);
    }

    public Map<String,Object> owned(long actor,UUID id,boolean lock) {
        var rows=jdbc.queryForList("""
                SELECT a.*,r.user_id,r.story_id,r.chapter_id,r.request_id,r.run_attributes_json,r.status AS run_status,
                v.conversation_uuid,v.status AS conversation_status,v.legacy_import_key FROM manga_agent_run_artifacts a
                JOIN manga_agent_runs r ON r.id=a.run_id JOIN stories s ON s.id=r.story_id AND s.user_id=r.user_id
                JOIN ai_conversations v ON v.id=r.conversation_id AND v.user_id=r.user_id AND v.story_id=r.story_id AND v.chapter_id=r.chapter_id
                WHERE a.artifact_uuid=? AND r.user_id=? AND a.artifact_type IN ('SETTINGS_CHANGE','CHARACTER_CHANGE','IMAGE_PROPOSAL','IMAGE_RECOVERY')
                """+(lock?" FOR UPDATE OF r,a":""),id,actor);
        if(rows.size()!=1)throw new BusinessException(404,"提案不存在");return rows.getFirst();
    }

    /** Caller holds the run and artifact locks. A terminal receipt can be replayed after cancellation. */
    public void verifyDecision(Map<String,Object> row,String decision,String hash) {
        if(!Set.of("confirm","discard").contains(Objects.toString(decision,"")))throw new BusinessException(400,"无效的决定");
        var p=payload(row);
        if(hash==null||!hash.equals(row.get("checksum"))||!hash.equals(json.hash(p)))throw conflict("提案摘要已变化，请重新审阅");
        String state=row.get("status").toString();
        if(Set.of("COMMITTED","REJECTED").contains(state)) {
            if(!("COMMITTED".equals(state)?"confirm":"discard").equals(decision))throw conflict("提案已收到另一项决定");return;
        }
        if(!"DRAFT".equals(state))throw conflict("提案当前不可处理");
        var receipt=receipt(row);
        if(Boolean.TRUE.equals(receipt.get("approved"))) {
            if(!"confirm".equals(decision))throw conflict("已批准的动作可能已受理，请用原提案重试");return;
        }
        if("discard".equals(decision))return;
        requireInteractive(row);
        if(Set.of("CANCELLED","INTERRUPTED","FAILED").contains(Objects.toString(row.get("run_status"),""))||!"ACTIVE".equals(row.get("conversation_status"))) throw conflict("原运行已停止");
        if(!binding(row,Objects.toString(p.get("step_id")),Objects.toString(p.get("task_type"))).equals(p.get("execution_binding")))throw conflict("原任务契约已变化");
        if(((java.sql.Timestamp)row.get("created_at")).toInstant().plusSeconds(1800).isBefore(Instant.now()))throw conflict("提案已过期，请重新准备");
        if(!Objects.toString(row.get("user_id")).equals(Objects.toString(p.get("actor_id")))||!Objects.toString(row.get("story_id")).equals(Objects.toString(p.get("story_id")))||!Objects.toString(row.get("chapter_id")).equals(Objects.toString(p.get("chapter_id")))||!row.get("request_id").toString().equals(p.get("source_request_id"))||!row.get("conversation_uuid").toString().equals(p.get("conversation_id")))throw denied();
    }

    public void finish(Map<String,Object> row,String status,Map<String,Object> result) {
        var r=new LinkedHashMap<>(result);r.put("artifact_id",row.get("artifact_uuid").toString());r.put("status",status);
        r.put("command_hash",row.get("checksum"));r.put("actor_id",row.get("user_id"));r.put("decided_at",Instant.now().toString());
        if(jdbc.update("UPDATE manga_agent_run_artifacts SET status=?,evaluation=?::jsonb,updated_at=clock_timestamp() WHERE artifact_uuid=? AND status='DRAFT'",status,json.write(r),row.get("artifact_uuid"))!=1)throw conflict("提案已变化");
        effects.recordAuthorReview(((Number)row.get("user_id")).longValue(),((Number)row.get("chapter_id")).longValue(),(UUID)row.get("request_id"),(UUID)row.get("artifact_uuid"),r);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> list(long actor,Long chapter,UUID conversation,UUID before,int limit) {
        if((chapter==null)!=(conversation==null)||limit<1||limit>50)throw new BusinessException(400,"提案分页范围无效");
        if(before!=null) { var row=owned(actor,before,false);if(chapter!=null&&(!Objects.equals(row.get("conversation_uuid"),conversation)||((Number)row.get("chapter_id")).longValue()!=chapter))throw denied(); }
        var rows=jdbc.queryForList("""
                SELECT a.artifact_uuid FROM manga_agent_run_artifacts a JOIN manga_agent_runs r ON r.id=a.run_id
                JOIN ai_conversations c ON c.id=r.conversation_id JOIN stories s ON s.id=r.story_id AND s.user_id=r.user_id
                WHERE r.user_id=? AND (?::bigint IS NULL OR (r.chapter_id=? AND c.conversation_uuid=?)) AND a.artifact_type IN ('SETTINGS_CHANGE','CHARACTER_CHANGE','IMAGE_PROPOSAL','IMAGE_RECOVERY')
                AND (?::uuid IS NULL OR a.id<(SELECT id FROM manga_agent_run_artifacts WHERE artifact_uuid=?)) ORDER BY a.id DESC LIMIT ?
                """,actor,chapter,chapter,conversation,before,before,limit+1);
        var items=rows.stream().limit(limit).map(r->view(owned(actor,(UUID)r.get("artifact_uuid"),false))).toList();
        boolean more=rows.size()>limit;return Map.of("items",items,"hasMore",more,"nextCursor",more?items.getLast().get("artifactId"):"");
    }
    public Map<String,Object> view(Map<String,Object> row) {
        var p=payload(row);var r=receipt(row);var v=new LinkedHashMap<String,Object>();
        v.put("artifactId",row.get("artifact_uuid"));v.put("kind",row.get("artifact_type"));v.put("status",Boolean.TRUE.equals(r.get("approved"))&&"DRAFT".equals(row.get("status"))?"APPROVED_PENDING":row.get("status"));v.put("commandHash",row.get("checksum"));
        v.put("chapterId",row.get("chapter_id"));v.put("conversationId",row.get("conversation_uuid"));
        v.put("expiresAt",((java.sql.Timestamp)row.get("created_at")).toInstant().plusSeconds(1800).toString());
        for(String key:List.of("before","after","base_version","character_id","mode","selection","targets","request_id","operation_id","recovery_action","recovery_version","estimated_cost","quota_availability","model_calls","execution_lane"))if(p.containsKey(key))v.put(key,p.get(key));
        var publicReceipt=new LinkedHashMap<>(r);publicReceipt.remove("commands");v.put("receipt",publicReceipt);return v;
    }
    public Map<String,Object> payload(Map<String,Object> row) { return json.read(row.get("payload").toString()); }
    public Map<String,Object> receipt(Map<String,Object> row) { return json.read(Objects.toString(row.get("evaluation"),"{}")); }
    private Map<String,Object> binding(Map<String,Object> row,String step,String task) {
        var attrs=json.read(row.get("run_attributes_json").toString());
        if(attrs.containsKey("review_result")||attrs.containsKey("review_summary_continuation"))throw denied();
        Object binding;
        if("MANGA_CONVERSATION".equals(task))binding=((Map<?,?>)attrs.getOrDefault("manga_execution_bindings",Map.of())).get(step);
        else binding=attrs.get("story_chat_execution");
        if(!(binding instanceof Map<?,?> m)||!"local-tools-v1".equals(m.get("profile"))||!step.equals(m.get("step_id"))||!task.equals(m.get("task_type")))throw conflict("缺少原任务步骤或工具契约");
        return json.read(json.write(binding));
    }
    private void requireInteractive(Map<String,Object> row) {
        if(Objects.toString(row.get("legacy_import_key"),"").startsWith("automation:")||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM automation_run_execution e JOIN automation_run a ON a.id=e.run_id WHERE e.agent_run_id=? OR (a.user_id=? AND (e.request_id=? OR e.conversation_uuid=?)))",Boolean.class,row.getOrDefault("run_id",row.get("id")),row.get("user_id"),row.get("request_id"),row.get("conversation_uuid")))) throw denied();
    }
    public static BusinessException conflict(String message) {return new BusinessException(409,message);}
    public static BusinessException denied() {return new BusinessException(403,"SCOPE_DENIED: 需要当前作品的交互提案审核权限");}
}
