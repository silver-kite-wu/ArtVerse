package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.artverse.application.automation.AutomationStore.*;

@Service
@RequiredArgsConstructor
public class AutomationService {
    private final AutomationStore store;
    private final AutomationScheduleCalculator schedules;
    private final AutomationInputService inputs;
    @Value("${artverse.automations.max-active-plans-per-user:10}") private int maxActivePlans;

    public Map<String,Object> preview(AutomationDefinition.Schedule schedule) {
        var normalized=schedules.normalize(schedule);
        return Map.of("schedule",normalized,"occurrences",schedules.preview(normalized,Instant.now(),5));
    }

    @Transactional
    public Plan create(long userId, String key, AutomationDefinition input) {
        requireKey(key);
        // Serialize this user's quota and idempotency decisions, including different concurrent keys.
        lockUser(userId);
        String requestHash=hash(store.canonical(input));
        var previous=store.jdbc().queryForList("SELECT id,request_hash FROM automation WHERE user_id=? AND idempotency_key=?",userId,key);
        if(!previous.isEmpty()) {
            if(!requestHash.equals(previous.getFirst().get("request_hash")))throw conflict("同一幂等键不能创建不同任务");
            return store.plan((UUID)previous.getFirst().get("id"),userId,false);
        }
        AutomationDefinition definition=normalize(userId,input);
        checkQuota(userId);
        Instant next=schedules.nextAfter(definition.schedule(),Instant.now().plusSeconds("ONCE".equals(definition.schedule().kind())?15:0));
        if(next==null)throw bad("执行时间必须至少在 15 秒之后");
        UUID id=UUID.randomUUID();
        store.jdbc().update("""
                INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,next_run_at,idempotency_key,request_hash)
                VALUES (?,?,?,?,?,?::jsonb,?,?,?,?)
                """,id,userId,definition.name(),definition.taskType(),definition.executorKey(),store.json(definition),
                definition.schedule().timezone(),timestamp(next),key,requestHash);
        return store.plan(id,userId,false);
    }

    @Transactional
    public Plan update(long userId,UUID id,AutomationDefinition input) {
        lockUser(userId);
        Plan current=store.plan(id,userId,true);
        version(current,input.expectedVersion());
        if(Set.of("COMPLETED","ARCHIVED").contains(current.status()))throw conflict("请复制已结束或归档的任务来创建新计划");
        AutomationDefinition definition=normalize(userId,input);
        Instant next="ACTIVE".equals(current.status())?schedules.nextAfter(definition.schedule(),Instant.now()):null;
        if("ACTIVE".equals(current.status())&&next==null)throw bad("执行时间必须在未来");
        // A materialized one-shot run owns its original definition; editing must not create a second trigger.
        if("ONCE".equals(definition.schedule().kind())&&store.blocking(id))next=null;
        store.jdbc().update("UPDATE automation SET name=?,task_type=?,executor_key=?,definition=?::jsonb,timezone_id=?,next_run_at=?,version=version+1,updated_at=now() WHERE id=?",
                definition.name(),definition.taskType(),definition.executorKey(),store.json(definition),definition.schedule().timezone(),timestamp(next),id);
        return store.plan(id,userId,false);
    }

    @Transactional
    public Plan lifecycle(long userId,UUID id,String action,Long expectedVersion) {
        lockUser(userId);
        Plan current=store.plan(id,userId,true);
        version(current,expectedVersion);
        String target=switch(action) { case "pause" -> "PAUSED"; case "resume" -> "ACTIVE"; case "archive" -> "ARCHIVED"; default -> throw bad("未知任务操作"); };
        if(current.status().equals(target))return current;
        if("ARCHIVED".equals(current.status()) || ("COMPLETED".equals(current.status())&&!"archive".equals(action)))throw conflict("此任务已结束，请创建新计划");
        if("ACTIVE".equals(target))checkQuota(userId);
        Instant next=null;
        if("ACTIVE".equals(target)) {
            var schedule=current.definition().schedule();
            if(!"ONCE".equals(schedule.kind())||!store.blocking(id)) {
                next=schedules.nextAfter(schedule,Instant.now());
                if(next==null&&"ONCE".equals(schedule.kind()))next=Instant.now(); // one-shot missed during an explicit pause
            }
        }
        store.jdbc().update("UPDATE automation SET status=?,next_run_at=?,version=version+1,updated_at=now() WHERE id=?",target,timestamp(next),id);
        return store.plan(id,userId,false);
    }

