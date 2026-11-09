package com.artverse.application.automation;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.MangaAgentRunService;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static com.artverse.application.automation.AutomationReadViews.*;
import static com.artverse.application.automation.AutomationStore.hash;
import static com.artverse.application.automation.AutomationStore.timestamp;

/** Story-scoped, read-only model access to existing automation data. */
@Service
@RequiredArgsConstructor
public class AutomationReadService {
    private final AutomationStore store;
    private final AutomationService service;
    private final AutomationInputService inputs;
    private final AutomationScheduleCalculator schedules;
    private final MangaAgentRunService agentRuns;
    private final AutomationReadTokens tokens;
    private static final Set<String> SAFE_ERRORS=Set.of("RESULT_UNCERTAIN","INPUT_CHANGED","MODEL_CONFIG_CHANGED","IMAGE_LIMIT_EXCEEDED",
            "AGENT_FAILED","EXECUTOR_FAILED","PROVIDER_ERROR","OVERLAP","MISSED_WINDOW","MISSED_SCHEDULE","CANCELLED","TIMEOUT","LEASE_LOST");
    private static final String RUN_FILTER="r.user_id=? AND r.automation_id=? AND r.definition_snapshot->'payload'->>'storyId'=?";

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(MangaAgentRuntimeContext context,AutomationDefinition input) {
        requireRead(context);
        AutomationDefinition normalized=service.previewModelDefinition(context,input);
        Instant observed=now(),expires=observed.plusSeconds(300);
        String definitionHash=hash(store.canonical(normalized));
        String snapshotHash=hash(store.canonical(normalized.payload()));
        var claims=claims(context,"preview",expires);
        claims.put("definition_hash",definitionHash);claims.put("input_snapshot_hash",snapshotHash);
        return new Preview(tokens.sign(claims),definitionHash,snapshotHash,definition(context,normalized),model(context,normalized),
                schedules.preview(normalized.schedule(),observed,5),observed,expires,false,false);
    }

