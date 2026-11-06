package com.artverse.application.automation;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Tool arguments never determine identity, and background runs cannot multiply schedules. */
@Service
@RequiredArgsConstructor
public class AutomationAgentTools {
    private final AutomationService service;
    private final AutomationStore store;
    private final AutomationInputService inputs;
    private final ObjectMapper mapper;

    public void requireInteractive(MangaAgentRuntimeContext context) {
        requireInteractive(store, context, false);
    }
    static Map<String,Object> requireInteractive(AutomationStore store, MangaAgentRuntimeContext context, boolean lock) {
        if (context == null || context.userId() == null || context.userId() <= 0 || context.storyId() == null
                || context.chapterId() == null || context.requestId() == null || context.conversationId() == null
                || context.fencingToken() == null || context.fencingToken() <= 0
                || context.taskType() == null) throw new BusinessException(403, "需要经过认证的交互式会话");
        String runType = switch (context.taskType()) {
            case MANGA_CONVERSATION -> "MANGA_AGENT";
            case STORY_CHAT_READ, STORY_CHAT_WRITE -> "STORY_CHAT";
            default -> throw new BusinessException(403, "当前 Agent 阶段不能查询或管理计划");
        };
        // A UUID value never proves source. Resolve the owned business graph and explicit attachments.
        var rows = store.jdbc().queryForList("""
                SELECT r.id,r.owner_instance_id,r.run_attributes_json::text AS attributes,
                    (coalesce(c.legacy_import_key,'') LIKE 'automation:%' OR EXISTS(
                        SELECT 1 FROM automation_run_execution e
                        LEFT JOIN automation_run a ON a.id=e.run_id
                        WHERE e.agent_run_id=r.id
                            OR (a.user_id=r.user_id AND (e.request_id=r.request_id OR e.conversation_uuid=c.conversation_uuid))
                    )) AS background,
                    (r.status='RUNNING' AND r.fencing_token=? AND r.owner_instance_id IS NOT NULL
                        AND r.lease_until>clock_timestamp() AND c.status='ACTIVE') AS active
                FROM manga_agent_runs r
                JOIN ai_conversations c ON c.id=r.conversation_id AND c.user_id=r.user_id
                    AND c.story_id=r.story_id AND c.chapter_id=r.chapter_id AND c.conversation_type=r.run_type
                JOIN chapters ch ON ch.id=r.chapter_id AND ch.story_id=r.story_id
                JOIN stories s ON s.id=ch.story_id AND s.user_id=r.user_id
                WHERE r.user_id=? AND r.request_id=? AND r.story_id=? AND r.chapter_id=?
                    AND c.conversation_uuid=? AND r.run_type=?
                """ + (lock ? " FOR UPDATE OF r" : ""), context.fencingToken(), context.userId(), context.requestId(), context.storyId(),
                context.chapterId(), context.conversationId(), runType);
        if (rows.size() != 1) throw new BusinessException(403, "交互运行的用户、作品、章节或会话归属不完整");
        if (Boolean.TRUE.equals(rows.getFirst().get("background")))
            throw new BusinessException(403, "后台定时任务不能创建或管理其他计划");
        if (!Boolean.TRUE.equals(rows.getFirst().get("active")))
            throw new BusinessException(409, "RUN_NOT_ACTIVE: 交互运行已停止或执行权已变化");
        var attributes = store.node(Objects.toString(rows.getFirst().get("attributes"), "{}"));
        if (attributes != null && (attributes.has("review_result") || attributes.has("review_summary_continuation")))
            throw new BusinessException(403, "审核总结不能查询或管理计划");
        return rows.getFirst();
    }
    public Object create(MangaAgentRuntimeContext context,Map<String,Object> args) {
        return create(context, args, null);
    }
    public Object create(MangaAgentRuntimeContext context,Map<String,Object> args, com.artverse.agent.AgentToolAttempt attempt) {
        requireInteractive(context);
        var definition=mapper.convertValue(args.get("definition"),AutomationDefinition.class);
        return attempt == null ? service.proposeModelCommand(context, "create", null, null, definition)
                : service.proposeModelCommand(context, "create", null, null, definition, attempt);
    }
    public Object list(MangaAgentRuntimeContext context) {
        return list(context, null, null, null, null);
    }
    public AutomationStore.PlanPage list(MangaAgentRuntimeContext context, String status, String taskType,
                                         String cursor, Integer limit) {
        requireInteractive(context);
        if (context.userId() == null || context.storyId() == null)
            throw new BusinessException(403, "任务查询缺少当前用户或作品");
        return store.planPageForStory(context.userId(), context.storyId(), status, taskType, cursor, limit);
    }
    public Object update(MangaAgentRuntimeContext context,Map<String,Object> args) {
        return update(context, args, null);
    }
    public Object update(MangaAgentRuntimeContext context,Map<String,Object> args, com.artverse.agent.AgentToolAttempt attempt) {
        requireInteractive(context);
        UUID id;
        try { id=UUID.fromString(String.valueOf(args.get("automationId"))); }
        catch (RuntimeException invalid) { throw new BusinessException(400,"需要有效的计划标识"); }
        String action=String.valueOf(args.get("action"));
        Long version=args.get("expectedVersion") instanceof Number number?number.longValue():null;
        if (version == null || version < 0) throw new BusinessException(400,"所有计划修改均需 expected_version");
        var definition="edit".equals(action)?mapper.convertValue(args.get("definition"),AutomationDefinition.class):null;
        return attempt == null ? service.proposeModelCommand(context,action,id,version,definition)
                : service.proposeModelCommand(context,action,id,version,definition,attempt);
    }
}