    @Transactional
    public Run runNow(long userId,UUID id,String key) {
        requireKey(key);
        Plan plan=store.plan(id,userId,true);
        Optional<Run> existing=store.invocation(id,"manual:"+key);
        if(existing.isPresent())return existing.get();
        if("ARCHIVED".equals(plan.status()))throw conflict("归档任务不能立即执行");
        if(store.blocking(id))throw conflict("该任务已有运行或待确认事项");
        UUID runId=store.insertRun(plan,Instant.now(),"MANUAL","manual:"+key,"QUEUED",null,"用户立即执行");
        return store.run(runId,userId);
    }

    @Transactional
    public void materializeDue() {
        Instant now=Instant.now();
        for(Plan plan:store.duePlans(20)) {
            var definition=plan.definition();
            var schedule=definition.schedule();
            boolean once="ONCE".equals(schedule.kind());
            Instant due=plan.nextRunAt();
            boolean blocked=store.blocking(plan.id());
            if(!once&&"RUN_LATEST".equals(definition.misfirePolicy())) {
                Instant latest=schedules.latestAtOrBefore(schedule,now);
                if(latest!=null&&latest.isAfter(due))due=latest;
            }
            long delay=Duration.between(due,now).getSeconds();
            int window=definition.maxLatenessSeconds()==null?(once?Integer.MAX_VALUE:86400):definition.maxLatenessSeconds();
            String code=blocked?"OVERLAP":delay>window?"MISSED_WINDOW":(!once&&"SKIP".equals(definition.misfirePolicy())&&delay>60)?"MISSED_SCHEDULE":null;
            String summary=code==null?(delay>60?"延迟执行：服务恢复后补跑":"等待执行"):
                    "OVERLAP".equals(code)?"上一次运行尚未结束或等待确认，已跳过本周期":"错过允许的执行时间，已跳过";
            UUID runId=store.insertRun(plan,due,"SCHEDULED","scheduled:"+due,code==null?"QUEUED":"SKIPPED",code,summary);
            if(!once&&due.isAfter(plan.nextRunAt()))store.event(runId,"MISFIRE_COALESCED",Map.of("firstMissed",plan.nextRunAt().toString(),"latest",due.toString()));
            store.advance(plan,once?null:schedules.nextAfter(schedule,now));
            if(once&&code!=null)store.jdbc().update("UPDATE automation SET status='COMPLETED',updated_at=now() WHERE id=?",plan.id());
        }
    }

    @Transactional
    public Run cancel(long userId,UUID id,String key) {
        requireKey(key);
        Run current=store.run(id,userId);
        store.plan(current.automationId(),userId,true);
        current=store.run(id,userId);
        if(terminal(current.state()))return current;
        String state=store.executions(id).isEmpty()&&"QUEUED".equals(current.state())?"CANCELLED":"CANCEL_REQUESTED";
        store.jdbc().update("UPDATE automation_run SET state=?,cancel_requested_at=now(),completed_at=CASE WHEN ?='CANCELLED' THEN now() END WHERE id=?",state,state,id);
        store.event(id,state,Map.of("summary","用户请求停止本次运行"));
        if("CANCELLED".equals(state))store.notify(current,state,"本次运行已停止");
        completeOnce(store.run(id,userId));
        return store.run(id,userId);
    }

    @Transactional
    public Run retry(long userId,UUID id,String key) {
        requireKey(key);
        Run current=store.run(id,userId);
        store.plan(current.automationId(),userId,true);
        String hash=hash("retry:"+id);
        if(previousAction(id,key,hash))return store.run(id,userId);
        current=store.run(id,userId);
        if(!Set.of("FAILED","PARTIAL_FAILED").contains(current.state()))throw conflict("只有明确失败的运行可以重试");
        if("RESULT_UNCERTAIN".equals(current.errorCode()))throw conflict("供应商结果不确定，需要人工核对");
        if(store.blocking(current.automationId()))throw conflict("该计划已有另一条运行");
        store.jdbc().update("UPDATE automation_run SET state='QUEUED',completed_at=NULL,error_code=NULL,error_message=NULL,waiting_reason=NULL,next_attempt_at=now(),lease_owner=NULL,lease_until=NULL,response=?::jsonb,response_key=?,response_hash=? WHERE id=?",
                "{\"action\":\"retry\"}",key,hash,id);
        store.jdbc().update("UPDATE automation SET status=CASE WHEN status='COMPLETED' THEN 'ACTIVE' ELSE status END WHERE id=?",current.automationId());
        store.event(id,"RETRY_REQUESTED",Map.of("summary","仅重试明确失败项"));
        return store.run(id,userId);
    }