    /** An informational read reference only; callers still need exact author decision and fresh validation. */
    @Transactional(readOnly=true)
    public void requirePreview(MangaAgentRuntimeContext context,String previewRef,String definitionHash) {
        requireRead(context);
        JsonNode claims=verify(context,previewRef,"preview",now());
        if(!Objects.equals(definitionHash,claims.path("definition_hash").asText()))throw bad("预览定义已变化，请重新预览");
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Plan get(MangaAgentRuntimeContext context,UUID id) {
        requireRead(context);var plan=plan(context,id);
        return new Plan(plan.id(),plan.status(),plan.version(),definition(context,plan.definition()),model(context,plan.definition()),
                plan.nextRunAt(),plan.createdAt(),plan.updatedAt());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RunPage runs(MangaAgentRuntimeContext context,UUID id,String cursor,Integer requestedLimit) {
        requireRead(context);var plan=plan(context,id);
        int limit=requestedLimit==null?20:requestedLimit;
        if(limit<1||limit>50)throw bad("运行分页大小必须为 1–50");
        var snapshot=store.jdbc().queryForMap("""
                SELECT md5(coalesce(string_agg(md5(to_jsonb(r)::text),'' ORDER BY r.created_at DESC,r.id DESC),'')) AS fingerprint,
                    clock_timestamp() AS observed_at FROM automation_run r WHERE
                """+RUN_FILTER,context.userId(),id,String.valueOf(context.storyId()));
        String fingerprint=hash(plan.version()+":"+snapshot.get("fingerprint"));
        Instant observed=((java.sql.Timestamp)snapshot.get("observed_at")).toInstant();
        JsonNode anchor=cursor==null?null:verify(context,cursor,"runs",observed);
        UUID lastId=null;Instant lastCreated=null;
        if(anchor!=null) {
            if(!id.toString().equals(anchor.path("automation_id").asText()))throw bad("运行游标不属于当前计划");
            if(!fingerprint.equals(anchor.path("snapshot").asText()))throw stale();
            try {lastId=UUID.fromString(anchor.path("last_id").asText());lastCreated=Instant.parse(anchor.path("last_created_at").asText());}
            catch(RuntimeException invalid){throw bad("运行游标末项无效");}
            if(!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM automation_run r WHERE "+RUN_FILTER+" AND r.id=? AND r.created_at=?)",
                    Boolean.class,context.userId(),id,String.valueOf(context.storyId()),lastId,timestamp(lastCreated))))throw bad("运行游标末项无效");
        }
        var rows=store.jdbc().queryForList("SELECT r.id FROM automation_run r WHERE "+RUN_FILTER
                +" AND (?::uuid IS NULL OR (r.created_at,r.id)<(?::timestamptz,?::uuid)) ORDER BY r.created_at DESC,r.id DESC LIMIT ?",
                context.userId(),id,String.valueOf(context.storyId()),lastId,timestamp(lastCreated),lastId,limit+1);
        boolean more=rows.size()>limit;
        List<Run> items=rows.subList(0,Math.min(rows.size(),limit)).stream().map(row->run(store.run((UUID)row.get("id"),context.userId()))).toList();
        String next=null;
        if(more) {
            var last=items.getLast();Instant expires=anchor==null?observed.plusSeconds(900):Instant.parse(anchor.path("expires_at").asText());
            var claims=claims(context,"runs",expires);claims.put("automation_id",id.toString());claims.put("snapshot",fingerprint);
            claims.put("last_id",last.runId().toString());claims.put("last_created_at",last.createdAt().toString());next=tokens.sign(claims);
        }
        return new RunPage(items,next,more,fingerprint);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RunDetail runDetail(MangaAgentRuntimeContext context,UUID id) {
        requireRead(context);
        if(id==null)throw bad("需要运行标识");
        var run=store.run(id,context.userId());plan(context,run.automationId());requireDefinition(context,run.definition());
        var artifacts=store.jdbc().query("""
                SELECT a.artifact_uuid,a.artifact_type,a.status,a.checksum,r.chapter_id
                FROM automation_run_execution e JOIN manga_agent_runs r ON r.id=e.agent_run_id
                JOIN manga_agent_run_artifacts a ON a.run_id=r.id
                JOIN chapters c ON c.id=r.chapter_id AND c.story_id=r.story_id JOIN stories s ON s.id=c.story_id AND s.user_id=r.user_id
                WHERE e.run_id=? AND r.user_id=? AND r.story_id=? ORDER BY a.created_at DESC,a.id DESC LIMIT 101
                """,(r,n)->new Artifact(r.getObject("artifact_uuid",UUID.class),r.getString("artifact_type"),r.getString("status"),r.getLong("chapter_id"),r.getString("checksum")),
                id,context.userId(),context.storyId());
        var pending=store.jdbc().query("""
                SELECT r.request_id,r.user_input_request_json FROM automation_run_execution e JOIN manga_agent_runs r ON r.id=e.agent_run_id
                JOIN chapters c ON c.id=r.chapter_id AND c.story_id=r.story_id JOIN stories s ON s.id=c.story_id AND s.user_id=r.user_id
                WHERE e.run_id=? AND r.user_id=? AND r.story_id=? AND r.status='WAITING_USER'
                    AND r.user_input_request_json IS NOT NULL ORDER BY e.id LIMIT 11
                """,(r,n)->{
                    JsonNode value=store.node(r.getString("user_input_request_json"));
                    return new PendingInput(r.getObject("request_id",UUID.class),text(value,"tool_call_id","toolCallId"),number(value,"pending_version","pendingVersion"),
                            Set.of("USER_QUESTION","NOVEL_DRAFT_REVIEW","STORYBOARD_OVERWRITE").contains(value.path("purpose").asText())?value.path("purpose").asText():"AUTHOR_REVIEW");
                },id,context.userId(),context.storyId());
        var images=store.jdbc().query("""
                SELECT t.chapter_id,p.image_number,p.state,p.result_image_id,i.version,p.error_code
                FROM automation_run_execution e JOIN scheduled_manga_generation t ON t.id=e.scheduled_manga_id
                JOIN chapters c ON c.id=t.chapter_id JOIN stories s ON s.id=c.story_id AND s.user_id=t.user_id
                JOIN scheduled_manga_panel p ON p.task_id=t.id LEFT JOIN manga_images i ON i.id=p.result_image_id AND i.chapter_id=t.chapter_id
                WHERE e.run_id=? AND t.user_id=? AND c.story_id=? ORDER BY t.chapter_id,p.image_number LIMIT 181
                """,(r,n)->new Image(r.getLong("chapter_id"),r.getInt("image_number"),r.getString("state"),
                        r.getObject("result_image_id",Long.class),r.getObject("version",Long.class),safeError(r.getString("error_code"))),id,context.userId(),context.storyId());
        return new RunDetail(run(run),definition(context,run.definition()),model(context,run.definition()),head(artifacts,100),head(pending,10),head(images,180),
                artifacts.size()>100,images.size()>180,pending.size()>10);
    }

