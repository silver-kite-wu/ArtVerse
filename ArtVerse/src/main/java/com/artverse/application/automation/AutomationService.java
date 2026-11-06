package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import com.artverse.application.NovelDraftReviewService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.gateway.AgentScopeRuntimeContextFactory;
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
    private com.artverse.application.AgentToolEffectService toolEffects;
    @org.springframework.beans.factory.annotation.Autowired
    public void setToolEffects(com.artverse.application.AgentToolEffectService effects) { this.toolEffects = effects; }
    private final AutomationStore store;
    private final AutomationScheduleCalculator schedules;
    private final AutomationInputService inputs;
    private final NovelDraftReviewService draftReviews;
    private final MangaAgentRunService agentRuns;
    @Value("${artverse.automations.max-active-plans-per-user:10}") private int maxActivePlans;

    public Map<String,Object> preview(AutomationDefinition.Schedule schedule) {
        var normalized=schedules.normalize(schedule);
        return Map.of("schedule",normalized,"occurrences",schedules.preview(normalized,Instant.now(),5));
    }

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ModelCommandView(UUID artifactId, UUID requestId, long chapterId, UUID conversationId,
                                   String status, String commandHash, String action, UUID automationId,
                                   Long expectedVersion, AutomationDefinition definition, Plan result,
                                   boolean applied, Instant expiresAt, String reviewUrl) {}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ModelCommandPage(List<ModelCommandView> items, UUID nextCursor, boolean hasMore) {}
    private static final String COMMAND_ARTIFACT = "AUTOMATION_COMMAND";

    /** Model calls prepare a reviewable command. Only an authenticated author decision applies it. */
    @Transactional(timeout = 15)
    public ModelCommandView proposeModelCommand(MangaAgentRuntimeContext context, String action, UUID target,
                                                Long expectedVersion, AutomationDefinition input) {
        return proposeModelCommand(context, action, target, expectedVersion, input, null);
    }

    @Transactional(timeout = 15)
    public ModelCommandView proposeModelCommand(MangaAgentRuntimeContext context, String action, UUID target,
            Long expectedVersion, AutomationDefinition input, com.artverse.agent.AgentToolAttempt attempt) {
        if (attempt != null) {
            attempt.requireScope(context);
            if (!("create".equals(action) ? "create_automation" : "update_automation").equals(attempt.toolName()))
                throw conflict("计划命令与原工具调用不一致");
        }
        requireCommand(action, target, expectedVersion, input);
        if (context == null || context.userId() == null) throw new com.artverse.common.BusinessException(403,"缺少交互执行身份");
        lockUser(context.userId());
        Plan current = target == null ? null : store.plan(target, context.userId(), true);
        if (current != null) { requireStoryScope(context.storyId(), context.userId(), current.definition()); }
        var run = AutomationAgentTools.requireInteractive(store, context, true);
        var attributes = store.node(Objects.toString(run.get("attributes"), "{}"));
        if (context.stepId() == null || context.stepId().isBlank()) throw bad("缺少原执行步骤");
        if (context.taskType() == AgentTaskType.STORY_CHAT_READ || context.taskType() == AgentTaskType.STORY_CHAT_WRITE) {
            var execution = attributes.path("story_chat_execution");
            if (!"local-tools-v1".equals(execution.path("profile").asText())
                    || !context.stepId().equals(execution.path("step_id").asText())
                    || !context.taskType().name().equals(execution.path("task_type").asText()))
                throw conflict("计划命令与原任务步骤或工具契约不一致");
        }
        agentRuns.requireToolExecution(context, false);
        AutomationDefinition definition = current != null && !"edit".equals(action) ? current.definition()
                : normalize(context.userId(), modelDefinition(context, input));
        requireStoryScope(context.storyId(), context.userId(), definition);
        var payload = new LinkedHashMap<String,Object>();
        payload.put("action", action); payload.put("automation_id", target); payload.put("expected_version", expectedVersion);
        payload.put("definition", definition); payload.put("actor_id", context.userId());
        payload.put("story_id", context.storyId()); payload.put("chapter_id", context.chapterId());
        payload.put("conversation_id", context.conversationId()); payload.put("request_id", context.requestId());
        payload.put("step_id", context.stepId()); payload.put("task_type", context.taskType().name());
        payload.put("profile", "local-tools-v1");
        payload.put("execution_binding", context.taskType() == AgentTaskType.MANGA_CONVERSATION
                ? attributes.path("manga_execution_bindings").path(context.stepId()) : attributes.path("story_chat_execution"));
        if (Set.of("create","edit","resume").contains(action)) payload.put("model_binding_hash",modelBinding(context.userId(),definition));
        String checksum = hash(store.canonical(payload));
        var previous = store.jdbc().queryForList("SELECT artifact_uuid FROM manga_agent_run_artifacts WHERE run_id=? AND artifact_type=? AND checksum=? ORDER BY id LIMIT 1",
                run.get("id"), COMMAND_ARTIFACT, checksum);
        if (!previous.isEmpty()) return linkedCommand(context, attempt,
                commandView(commandRow(context.userId(), (UUID)previous.getFirst().get("artifact_uuid"), false)));
        if (current != null) version(current, expectedVersion);
        Integer count = store.jdbc().queryForObject("SELECT count(*) FROM manga_agent_run_artifacts WHERE run_id=? AND artifact_type=?",Integer.class,run.get("id"),COMMAND_ARTIFACT);
        if (count != null && count >= 16) throw conflict("本轮计划提案过多，请核对已有提案后重试");
        UUID artifact = UUID.randomUUID();
        store.jdbc().update("""
                INSERT INTO manga_agent_run_artifacts(artifact_uuid,run_id,artifact_type,status,schema_version,payload,evaluation,checksum,created_at,updated_at)
                VALUES (?,?,?,'DRAFT','1',?::jsonb,'{}'::jsonb,?,clock_timestamp(),clock_timestamp())
                """,artifact,run.get("id"),COMMAND_ARTIFACT,store.json(payload),checksum);
        return linkedCommand(context, attempt, commandView(commandRow(context.userId(), artifact, false)));
    }

    private ModelCommandView linkedCommand(MangaAgentRuntimeContext context, com.artverse.agent.AgentToolAttempt attempt,
                                           ModelCommandView command) {
        if (attempt != null) {
            if (toolEffects == null) throw conflict("计划命令缺少领域回执服务");
            toolEffects.record(attempt, context, "AUTOMATION_PROPOSAL", command.artifactId(), COMMAND_ARTIFACT,
                    "AWAITING_REVIEW".equals(command.status()) ? "DRAFT" : command.status(), command.commandHash(),
                    Map.of("action", command.action()));
        }
        return command;
    }

    /** No model request, session gate or provider call is part of an author's exact command decision. */
    @Transactional(timeout = 15)
    public ModelCommandView decideModelCommand(long actor, UUID artifactId, String decision, String commandHash, Long expectedVersion) {
        if (!Set.of("confirm","discard").contains(Objects.toString(decision,"")) || commandHash == null || !commandHash.matches("[0-9a-f]{64}"))
            throw bad("需要有效的计划决定和内容摘要");
        var locator = commandRow(actor, artifactId, false);
        JsonNode original = store.node(Objects.toString(locator.get("payload")));
        UUID target = original.path("automation_id").isNull() ? null : UUID.fromString(original.path("automation_id").asText());
        lockUser(actor);
        Plan existing = target == null ? null : store.plan(target, actor, true);
        var roots = store.jdbc().queryForList("SELECT r.*,c.conversation_uuid,c.status AS conversation_status,c.legacy_import_key FROM manga_agent_runs r JOIN ai_conversations c ON c.id=r.conversation_id AND c.user_id=r.user_id AND c.story_id=r.story_id AND c.chapter_id=r.chapter_id JOIN stories s ON s.id=r.story_id AND s.user_id=r.user_id WHERE r.id=? AND r.user_id=? FOR UPDATE OF r",
                locator.get("run_id"), actor);
        if (roots.size() != 1) throw conflict("原计划提案的执行归属不完整");
        var root = roots.getFirst();
        var row = commandRow(actor, artifactId, true);
        JsonNode payload = store.node(Objects.toString(row.get("payload")));
        if (!commandHash.equals(row.get("checksum")) || !commandHash.equals(hash(store.canonical(payload)))
                || !Objects.equals(expectedVersion, nullableLong(payload.path("expected_version"))))
            throw conflict("计划提案内容或基准版本已变化，请重新审阅");
        String state = Objects.toString(row.get("status"));
        if ("COMMITTED".equals(state) || "REJECTED".equals(state)) {
            if (!("COMMITTED".equals(state) ? "confirm" : "discard").equals(decision)) throw conflict("该提案已经收到另一项决定");
            return commandView(row);
        }
        if (!"DRAFT".equals(state)) throw conflict("该计划提案当前不可处理");
        if ("discard".equals(decision)) {
            saveCommandDecision(artifactId,"REJECTED",actor,commandHash,null);
            return commandView(commandRow(actor,artifactId,false));
        }
        if (Set.of("CANCELLED","INTERRUPTED").contains(Objects.toString(root.get("status")))
                || !"ACTIVE".equals(root.get("conversation_status"))) throw conflict("原运行已停止，不能再应用计划提案");
        if (!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT created_at+interval '30 minutes'>clock_timestamp() FROM manga_agent_run_artifacts WHERE artifact_uuid=?",Boolean.class,artifactId)))
            throw conflict("计划提案已过期，请重新生成并审阅");
        if (Objects.toString(root.get("legacy_import_key"),"").startsWith("automation:")
                || Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM automation_run_execution e JOIN automation_run a ON a.id=e.run_id WHERE e.agent_run_id=? OR (a.user_id=? AND (e.request_id=? OR e.conversation_uuid=?)))",Boolean.class,
                root.get("id"),actor,root.get("request_id"),root.get("conversation_uuid"))))
            throw new com.artverse.common.BusinessException(403,"后台运行不能管理计划");
        var attributes = store.node(Objects.toString(root.get("run_attributes_json"),"{}"));
        if (attributes.has("review_result") || attributes.has("review_summary_continuation")) throw conflict("原运行已进入审核总结阶段");
        var execution = "MANGA_CONVERSATION".equals(payload.path("task_type").asText())
                ? attributes.path("manga_execution_bindings").path(payload.path("step_id").asText()) : attributes.path("story_chat_execution");
        if (!execution.isObject() || !execution.equals(payload.path("execution_binding")))
            throw conflict("原计划提案的任务步骤或工具契约已变化");
        if (payload.path("actor_id").asLong(-1) != actor || payload.path("story_id").asLong(-1) != ((Number)root.get("story_id")).longValue()
                || payload.path("chapter_id").asLong(-1) != ((Number)root.get("chapter_id")).longValue()
                || !payload.path("conversation_id").asText().equals(root.get("conversation_uuid").toString())
                || !payload.path("request_id").asText().equals(root.get("request_id").toString()) || !"local-tools-v1".equals(payload.path("profile").asText()))
            throw conflict("计划提案与原运行不一致");
        String action = payload.path("action").asText();
        AutomationDefinition frozen = store.definition(store.json(payload.path("definition")));
        if (Set.of("create","edit","resume").contains(action)
                && !modelBinding(actor,frozen).equals(payload.path("model_binding_hash").asText()))
            throw conflict("已审阅的模型配置已变化，请重新准备计划提案");
        requireStoryScope(((Number)root.get("story_id")).longValue(),actor,frozen);
        if (existing != null) { requireStoryScope(((Number)root.get("story_id")).longValue(),actor,existing.definition()); version(existing,expectedVersion); }
        Plan result;
        if ("create".equals(action) || "edit".equals(action)) {
            AutomationDefinition input = commandInput(frozen, expectedVersion);
            AutomationDefinition refreshed = normalize(actor,input);
            if (!store.canonical(refreshed).equals(store.canonical(frozen))) throw conflict("计划配置或章节输入已变化，请重新生成提案");
            result = "create".equals(action) ? create(actor,"agent-command:"+artifactId,input) : update(actor,target,input);
            if (!store.canonical(result.definition()).equals(store.canonical(frozen))) throw conflict("计划执行输入与已审阅提案不一致");
        } else result = lifecycle(actor,target,action,expectedVersion);
        saveCommandDecision(artifactId,"COMMITTED",actor,commandHash,result);
        return commandView(commandRow(actor,artifactId,false));
    }

    @Transactional(readOnly = true)
    public ModelCommandPage modelCommands(long actor, Long chapter, UUID conversation, UUID before, int requestedLimit) {
        if ((chapter == null) != (conversation == null)) throw bad("章节和会话范围必须同时提供");
        int limit = Math.min(50,Math.max(1,requestedLimit));
        String scope = " r.user_id=? AND s.user_id=? AND (?::bigint IS NULL OR (r.chapter_id=? AND c.conversation_uuid=?)) ";
        if (before != null && store.jdbc().queryForList("SELECT a.id FROM manga_agent_run_artifacts a JOIN manga_agent_runs r ON r.id=a.run_id JOIN ai_conversations c ON c.id=r.conversation_id JOIN stories s ON s.id=r.story_id WHERE a.artifact_uuid=? AND a.artifact_type=? AND "+scope,
                before,COMMAND_ARTIFACT,actor,actor,chapter,chapter,conversation).isEmpty()) throw bad("计划提案游标不属于当前范围");
        var rows = store.jdbc().queryForList("SELECT a.*,r.chapter_id,c.conversation_uuid,r.request_id FROM manga_agent_run_artifacts a JOIN manga_agent_runs r ON r.id=a.run_id JOIN ai_conversations c ON c.id=r.conversation_id AND c.user_id=r.user_id AND c.chapter_id=r.chapter_id AND c.story_id=r.story_id JOIN stories s ON s.id=r.story_id WHERE a.artifact_type=? AND "+scope
                        +" AND (?::uuid IS NULL OR (a.created_at,a.id)<(SELECT created_at,id FROM manga_agent_run_artifacts WHERE artifact_uuid=?)) ORDER BY a.created_at DESC,a.id DESC LIMIT ?",
                COMMAND_ARTIFACT,actor,actor,chapter,chapter,conversation,before,before,limit+1);
        boolean more = rows.size()>limit;
        var items = rows.subList(0,Math.min(rows.size(),limit)).stream().map(this::commandView).toList();
        return new ModelCommandPage(items,more?items.getLast().artifactId():null,more);
    }

    private void saveCommandDecision(UUID artifact,String state,long actor,String checksum,Plan result) {
        var receipt = new LinkedHashMap<String,Object>(); receipt.put("actor_id",actor);receipt.put("command_hash",checksum);
        receipt.put("decision","COMMITTED".equals(state)?"confirm":"discard"); receipt.put("decided_at",Instant.now());receipt.put("plan",result);
        if (store.jdbc().update("UPDATE manga_agent_run_artifacts SET status=?,evaluation=?::jsonb,updated_at=clock_timestamp() WHERE artifact_uuid=? AND status='DRAFT'",state,store.json(receipt),artifact)!=1)
            throw conflict("计划提案决定已变化");
        var row = commandRow(actor, artifact, false);
        var evidence = new LinkedHashMap<String,Object>(receipt);
        evidence.put("artifact_id", artifact.toString()); evidence.put("status", state);
        toolEffects.recordAuthorReview(actor, ((Number)row.get("chapter_id")).longValue(),
                (UUID)row.get("request_id"), artifact, evidence);
    }
    private Map<String,Object> commandRow(long actor,UUID artifact,boolean lock) {
        var rows=store.jdbc().queryForList("SELECT a.*,r.chapter_id,r.request_id,c.conversation_uuid FROM manga_agent_run_artifacts a JOIN manga_agent_runs r ON r.id=a.run_id JOIN ai_conversations c ON c.id=r.conversation_id AND c.user_id=r.user_id AND c.story_id=r.story_id AND c.chapter_id=r.chapter_id JOIN stories s ON s.id=r.story_id AND s.user_id=r.user_id WHERE a.artifact_uuid=? AND a.artifact_type=? AND r.user_id=?"+(lock?" FOR UPDATE OF a":""),artifact,COMMAND_ARTIFACT,actor);
        if(rows.size()!=1)throw new com.artverse.common.BusinessException(404,"计划提案不存在"); return rows.getFirst();
    }
    private ModelCommandView commandView(Map<String,Object> row) {
        var payload=store.node(Objects.toString(row.get("payload"))); var receipt=store.node(Objects.toString(row.get("evaluation"),"{}"));
        Plan result=receipt.path("plan").isObject()?store.commandPlan(receipt.path("plan")):null;
        return new ModelCommandView((UUID)row.get("artifact_uuid"),(UUID)row.get("request_id"),((Number)row.get("chapter_id")).longValue(),
                (UUID)row.get("conversation_uuid"),"DRAFT".equals(row.get("status"))?"AWAITING_REVIEW":Objects.toString(row.get("status")),Objects.toString(row.get("checksum")),payload.path("action").asText(),
                payload.path("automation_id").isNull()?null:UUID.fromString(payload.path("automation_id").asText()),nullableLong(payload.path("expected_version")),
                store.definition(store.json(payload.path("definition"))),result,"COMMITTED".equals(row.get("status")),
                ((java.sql.Timestamp)row.get("created_at")).toInstant().plusSeconds(1800),"#/automations");
    }
    private void requireCommand(String action,UUID target,Long expected,AutomationDefinition definition) {
        if (!Set.of("create","edit","pause","resume","archive").contains(Objects.toString(action,"")))throw bad("未知计划操作");
        if ("create".equals(action) ? target!=null||expected!=null : target==null||expected==null||expected<0)throw bad("计划目标或 expected_version 无效");
        if (Set.of("create","edit").contains(action) ? definition==null : definition!=null)throw bad("只有新建或编辑需要完整计划定义");
        if ("edit".equals(action) && definition.expectedVersion()!=null && !expected.equals(definition.expectedVersion()))throw bad("计划版本字段不一致");
    }
    private AutomationDefinition modelDefinition(MangaAgentRuntimeContext context,AutomationDefinition definition) {
        if(definition==null||definition.payload()==null||"IMAGE_GEN".equals(definition.executorKey()))throw bad("对话计划只支持当前作品的 Agent 与章节生图任务");
        return new AutomationDefinition(definition.name(),definition.taskType(),definition.executorKey(),definition.instruction(),definition.schedule(),definition.payload(),definition.limits(),
                definition.misfirePolicy(),definition.overlapPolicy(),definition.maxLatenessSeconds(),null,"CONVERSATION",context.conversationId());
    }
    private AutomationDefinition commandInput(AutomationDefinition definition,Long version) {
        var payload=definition.payload().deepCopy(); if(payload.isObject())((com.fasterxml.jackson.databind.node.ObjectNode)payload).remove("chapterSnapshots");
        return new AutomationDefinition(definition.name(),definition.taskType(),definition.executorKey(),definition.instruction(),definition.schedule(),payload,definition.limits(),
                definition.misfirePolicy(),definition.overlapPolicy(),definition.maxLatenessSeconds(),version,definition.origin(),definition.sourceConversationId());
    }
    private void requireStoryScope(Long story,long actor,AutomationDefinition definition) {
        if(story==null||definition==null||definition.payload()==null||"IMAGE_GEN".equals(definition.executorKey()))throw bad("计划缺少当前作品范围");
        if(!definition.payload().hasNonNull("storyId")||definition.payload().path("storyId").asLong(-1)!=story)throw new com.artverse.common.BusinessException(403,"计划不属于当前作品");
        for(Long chapter:inputs.chapterIds(definition.payload(),definition.executorKey()))
            if(store.jdbc().queryForList("SELECT c.id FROM chapters c JOIN stories s ON s.id=c.story_id WHERE c.id=? AND s.id=? AND s.user_id=?",chapter,story,actor).isEmpty())
                throw new com.artverse.common.BusinessException(403,"计划章节不属于当前作品");
    }
    private static Long nullableLong(JsonNode node) { return node==null||node.isNull()||node.isMissingNode()?null:node.longValue(); }
    private String modelBinding(long actor,AutomationDefinition definition) {
        var config=inputs.config(actor,definition.payload(),"IMAGE".equals(definition.taskType()));
        if(config==null||config.configId()==null)throw bad("计划提案需要有效的已保存模型配置");
        return hash(store.canonical(Map.of("config_id",config.configId(),"slot",config.slot(),"provider",config.provider(),
                "endpoint",config.baseUrl(),"model",config.model())));
    }

    /** Validate the exact model-visible plan without creating a proposal, plan, run or quota reservation. */
    @Transactional(readOnly = true)
    public AutomationDefinition previewModelDefinition(MangaAgentRuntimeContext context, AutomationDefinition input) {
        AutomationAgentTools.requireInteractive(store,context,false);
        agentRuns.requireToolExecution(context,false);
        AutomationDefinition normalized=normalize(context.userId(),modelDefinition(context,input));
        requireStoryScope(context.storyId(),context.userId(),normalized);
        return normalized;
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
        if(store.jdbc().update("UPDATE automation SET name=?,task_type=?,executor_key=?,definition=?::jsonb,timezone_id=?,next_run_at=?,version=version+1,updated_at=now() WHERE id=? AND user_id=? AND version=?",
                definition.name(),definition.taskType(),definition.executorKey(),store.json(definition),definition.schedule().timezone(),timestamp(next),id,userId,input.expectedVersion())!=1)throw conflict("任务版本已变化");
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
        if(store.jdbc().update("UPDATE automation SET status=?,next_run_at=?,version=version+1,updated_at=now() WHERE id=? AND user_id=? AND version=?",target,timestamp(next),id,userId,expectedVersion)!=1)throw conflict("任务版本已变化");
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
        current=store.lockRun(id,userId);
        JsonNode retryPayload=store.node("{\"action\":\"retry\"}");
        if(previousAction(id,key,"RETRY",retryPayload))return current;
        if(!Set.of("FAILED","PARTIAL_FAILED").contains(current.state()))throw conflict("只有明确失败的运行可以重试");
        if("RESULT_UNCERTAIN".equals(current.errorCode()))throw conflict("供应商结果不确定，需要人工核对");
        if(store.blocking(current.automationId()))throw conflict("该计划已有另一条运行");
        JsonNode retryResponse=retryPayload;
        Map<String,Object> binding=new LinkedHashMap<>();
        binding.put("response_kind","RETRY");
        if("STORY_CHAT".equals(current.definition().executorKey())) {
            var child=storyInput(current);
            UUID artifactId=reviewedArtifact(current,child);
            JsonNode receipt=requireStoryReceipt(current,child,artifactId);
            binding.putAll(reviewBinding(current,child,artifactId,receipt));
            retryResponse=store.node(store.json(Map.of("decision","reviewed","artifactId",artifactId)));
        }
        // A failed summary retains its original response; retry never overwrites that slot.
        String responseKey=key;
        String responseHash;
        if(current.response()!=null) {
            if(!retryResponse.equals(current.response()))throw conflict("原响应尚未消费，请先处理原待办");
            var original=store.action(id,current.responseKey()).orElseThrow(()->conflict("原响应缺少动作关联"));
            if(original.target()==null||!Objects.equals(binding.get("receipt_hash"),original.target().path("receipt_hash").asText()))
                throw conflict("原响应与本次重试目标不一致");
            binding.put("retained_response_key",current.responseKey());
            binding.put("retained_response_hash",current.responseHash());
            responseKey=current.responseKey();
            responseHash=current.responseHash();
            registerAction(current,key,"RETRY",retryPayload,binding,retryResponse);
        } else responseHash=registerAction(current,key,"RETRY",retryPayload,binding,retryResponse);
        store.jdbc().update("UPDATE automation_run SET state='QUEUED',completed_at=NULL,error_code=NULL,error_message=NULL,waiting_reason=NULL,next_attempt_at=now(),lease_owner=NULL,lease_until=NULL,response=?::jsonb,response_key=?,response_hash=? WHERE id=?",
                store.json(retryResponse),responseKey,responseHash,id);
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
        current=store.lockRun(id,userId);
        if(previousAction(id,key,"RESPOND",response))return current;
        if(!"WAITING_USER".equals(current.state()))throw conflict("本次运行当前没有等待用户处理");
        if(current.response()!=null)throw conflict("原响应仍在处理中，请刷新后处理下一待办");
        Map<String,Object> binding=new LinkedHashMap<>();
        binding.put("response_kind","CLOSE");
        if("RESULT_UNCERTAIN".equals(current.waitingReason())&&!"close".equals(response.path("action").asText()))
            throw bad("结果不确定的请求只允许核对后关闭；重新生成需要新建明确请求");
        if("close".equals(response.path("action").asText())) {
            if(response.size()!=1)throw bad("关闭响应仅接受 action 字段");
            String digest=registerAction(current,key,"RESPOND",response,binding,response);
            store.jdbc().update("UPDATE automation_run SET state='CANCEL_REQUESTED',next_attempt_at=now(),response=?::jsonb,response_key=?,response_hash=? WHERE id=?",store.json(response),key,digest,id);
            store.event(id,"CANCEL_REQUESTED",Map.of("summary","用户关闭待处理运行，等待业务收尾"));
            completeOnce(store.run(id,userId));
        } else {
            if(!Set.of("AGENT_INPUT","INPUT_CHANGED","CONFIGURATION").contains(Objects.toString(current.waitingReason(),"")))throw bad("该待处理事项不能恢复执行");
            if("AGENT_INPUT".equals(current.waitingReason())&&("STORY_CHAT".equals(current.definition().executorKey())
                    || ("MANGA_AGENT".equals(current.definition().executorKey())&&"answer".equals(response.path("decision").asText())))) {
                binding.putAll(validateStoryResponse(current,response));
            }
            if(Set.of("INPUT_CHANGED","CONFIGURATION").contains(current.waitingReason())) {
                if(!"refresh-input".equals(response.path("action").asText()))throw bad("需要明确确认刷新输入");
                var refreshed=inputs.refreshedDefinition(userId,current.definition());
                store.jdbc().update("UPDATE automation_run SET definition_snapshot=?::jsonb,input_snapshot=NULL,snapshot_hash=NULL WHERE id=?",store.json(refreshed),id);
                var snapshot=inputs.prepare(store.run(id));
                store.jdbc().update("UPDATE automation_run SET input_snapshot=?::jsonb,snapshot_hash=? WHERE id=?",store.json(snapshot),hash(store.json(snapshot)),id);
                inputs.refreshChildren(store.run(id),store.executions(id));
                store.event(id,"INPUT_REFRESHED",Map.of("summary","用户确认后重新冻结输入，已成功图片保留"));
                binding.put("response_kind","REFRESH_INPUT");
            }
            String digest=registerAction(current,key,"RESPOND",response,binding,response);
            store.jdbc().update("UPDATE automation_run SET state='QUEUED',next_attempt_at=now(),response=?::jsonb,response_key=?,response_hash=?,lease_owner=NULL,lease_until=NULL WHERE id=?",store.json(response),key,digest,id);
            store.event(id,"USER_RESPONDED",Map.of("summary","已收到用户响应，等待重新校验"));
        }
        return store.run(id,userId);
    }

    private Map<String,Object> validateStoryResponse(Run run,JsonNode response) {
        var child=storyInput(run);
        String decision=response.path("decision").asText();
        Set<String> fields=new HashSet<>();response.fieldNames().forEachRemaining(fields::add);
        if("reviewed".equals(decision)) {
            if(!"STORY_CHAT".equals(run.definition().executorKey()))throw bad("漫画普通提问不能提交正文审核回执");
            if(!fields.equals(Set.of("decision","artifactId")))throw bad("审阅响应仅接受决定及产物引用");
            UUID artifactId=uuid(response.path("artifactId").asText());
            return reviewBinding(run,child,artifactId,requireStoryReceipt(run,child,artifactId));
        }
        boolean selected=fields.equals(Set.of("decision","selectedOptionId","toolCallId","pendingVersion"));
        if(!"answer".equals(decision)||(!selected&&!fields.equals(Set.of("decision","answer","toolCallId","pendingVersion"))))
            throw bad("请在正文审阅页保存最终稿；普通回复仅接受补充说明");
        String answer=response.path("answer").asText();
        var pending=store.node(Objects.toString(child.get("pending"),"{}"));
        var attributes=store.node(String.valueOf(child.get("attributes")));
        if(!"WAITING_USER".equals(child.get("status"))||!"USER_QUESTION".equals(pending.path("purpose").asText())
                ||attributes.has("review_result"))throw conflict("该任务没有可恢复的普通提问，请通过审阅页处理草稿");
        if(!response.path("toolCallId").isTextual()||response.path("toolCallId").asText().isBlank()
                ||!response.path("toolCallId").asText().equals(pending.path("toolCallId").asText())
                ||!response.path("pendingVersion").isIntegralNumber()
                ||response.path("pendingVersion").asLong()!=pending.path("pendingVersion").asLong(-1))
            throw conflict("原提问版本已变化，请刷新后回复");
        if(selected) {
            String option=response.path("selectedOptionId").asText();
            boolean exists=false;
            for(var candidate:pending.path("options")) if(candidate.path("id").asText().equals(option))exists=true;
            if(!response.path("selectedOptionId").isTextual()||option.isBlank()||!exists)throw bad("请选择原问题提供的选项");
        } else if(!pending.path("allowFreeText").asBoolean()||!response.path("answer").isTextual()||answer.isBlank()||answer.length()>4000)
            throw bad("请按原问题允许的方式选择或填写补充说明");
        var accepted=agentRuns.acceptBackgroundAnswer(run.userId(),run.definition().payload().path("chapterId").asLong(),
                (UUID)child.get("request_id"),(UUID)child.get("conversation_uuid"),response.path("toolCallId").asText(),
                response.path("pendingVersion").asLong(),response.path("answer").asText(null),response.path("selectedOptionId").asText(null));
        var question=store.node(accepted.run().getRunAttributesJson()).path("external_questions").path(accepted.callId());
        String step=question.path("step_id").asText();
        String profile=question.path("profile").asText();
        AgentTaskType task;
        try { task=AgentTaskType.valueOf(question.path("task_type").asText()); }
        catch(IllegalArgumentException error) { throw conflict("原提问缺少执行类型关联"); }
        if(step.isBlank()||!"local-tools-v1".equals(profile))throw conflict("原提问契约已失效");
        Map<String,Object> binding=childBinding(child);
        binding.put("response_kind","ANSWER");binding.put("call_id",accepted.callId());
        binding.put("pending_version",accepted.pendingVersion());binding.put("accepted_action_id",accepted.actionId().toString());
        binding.put("answer_hash",accepted.answerHash());binding.put("step_id",step);binding.put("profile",profile);
        binding.put("session_id",AgentScopeRuntimeContextFactory.createSessionId(String.valueOf(run.userId()),
                ((Number)child.get("story_id")).longValue(),((Number)child.get("chapter_id")).longValue(),
                (UUID)child.get("conversation_uuid"),task));
        return binding;
    }

    private Map<String,Object> storyInput(Run run) {
        var children=store.jdbc().queryForList("""
                SELECT r.id,r.status,r.user_input_request_json::text AS pending,r.run_attributes_json::text AS attributes,
                  r.request_id,r.story_id,r.chapter_id,c.conversation_uuid
                FROM automation_run_execution e JOIN manga_agent_runs r ON r.id=e.agent_run_id
                JOIN ai_conversations c ON c.id=r.conversation_id
                WHERE e.run_id=? AND e.execution_kind=? AND r.user_id=? AND c.user_id=r.user_id
                  AND r.chapter_id=? AND c.chapter_id=r.chapter_id AND c.story_id=r.story_id
                  AND e.request_id=r.request_id AND e.conversation_uuid=c.conversation_uuid AND r.run_type=?
                FOR UPDATE OF r
                """,run.id(),run.definition().executorKey(),run.userId(),run.definition().payload().path("chapterId").asLong(),
                "MANGA_AGENT".equals(run.definition().executorKey())?"MANGA_AGENT":"STORY_CHAT");
        if(children.size()!=1)throw conflict("后台任务与原创作运行的关联不可用");
        return children.getFirst();
    }

    private JsonNode requireStoryReceipt(Run run,Map<String,Object> child,UUID artifactId) {
        var receipt=store.node(store.json(draftReviews.reconcile(run.userId(),
                run.definition().payload().path("chapterId").asLong(),(UUID)child.get("conversation_uuid"),
                (UUID)child.get("request_id"),artifactId,true)));
        String status=receipt.path("status").asText();
        if(!receipt.isObject()||!artifactId.toString().equals(receipt.path("artifact_id").asText())
                ||!Set.of("COMMITTED","REJECTED").contains(status))throw conflict("请先完成这份草稿的人工审阅");
        Integer count=store.jdbc().queryForObject("""
                SELECT count(*) FROM manga_agent_run_artifacts a JOIN manga_agent_runs r ON r.id=a.run_id
                WHERE a.artifact_uuid=? AND a.run_id=? AND r.user_id=? AND a.artifact_type='NOVEL_CONTENT_DRAFT'
                  AND a.status=? AND COALESCE(a.payload->>'reviewed_content_hash',a.payload->>'content_hash')=?
                """,Integer.class,artifactId,child.get("id"),run.userId(),status,receipt.path("content_hash").asText());
        if(count==null||count!=1)throw conflict("审阅回执与实际产物不一致");
        return receipt;
    }

    private Map<String,Object> childBinding(Map<String,Object> child) {
        Map<String,Object> binding=new LinkedHashMap<>();
        binding.put("agent_run_id",child.get("id"));binding.put("request_id",child.get("request_id").toString());
        binding.put("conversation_id",child.get("conversation_uuid").toString());
        binding.put("story_id",child.get("story_id"));binding.put("chapter_id",child.get("chapter_id"));
        return binding;
    }

    private Map<String,Object> reviewBinding(Run run,Map<String,Object> child,UUID artifact,JsonNode receipt) {
        Map<String,Object> binding=childBinding(child);
        binding.put("response_kind","REVIEWED");binding.put("artifact_id",artifact.toString());
        binding.put("receipt_hash",hash(store.canonical(receipt)));binding.put("profile","local-tools-v1");
        return binding;
    }

    private UUID reviewedArtifact(Run run,Map<String,Object> child) {
        var receipt=store.node(String.valueOf(child.get("attributes"))).path("review_result");
        if(receipt.isObject())return uuid(receipt.path("artifact_id").asText());
        var reviewed=store.jdbc().queryForList("""
                SELECT artifact_uuid FROM manga_agent_run_artifacts
                WHERE run_id=? AND artifact_type='NOVEL_CONTENT_DRAFT' AND status IN ('COMMITTED','REJECTED')
                ORDER BY id LIMIT 2
                """,child.get("id"));
        if(reviewed.size()!=1)throw conflict("无法唯一确定已审核产物，请选择具体审阅结果后继续");
        return (UUID)reviewed.getFirst().get("artifact_uuid");
    }

    private static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch(IllegalArgumentException error) { throw bad("产物引用无效"); }
    }

    private boolean previousAction(UUID id,String key,String kind,JsonNode payload) {
        var action=store.action(id,key);
        if(action.isEmpty())return false;
        if(!kind.equals(action.get().kind())||!hash(store.canonical(payload)).equals(action.get().payloadHash()))
            throw conflict("同一幂等键不能用于不同响应，旧动作契约请刷新后核对");
        return true;
    }

    private String registerAction(Run run,String key,String kind,JsonNode payload,Map<String,Object> target,JsonNode response) {
        Map<String,Object> binding=new LinkedHashMap<>(target);
        binding.put("schema_version",1);binding.put("actor",run.userId());binding.put("automation_run_id",run.id().toString());
        binding.put("automation_version",run.automationVersion());binding.put("definition_hash",hash(store.canonical(run.definition())));
        binding.put("input_snapshot_hash",hash(store.canonical(run.inputSnapshot())));binding.put("stage",run.state());
        binding.put("waiting_reason",Objects.toString(run.waitingReason(),""));binding.put("attempt_count",run.attemptCount());
        binding.put("response_hash",hash(store.canonical(response)));
        String payloadHash=hash(store.canonical(payload));
        String digest=hash(store.canonical(Map.of("kind",kind,"payload_hash",payloadHash,"target",binding)));
        store.jdbc().update("""
                INSERT INTO automation_run_action(run_id,idempotency_key,request_hash,action_kind,payload_hash,target_binding)
                VALUES (?,?,?,?,?,?::jsonb)
                """,run.id(),key,digest,kind,payloadHash,store.json(binding));
        return digest;
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
                var children=store.jdbc().queryForList("SELECT user_input_request_json, run_attributes_json::text AS attributes FROM manga_agent_runs WHERE id=? AND user_id=?",execution.agentRunId(),userId);
                if(!children.isEmpty()) {
                    if(children.getFirst().get("user_input_request_json") instanceof String request)detail.put("userInputRequest",store.node(request));
                    var receipt=store.node(String.valueOf(children.getFirst().get("attributes"))).path("review_result");
                    if(receipt.isObject()&&Set.of("COMMITTED","REJECTED").contains(receipt.path("status").asText()))detail.put("reviewReceipt",receipt);
                }
                artifacts.addAll(store.jdbc().query("""
                        SELECT a.*,r.chapter_id,r.request_id,c.conversation_uuid,ch.version AS current_version
                        FROM manga_agent_run_artifacts a JOIN manga_agent_runs r ON r.id=a.run_id
                        JOIN ai_conversations c ON c.id=r.conversation_id JOIN chapters ch ON ch.id=r.chapter_id
                        WHERE a.run_id=? AND r.user_id=? ORDER BY a.id
                        """,(r,n)-> {
                    Map<String,Object> artifact=new LinkedHashMap<>();artifact.put("id",r.getString("artifact_uuid"));artifact.put("kind",r.getString("artifact_type"));
                    artifact.put("title",Set.of("NOVEL_CONTENT","NOVEL_CONTENT_DRAFT").contains(r.getString("artifact_type"))?"小说草稿":"Agent 产物");artifact.put("state",r.getString("status"));
                    JsonNode payload=store.node(r.getString("payload"));artifact.put("content",payload.has("content")?payload.path("content").asText():payload.toPrettyString());
                    if("NOVEL_CONTENT_DRAFT".equals(r.getString("artifact_type"))) {
                        artifact.put("review",Map.of("chapterId",r.getLong("chapter_id"),"conversationId",r.getString("conversation_uuid"),
                                "requestId",r.getString("request_id"),"baseVersion",payload.path("base_version").asLong(),
                                "currentVersion",r.getLong("current_version"),"contentHash",payload.path("content_hash").asText(),
                                "originalContent",payload.path("base_content").asText("")));
                    }
                    return artifact;
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