    @Transactional
    public Run respond(long userId,UUID id,String key,JsonNode response) {
        requireKey(key);
        if(response==null||!response.isObject()||store.json(response).length()>8000)throw bad("响应格式无效");
        Run current=store.run(id,userId);
        store.plan(current.automationId(),userId,true);
        String digest=hash(store.canonical(response));
        if(previousAction(id,key,digest))return store.run(id,userId);
        current=store.run(id,userId);
        if(!"WAITING_USER".equals(current.state()))throw conflict("本次运行当前没有等待用户处理");
        if("RESULT_UNCERTAIN".equals(current.waitingReason())&&!"close".equals(response.path("action").asText()))
            throw bad("结果不确定的请求只允许核对后关闭；重新生成需要新建明确请求");
        if("close".equals(response.path("action").asText())) {
            store.jdbc().update("UPDATE automation_run SET state='CANCEL_REQUESTED',next_attempt_at=now(),response=?::jsonb,response_key=?,response_hash=? WHERE id=?",store.json(response),key,digest,id);
            store.event(id,"CANCEL_REQUESTED",Map.of("summary","用户关闭待处理运行，等待业务收尾"));
            completeOnce(store.run(id,userId));
        } else {
            if(!Set.of("AGENT_INPUT","INPUT_CHANGED","CONFIGURATION").contains(Objects.toString(current.waitingReason(),"")))throw bad("该待处理事项不能恢复执行");
            if(Set.of("INPUT_CHANGED","CONFIGURATION").contains(current.waitingReason())) {
                if(!"refresh-input".equals(response.path("action").asText()))throw bad("需要明确确认刷新输入");
                var refreshed=inputs.refreshedDefinition(userId,current.definition());
                store.jdbc().update("UPDATE automation_run SET definition_snapshot=?::jsonb,input_snapshot=NULL,snapshot_hash=NULL WHERE id=?",store.json(refreshed),id);
                var snapshot=inputs.prepare(store.run(id));
                store.jdbc().update("UPDATE automation_run SET input_snapshot=?::jsonb,snapshot_hash=? WHERE id=?",store.json(snapshot),hash(store.json(snapshot)),id);
                inputs.refreshChildren(store.run(id),store.executions(id));
                store.event(id,"INPUT_REFRESHED",Map.of("summary","用户确认后重新冻结输入，已成功图片保留"));
            }
            store.jdbc().update("UPDATE automation_run SET state='QUEUED',next_attempt_at=now(),response=?::jsonb,response_key=?,response_hash=?,lease_owner=NULL,lease_until=NULL WHERE id=?",store.json(response),key,digest,id);
            store.event(id,"USER_RESPONDED",Map.of("summary","已收到用户响应，等待重新校验"));
        }
        return store.run(id,userId);
    }