    private void requireRead(MangaAgentRuntimeContext context) {
        AutomationAgentTools.requireInteractive(store,context,false);
        agentRuns.requireToolExecution(context,false);
    }
    private AutomationStore.Plan plan(MangaAgentRuntimeContext context,UUID id) {
        if(id==null)throw bad("需要计划标识");
        var plan=store.plan(id,context.userId(),false);requireDefinition(context,plan.definition());return plan;
    }
    private void requireDefinition(MangaAgentRuntimeContext context,AutomationDefinition definition) {
        if(definition==null||!Set.of("MANGA_AGENT","STORY_CHAT","MANGA_IMAGE").contains(Objects.toString(definition.executorKey(),""))
                ||definition.payload()==null||definition.payload().path("storyId").asLong(-1)!=context.storyId())throw denied();
        for(Long chapter:inputs.chapterIds(definition.payload(),definition.executorKey()))
            if(!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM chapters c JOIN stories s ON s.id=c.story_id WHERE c.id=? AND c.story_id=? AND s.user_id=?)",
                    Boolean.class,chapter,context.storyId(),context.userId())))throw denied();
    }
    private Definition definition(MangaAgentRuntimeContext context,AutomationDefinition definition) {
        requireDefinition(context,definition);
        return new Definition(definition.name(),definition.taskType(),definition.executorKey(),definition.instruction(),definition.schedule(),definition.limits(),
                new Targets(context.storyId(),inputs.chapterIds(definition.payload(),definition.executorKey()),definition.payload().path("inputPolicy").asText("RESOLVE_AT_RUN")),
                definition.misfirePolicy(),definition.overlapPolicy(),definition.maxLatenessSeconds(),definition.permissionMode());
    }
    private ModelDisplay model(MangaAgentRuntimeContext context,AutomationDefinition definition) {
        Long id=definition.payload().hasNonNull("modelConfigId")?definition.payload().path("modelConfigId").asLong():null;
        try {
            var config=inputs.config(context.userId(),definition.payload(),"IMAGE".equals(definition.taskType()));
            return new ModelDisplay(config.configId(),config.label(),config.provider(),config.model(),true);
        } catch(BusinessException unavailable){return new ModelDisplay(id,"配置当前不可用",null,definition.payload().path("model").asText(""),false);}
    }
    private Run run(AutomationStore.Run run) {
        String waiting=run.waitingReason()==null?null:Set.of("AGENT_INPUT","NOVEL_REVIEW","INPUT_CHANGED","RESULT_UNCERTAIN").contains(run.waitingReason())?run.waitingReason():"AUTHOR_ATTENTION";
        return new Run(run.id(),run.automationId(),run.automationVersion(),run.state(),run.triggerKind(),run.attemptCount(),safeError(run.errorCode()),waiting,
                run.scheduledFor(),run.createdAt(),run.startedAt(),run.completedAt());
    }
    private Map<String,Object> claims(MangaAgentRuntimeContext context,String kind,Instant expires) {
        var claims=new LinkedHashMap<String,Object>();claims.put("version",1);claims.put("kind",kind);claims.put("actor",context.userId());
        claims.put("story",context.storyId());claims.put("chapter",context.chapterId());claims.put("request_id",context.requestId().toString());
        claims.put("conversation_id",context.conversationId().toString());claims.put("step_id",context.stepId());claims.put("profile","local-tools-v1");claims.put("expires_at",expires.toString());return claims;
    }
    private JsonNode verify(MangaAgentRuntimeContext context,String token,String kind,Instant now) {
        JsonNode value=tokens.verify(token);
        if(!kind.equals(value.path("kind").asText())||value.path("actor").asLong(-1)!=context.userId()||value.path("story").asLong(-1)!=context.storyId()
                ||value.path("chapter").asLong(-1)!=context.chapterId()||!context.requestId().toString().equals(value.path("request_id").asText())
                ||!context.conversationId().toString().equals(value.path("conversation_id").asText())||!Objects.equals(context.stepId(),value.path("step_id").asText())
                ||!"local-tools-v1".equals(value.path("profile").asText()))throw bad("只读引用与当前作品或执行范围不一致");
        try {if(!Instant.parse(value.path("expires_at").asText()).isAfter(now))throw stale();}
        catch(java.time.format.DateTimeParseException invalid){throw bad("只读引用期限无效");}
        return value;
    }
    private Instant now(){return store.jdbc().queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();}
    private static String safeError(String code){return code==null?null:SAFE_ERRORS.contains(code)?code:"UNCLASSIFIED";}
    private static String text(JsonNode value,String snake,String camel){JsonNode field=value.has(snake)?value.get(snake):value.get(camel);return field==null||field.isNull()?null:field.asText();}
    private static Long number(JsonNode value,String snake,String camel){JsonNode field=value.has(snake)?value.get(snake):value.get(camel);return field==null||!field.canConvertToLong()?null:field.asLong();}
    private static <T> List<T> head(List<T> values,int max){return values.subList(0,Math.min(values.size(),max));}
    private static BusinessException stale(){return new BusinessException(409,"STALE_VERSION: 运行列表已变化或只读引用过期，请重新读取");}
    private static BusinessException bad(String message){return new BusinessException(400,message);}
    private static BusinessException denied(){return new BusinessException(403,"计划或运行不属于当前作品");}
}