    private boolean previousAction(UUID id,String key,String digest) {
        var values=store.jdbc().queryForList("SELECT request_hash FROM automation_run_action WHERE run_id=? AND idempotency_key=?",id,key);
        if(!values.isEmpty()) {
            if(!digest.equals(values.getFirst().get("request_hash")))throw conflict("同一幂等键不能用于不同响应");
            return true;
        }
        store.jdbc().update("INSERT INTO automation_run_action(run_id,idempotency_key,request_hash) VALUES (?,?,?)",id,key,digest);
        return false;
    }
    public void completeOnce(Run run) {
        if(terminal(run.state())&&"ONCE".equals(run.definition().schedule().kind()))
            store.jdbc().update("UPDATE automation SET status=CASE WHEN status='ARCHIVED' THEN status ELSE 'COMPLETED' END,updated_at=now() WHERE id=? AND next_run_at IS NULL",run.automationId());
    }
    public Plan get(long userId,UUID id) { return store.plan(id,userId,false); }
    public List<Plan> list(long userId,String status,String type,boolean attention,String search,int page,int size) {
        if(!status.isBlank()&&!Set.of("ACTIVE","PAUSED","COMPLETED","ARCHIVED").contains(status))throw bad("无效任务状态");
        if(!type.isBlank()&&!Set.of("AGENT","IMAGE").contains(type))throw bad("无效任务类型");
        return store.plans(userId,status,type,attention,search,Math.max(0,page),Math.min(100,Math.max(1,size)));
    }
    public Map<String,Object> runDetail(long userId,UUID id) {
        Run run=store.run(id,userId);
        var executions=store.executions(id);
        Map<String,Object> detail=new LinkedHashMap<>();
        detail.put("run",run);detail.put("executions",executions);detail.put("events",store.events(id,0,200));
        List<Map<String,Object>> artifacts=new ArrayList<>();
        for(var execution:executions) {
            if(execution.agentRunId()!=null) {
                var children=store.jdbc().queryForList("SELECT user_input_request_json FROM manga_agent_runs WHERE id=? AND user_id=?",execution.agentRunId(),userId);
                if(!children.isEmpty()&&children.getFirst().get("user_input_request_json") instanceof String request)detail.put("userInputRequest",store.node(request));
                artifacts.addAll(store.jdbc().query("SELECT a.* FROM manga_agent_run_artifacts a JOIN manga_agent_runs r ON r.id=a.run_id WHERE a.run_id=? AND r.user_id=? ORDER BY a.id",(r,n)-> {
                    Map<String,Object> artifact=new LinkedHashMap<>();artifact.put("id",r.getString("artifact_uuid"));artifact.put("kind",r.getString("artifact_type"));
                    artifact.put("title",Set.of("NOVEL_CONTENT","NOVEL_CONTENT_DRAFT").contains(r.getString("artifact_type"))?"小说草稿":"Agent 产物");artifact.put("state",r.getString("status"));
                    JsonNode payload=store.node(r.getString("payload"));artifact.put("content",payload.has("content")?payload.path("content").asText():payload.toPrettyString());return artifact;
                },execution.agentRunId(),userId));
            }
            if(execution.imageRecordId()!=null)artifacts.addAll(store.jdbc().query("SELECT * FROM image_gen_records WHERE id=? AND user_id=?",(r,n)-> {
                Map<String,Object> artifact=new LinkedHashMap<>();artifact.put("id","image:"+r.getLong("id"));artifact.put("kind","IMAGE_GEN");artifact.put("title","生成图片");
                artifact.put("state",r.getString("automation_dispatch_state"));artifact.put("url",r.getString("image_path"));artifact.put("error",r.getString("failure_reason"));return artifact;
            },execution.imageRecordId(),userId));
            if(execution.scheduledMangaId()!=null)artifacts.addAll(store.jdbc().query("SELECT p.*,i.image_path FROM scheduled_manga_panel p JOIN scheduled_manga_generation t ON t.id=p.task_id LEFT JOIN manga_images i ON i.id=p.result_image_id WHERE p.task_id=? AND t.user_id=? ORDER BY p.image_number",(r,n)-> {
                Map<String,Object> artifact=new LinkedHashMap<>();artifact.put("id","panel:"+r.getLong("id"));artifact.put("kind","MANGA_IMAGE");artifact.put("title",execution.targetKey()+" · 第 "+r.getInt("image_number")+" 张");
                artifact.put("state",r.getString("state"));artifact.put("url",r.getString("image_path"));artifact.put("error",r.getString("error_message"));return artifact;
            },execution.scheduledMangaId(),userId));
        }
        detail.put("artifacts",artifacts);return detail;
    }
    public List<Run> runs(long userId,UUID id,UUID before,int size) { store.plan(id,userId,false);return store.runs(id,before,Math.min(100,Math.max(1,size))); }
    public List<Map<String,Object>> events(long userId,UUID id,long after) { store.run(id,userId);return store.events(id,Math.max(0,after),200); }
    public List<Map<String,Object>> notifications(long userId) {
        return store.jdbc().query("SELECT n.*,a.name FROM automation_notification n JOIN automation_run r ON r.id=n.run_id JOIN automation a ON a.id=r.automation_id WHERE n.user_id=? ORDER BY n.created_at DESC LIMIT 100",(r,n) -> {
            Map<String,Object> value=new LinkedHashMap<>();value.put("id",r.getLong("id"));value.put("runId",r.getObject("run_id",UUID.class));value.put("name",r.getString("name"));value.put("kind",r.getString("notification_kind"));value.put("summary",r.getString("summary"));value.put("read",r.getTimestamp("read_at")!=null);value.put("createdAt",r.getTimestamp("created_at").toInstant());return value;
        },userId);
    }
    public void markRead(long userId,long id) { if(store.jdbc().update("UPDATE automation_notification SET read_at=COALESCE(read_at,now()) WHERE id=? AND user_id=?",id,userId)==0)throw new BusinessException(404,"通知不存在"); }

    private AutomationDefinition normalize(long userId,AutomationDefinition input) {
        if(input==null)throw bad("缺少任务设置");
        String name=Objects.toString(input.name(),"").strip(), instruction=Objects.toString(input.instruction(),"").strip();
        if(name.isEmpty()||name.length()>120)throw bad("任务名称长度为 1 至 120 字符");
        if(instruction.length()>20000)throw bad("任务说明过长");
        if(!Set.of("MANGA_AGENT","STORY_CHAT","MANGA_IMAGE","IMAGE_GEN").contains(Objects.toString(input.executorKey(),"")))throw bad("未知执行器");
        String taskType=Set.of("MANGA_AGENT","STORY_CHAT").contains(input.executorKey())?"AGENT":"IMAGE";
        if(!taskType.equals(input.taskType()))throw bad("任务类型与执行器不匹配");
        if(instruction.isBlank()&&!"MANGA_IMAGE".equals(input.executorKey()))throw bad("请输入任务说明或图片提示词");
        var limits=input.limits()==null?new AutomationDefinition.Limits(20,10,1800):input.limits();
        limits=new AutomationDefinition.Limits(bound(limits.maxImagesPerRun(),20,1,180),bound(limits.maxModelCallsPerRun(),10,1,50),bound(limits.maxDurationSeconds(),1800,60,7200));
        String misfire=Objects.toString(input.misfirePolicy(),"RUN_LATEST");
        if(!Set.of("RUN_LATEST","SKIP").contains(misfire)||!"SKIP".equals(Objects.toString(input.overlapPolicy(),"SKIP")))throw bad("不支持此补跑或重叠策略");
        if(input.maxLatenessSeconds()!=null&&(input.maxLatenessSeconds()<0||input.maxLatenessSeconds()>604800))throw bad("最大延迟必须为 0 至 604800 秒");
        var normalized=new AutomationDefinition(name,taskType,input.executorKey(),instruction,schedules.normalize(input.schedule()),input.payload(),limits,misfire,"SKIP",input.maxLatenessSeconds(),null,
                Set.of("TASK_CENTER","CHAPTER","IMAGE_GEN","CONVERSATION").contains(Objects.toString(input.origin(),""))?input.origin():"TASK_CENTER",input.sourceConversationId());
        return inputs.validateAndNormalize(userId,normalized);
    }
    private int bound(Integer value,int fallback,int min,int max) { int actual=value==null?fallback:value;if(actual<min||actual>max)throw bad("任务执行上限超出允许范围");return actual; }
    private void lockUser(long id) { if(store.jdbc().queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",id).isEmpty())throw new BusinessException(404,"用户不存在"); }
    private void checkQuota(long userId) { Integer count=store.jdbc().queryForObject("SELECT count(*) FROM automation WHERE user_id=? AND status='ACTIVE'",Integer.class,userId);if(count!=null&&count>=maxActivePlans)throw new BusinessException(429,"已达到启用计划数量上限"); }
    private void version(Plan plan,Long expected) { if(expected==null||plan.version()!=expected)throw conflict("任务已更新，请刷新后操作"); }
    public static boolean terminal(String state) { return Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED","CANCELLED","SKIPPED").contains(state); }
    public static void requireKey(String key) { if(key==null||key.isBlank()||key.length()>128)throw bad("需要有效的 Idempotency-Key"); }
    static BusinessException bad(String message) { return new BusinessException(400,message); }
    static BusinessException conflict(String message) { return new BusinessException(409,message); }
}
