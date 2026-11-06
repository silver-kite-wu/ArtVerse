package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/** SQL is authoritative. Reservations commit before dispatch and remain charged on uncertainty. */
@Service
public class AgentBudgetService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final JdbcTemplate jdbc;
    private final ArtVerseProperties properties;
    private final TransactionTemplate tx;

    public AgentBudgetService(JdbcTemplate jdbc, PlatformTransactionManager transactions, ArtVerseProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(15);
        ModelLimits.from(properties);
        for (var type : AgentTaskType.values()) limits(type, BusinessSkillSelection.empty());
        var a = properties.getAgent();
        if (a.getDirectorRootMaxModelCalls() <= 0 || a.getDirectorRootMaxToolCalls() < 0 || a.getTextMaxToolCalls() < 0
                || a.getMaxInputBytes() <= 0 || a.getMaxInputTokens() <= 0 || a.getMaxOutputBytes() <= 0 || a.getMaxOutputTokens() <= 0)
            throw new IllegalArgumentException("Agent budget configuration must be positive and finite");
    }

    public boolean reserveModelCall(AgentRunRequest request, UUID attempt, String payloadHash) {
        return reserveModelCall(request, attempt, payloadHash, ModelLimits.from(properties));
    }

    public boolean reserveModelCall(AgentRunRequest request, UUID attempt, String payloadHash, ModelLimits envelope) {
        return reserve(request, "MODEL_CALL", List.of(new Attempt(attempt, payloadHash, null)), Objects.requireNonNull(envelope));
    }

    /** A prior reservation must be reconciled; it never grants another execution. */
    public boolean reserveToolCalls(AgentRunRequest request, List<ToolUseBlock> calls) {
        if (calls.isEmpty()) return true;
        String slot = scopeKey(request);
        var seen = new HashSet<String>();
        var attempts = new ArrayList<Attempt>();
        for (var call : calls) {
            if (call.getId() == null || call.getId().isBlank() || !seen.add(call.getId()))
                throw new BusinessException(409, "工具批次缺少唯一调用标识");
            UUID id = UUID.nameUUIDFromBytes((slot + ":" + call.getId()).getBytes(StandardCharsets.UTF_8));
            attempts.add(new Attempt(id, hash(Map.of("name", call.getName(), "input", call.getInput())), call.getName()));
        }
        return reserve(request, "TOOL_CALL", attempts, null);
    }

    /** A native expert may be dispatched only once within its persisted parent/step binding. */
    public boolean reserveReviewExpert(AgentRunRequest parent, AgentReviewExpert identity) {
        var variables = new LinkedHashMap<>(parent.variables());
        variables.put("review_expert", Objects.requireNonNull(identity));
        var child = new AgentRunRequest(parent.userId(), parent.storyId(), parent.chapterId(), parent.taskType(),
                parent.messages(), Map.copyOf(variables), parent.modelSpec(), parent.llmApiKey(), parent.requestId(),
                parent.conversationId(), BusinessSkillSelection.empty());
        UUID attempt = UUID.nameUUIDFromBytes((scopeKey(child) + ":spawn").getBytes(StandardCharsets.UTF_8));
        return reserve(child, "SUBAGENT", List.of(new Attempt(attempt, hash(identity), identity.expertId())), null);
    }

    private boolean reserve(AgentRunRequest request, String kind, List<Attempt> attempts, ModelLimits envelope) {
        long actor = actor(request);
        UUID root = requireRoot(request);
        attempts.forEach(a -> {
            if (a.id == null || a.hash == null || a.hash.length() != 64)
                throw new BusinessException(400, "预算尝试标识或摘要无效");
        });
        return Boolean.TRUE.equals(tx.execute(status -> {
            // Discover, then lock parent -> business root -> budget root. Never acquire a parent late.
            UUID parent = parent(request, actor);
            Long parentModelLimit = lockParent(parent, actor, request);
            Map<String, Object> run = lockRun(request, actor);
            if (!Objects.equals(parent, parent(request, actor)))
                throw new BusinessException(409, "后台运行关联已变化，请核对原执行");
            ObjectNode binding = binding(request, run, parent);
            Limits scope = scopeLimits(request);
            Limits total = rootLimits(request, run, scope);
            ObjectNode initial = JSON.createObjectNode();
            initial.set("binding", binding);
            initial.set("generation_limits", JSON.valueToTree(ModelLimits.from(properties)));
            initial.put("subagent_limit", Math.min(4, properties.getAgent().getMaxSubagents()));
            initial.put("model_limit", total.models).put("tool_limit", total.tools).putObject("scopes");
            jdbc.update("""
                    INSERT INTO agent_usage_ledger(user_id,request_id,root_request_id,usage_kind,amount,metadata,reservation_status)
                    VALUES (?,?,?,'BUDGET_ROOT',0,?::jsonb,'ROOT') ON CONFLICT DO NOTHING
                    """, actor, root, root, json(initial));
            var rows = jdbc.queryForList("""
                    SELECT id,metadata FROM agent_usage_ledger
                    WHERE user_id=? AND root_request_id=? AND usage_kind='BUDGET_ROOT' FOR UPDATE
                    """, actor, root);
            if (rows.size() != 1) throw new BusinessException(409, "预算根不唯一");
            ObjectNode metadata = object(rows.getFirst().get("metadata"));
            ModelLimits generation = frozenModelLimits(metadata.get("generation_limits"));
            generation = generation.shrink(ModelLimits.from(properties));
            if (envelope != null) envelope.requireWithin(generation);
            metadata.set("generation_limits", JSON.valueToTree(generation));
            JsonNode original = metadata.get("binding");
            if (!binding.equals(original)) {
                // A router may precede durable workflow creation; it has no write tools.
                ObjectNode routed = (ObjectNode) original.deepCopy();
                routed.set("execution", binding.get("execution"));
                if (!"ROUTING".equals(original.path("execution").path("source").asText()) || !routed.equals(binding))
                    throw new BusinessException(409, "预算根的身份或来源与原请求不同");
                metadata.set("binding", binding);
            }
            long modelCap = Math.min(metadata.path("model_limit").asLong(), total.models);
            long toolCap = Math.min(metadata.path("tool_limit").asLong(), total.tools);
            metadata.put("model_limit", modelCap).put("tool_limit", toolCap);
            ObjectNode scopes = metadata.withObject("scopes");
            String key = scopeKey(request);
            ObjectNode frozen = JSON.createObjectNode();
            frozen.set("model", modelIdentity(request));
            frozen.set("generation_limits", JSON.valueToTree(generation));
            frozen.put("model_limit", scope.models).put("tool_limit", scope.tools);
            if (scopes.has(key) && !scopes.get(key).path("model").equals(frozen.get("model")))
                throw new BusinessException(409, "原步骤的模型配置或能力声明已变化");
            if (scopes.has(key)) {
                ModelLimits previous = frozenModelLimits(scopes.get(key).get("generation_limits"));
                ModelLimits scoped = generation.shrink(previous);
                if (envelope != null) envelope.requireWithin(scoped);
                frozen.set("generation_limits", JSON.valueToTree(scoped));
                frozen.put("model_limit", Math.min(scopes.get(key).path("model_limit").asLong(), scope.models));
                frozen.put("tool_limit", Math.min(scopes.get(key).path("tool_limit").asLong(), scope.tools));
            }
            scopes.set(key, frozen);
            // Count router attempts made before attachment against the actual parent as well.
            if (parent != null) jdbc.update("""
                    UPDATE agent_usage_ledger SET automation_run_id=?
                    WHERE user_id=? AND root_request_id=? AND automation_run_id IS NULL
                    """, parent, actor, root);
            int existing = 0;
            for (var a : attempts) {
                var prior = jdbc.queryForList("""
                        SELECT attempt_hash FROM agent_usage_ledger
                        WHERE user_id=? AND root_request_id=? AND usage_kind=? AND attempt_id=?
                        """, actor, root, kind, a.id);
                if (!prior.isEmpty()) {
                    if (!a.hash.equals(prior.getFirst().get("attempt_hash")))
                        throw new BusinessException(409, "同一调用标识的输入已变化");
                    existing++;
                }
            }
            if (existing > 0) {
                if (existing != attempts.size()) throw new BusinessException(409, "批次包含已预占与新调用，请核对原结果");
                return false;
            }
            long used = usage(actor, root, kind, null);
            long part = usage(actor, root, kind, key);
            long cap = "MODEL_CALL".equals(kind) ? modelCap : "SUBAGENT".equals(kind)
                    ? Math.min(metadata.path("subagent_limit").asLong(0), Math.min(4, properties.getAgent().getMaxSubagents()))
                    : toolCap;
            long partCap = "SUBAGENT".equals(kind) ? 1
                    : frozen.path("MODEL_CALL".equals(kind) ? "model_limit" : "tool_limit").asLong();
            if ("SUBAGENT".equals(kind)) metadata.put("subagent_limit", cap);
            requireRemaining(kind, used, attempts.size(), cap);
            requireRemaining(kind, part, attempts.size(), partCap);
            long modelTimeBudget = 0;
            if ("MODEL_CALL".equals(kind)) {
                long activityLimit = Math.multiplyExact((long) generation.modelActivityBudgetSeconds(), 1000);
                long activityRemaining = activityLimit - reservedModelActivity(actor, root);
                requireRemaining("MODEL_ACTIVITY_MILLIS", 0, 1, activityRemaining);
                modelTimeBudget = Math.min(activityRemaining, Math.multiplyExact((long) generation.modelTimeoutSeconds(), 1000));
            }
            if (parent != null && "MODEL_CALL".equals(kind)) {
                Long originalLimit = jdbc.queryForObject("""
                        SELECT min((metadata->>'parent_model_limit')::bigint) FROM agent_usage_ledger
                        WHERE user_id=? AND automation_run_id=? AND usage_kind='BUDGET_ROOT'
                        """, Long.class, actor, parent);
                if (originalLimit != null) parentModelLimit = Math.min(parentModelLimit, originalLimit);
                Long consumed = jdbc.queryForObject("""
                        SELECT coalesce(sum(amount),0) FROM agent_usage_ledger
                        WHERE user_id=? AND usage_kind='MODEL_CALL'
                          AND (automation_run_id=? OR (root_request_id IS NULL AND request_id=?))
                        """, Long.class, actor, parent, parent);
                requireRemaining(kind, consumed == null ? 0 : consumed, attempts.size(), parentModelLimit);
                metadata.put("parent_model_limit", parentModelLimit);
            }
            jdbc.update("UPDATE agent_usage_ledger SET metadata=?::jsonb,automation_run_id=? WHERE id=?",
                    json(metadata), parent, rows.getFirst().get("id"));
            for (var a : attempts) jdbc.update("""
                    INSERT INTO agent_usage_ledger(user_id,request_id,root_request_id,automation_run_id,step_id,
                        usage_kind,amount,limit_value,task_type,model_config_id,attempt_id,attempt_hash,reservation_status,metadata)
                    VALUES (?,?,?,?,?,?,1,?,?,?,?,?,'RESERVED',?::jsonb ||
                        CASE WHEN ?='MODEL_CALL' THEN jsonb_build_object('model_started_at',clock_timestamp(),'model_time_budget_ms',?)
                        ELSE '{}'::jsonb END)
                    """, actor, root, root, parent, key, kind, cap, request.taskType().name(),
                    request.modelSpec() == null ? null : request.modelSpec().configId(), a.id, a.hash,
                    json(a.tool == null ? Map.of() : Map.of("tool", a.tool)), kind, modelTimeBudget);
            return true;
        }));
    }

    /** Each in-flight or uncertain attempt holds its full allocation; known completions use database elapsed time. */
    public void requireMinimumRemaining(AgentRunRequest request, long minimumModels, long minimumTools) {
        if (minimumModels < 1 || minimumTools < 0) throw new IllegalArgumentException("Invalid minimum native chain");
        tx.executeWithoutResult(status -> {
            long actor = actor(request); UUID root = requireRoot(request);
            UUID parent = parent(request, actor);
            Long parentLimit = lockParent(parent, actor, request);
            Map<String,Object> run = lockRun(request, actor);
            if (!Objects.equals(parent, parent(request, actor)))
                throw new BusinessException(409, "后台运行关联已变化，请核对原执行");
            Limits scope = scopeLimits(request);
            Limits total = rootLimits(request, run, scope);
            long modelCap = total.models, toolCap = total.tools, scopeModels = scope.models, scopeTools = scope.tools;
            var roots = jdbc.queryForList("""
                    SELECT metadata FROM agent_usage_ledger WHERE user_id=? AND root_request_id=?
                        AND usage_kind='BUDGET_ROOT' FOR UPDATE
                    """, actor, root);
            if (roots.size() > 1) throw new BusinessException(409, "预算根不唯一");
            if (!roots.isEmpty()) {
                var data = object(roots.getFirst().get("metadata"));
                modelCap = Math.min(modelCap, data.path("model_limit").asLong());
                toolCap = Math.min(toolCap, data.path("tool_limit").asLong());
                var part = data.path("scopes").path(scopeKey(request));
                if (!part.isMissingNode()) {
                    scopeModels = Math.min(scopeModels, part.path("model_limit").asLong());
                    scopeTools = Math.min(scopeTools, part.path("tool_limit").asLong());
                }
                long activityLimit = Math.multiplyExact((long) frozenModelLimits(data.get("generation_limits"))
                        .shrink(ModelLimits.from(properties)).modelActivityBudgetSeconds(), 1000);
                requireRemaining("MODEL_ACTIVITY_MILLIS", reservedModelActivity(actor, root), minimumModels, activityLimit);
            }
            requireRemaining("MODEL_CALL_MINIMUM_CHAIN", usage(actor, root, "MODEL_CALL", null), minimumModels, modelCap);
            requireRemaining("MODEL_CALL_MINIMUM_CHAIN", usage(actor, root, "MODEL_CALL", scopeKey(request)), minimumModels, scopeModels);
            requireRemaining("TOOL_CALL_MINIMUM_CHAIN", usage(actor, root, "TOOL_CALL", null), minimumTools, toolCap);
            requireRemaining("TOOL_CALL_MINIMUM_CHAIN", usage(actor, root, "TOOL_CALL", scopeKey(request)), minimumTools, scopeTools);
            if (parent != null) {
                Long original = jdbc.queryForObject("""
                        SELECT min((metadata->>'parent_model_limit')::bigint) FROM agent_usage_ledger
                        WHERE user_id=? AND automation_run_id=? AND usage_kind='BUDGET_ROOT'
                        """, Long.class, actor, parent);
                if (original != null) parentLimit = Math.min(parentLimit, original);
                Long consumed = jdbc.queryForObject("""
                        SELECT coalesce(sum(amount),0) FROM agent_usage_ledger WHERE user_id=? AND usage_kind='MODEL_CALL'
                            AND (automation_run_id=? OR (root_request_id IS NULL AND request_id=?))
                        """, Long.class, actor, parent, parent);
                requireRemaining("PARENT_MODEL_CALL_MINIMUM_CHAIN", consumed == null ? 0 : consumed, minimumModels, parentLimit);
            }
        });
    }

    /** The root lock serializes allocations even when multiple native experts share it. */
    private long reservedModelActivity(long actor, UUID root) {
        long total = 0;
        for (var row : jdbc.queryForList("""
                SELECT metadata,reservation_status FROM agent_usage_ledger
                WHERE user_id=? AND root_request_id=? AND usage_kind='MODEL_CALL'
                """, actor, root)) {
            var data = object(row.get("metadata"));
            long allocated = data.path("model_time_budget_ms").asLong(0);
            if (allocated <= 0 || !data.hasNonNull("model_started_at"))
                throw new BusinessException(409, "原模型尝试缺少活动时长证据，请核对原运行");
            long charged = allocated;
            if ("FINISHED".equals(row.get("reservation_status")) && data.hasNonNull("model_finished_at")) {
                try {
                    long elapsed = Duration.between(OffsetDateTime.parse(data.path("model_started_at").asText()),
                            OffsetDateTime.parse(data.path("model_finished_at").asText())).toMillis();
                    charged = Math.max(1, Math.min(allocated, elapsed));
                } catch (RuntimeException invalid) {
                    throw new BusinessException(409, "原模型尝试的活动时长证据无效");
                }
            }
            total = Math.addExact(total, charged);
        }
        return total;
    }

    /** Called immediately before native dispatch; SQL time includes the delay after the committed reservation. */
    public Duration modelTimeAllowance(AgentRunRequest request, UUID attempt) {
        return tx.execute(status -> {
            long actor = actor(request);
            UUID parent = parent(request, actor);
            lockParent(parent, actor, request);
            lockRun(request, actor);
            if (!Objects.equals(parent, parent(request, actor)))
                throw new BusinessException(409, "后台运行关联已变化，请核对原执行");
            var rows = jdbc.queryForList("""
                    SELECT greatest(0,(metadata->>'model_time_budget_ms')::bigint-
                        ceil(extract(epoch FROM (clock_timestamp()-(metadata->>'model_started_at')::timestamptz))*1000)::bigint) AS remaining
                    FROM agent_usage_ledger WHERE user_id=? AND root_request_id=? AND usage_kind='MODEL_CALL'
                        AND attempt_id=? AND reservation_status='RESERVED'
                    """, actor, requireRoot(request), attempt);
            if (rows.size() != 1 || !(rows.getFirst().get("remaining") instanceof Number remaining) || remaining.longValue() <= 0)
                throw new AgentBudgetExceededException("MODEL_ACTIVITY_MILLIS", 0, 1);
            return Duration.ofMillis(remaining.longValue());
        });
    }

    private UUID parent(AgentRunRequest request, long actor) {
        var parents = jdbc.queryForList("""
                SELECT DISTINCT a.id FROM automation_run_execution e
                JOIN automation_run a ON a.id=e.run_id
                JOIN manga_agent_runs r ON r.id=e.agent_run_id AND r.user_id=a.user_id AND r.request_id=e.request_id
                JOIN ai_conversations c ON c.id=r.conversation_id AND c.user_id=r.user_id
                    AND c.chapter_id=r.chapter_id AND c.story_id=r.story_id AND c.conversation_uuid=e.conversation_uuid
                WHERE a.user_id=? AND r.request_id=? AND r.story_id=? AND r.chapter_id=? AND c.conversation_uuid=?
                  AND ((e.execution_kind='MANGA_AGENT' AND r.run_type='MANGA_AGENT')
                    OR (e.execution_kind='STORY_CHAT' AND r.run_type='STORY_CHAT'))
                """, actor, request.requestId(), request.storyId(), request.chapterId(), request.conversationId());
        if (parents.size() > 1) throw new BusinessException(409, "后台父运行关联不唯一");
        if (parents.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM automation_run_execution e JOIN manga_agent_runs r ON r.id=e.agent_run_id
                    WHERE r.user_id=? AND r.request_id=?)
                """, Boolean.class, actor, request.requestId())))
            throw new BusinessException(403, "后台执行关系不完整，不能作为交互运行执行");
        return parents.isEmpty() ? null : (UUID) parents.getFirst().get("id");
    }

    private Long lockParent(UUID parent, long actor, AgentRunRequest request) {
        if (parent == null) {
            if (request.variables().containsKey("automation_run_id")
                    || request.variables().containsKey("automation_execution_owner")
                    || request.variables().containsKey("automation_fencing_token"))
                throw new BusinessException(403, "后台原执行关联不存在");
            return null;
        }
        var rows = jdbc.queryForList("SELECT * FROM automation_run WHERE id=? AND user_id=? FOR UPDATE", parent, actor);
        if (rows.size() != 1) throw new BusinessException(409, "后台父运行不存在");
        var row = rows.getFirst();
        Object fence = request.variables().get("automation_fencing_token");
        Object owner = request.variables().get("automation_execution_owner");
        if (!parent.toString().equals(request.variables().get("automation_run_id"))
                || !(owner instanceof String claimedOwner) || claimedOwner.isBlank()
                || !claimedOwner.equals(row.get("lease_owner"))
                || !(fence instanceof Number n) || n.longValue() <= 0
                || !Objects.equals(n.longValue(), row.get("fencing_token")))
            throw new BusinessException(409, "后台原执行身份已变化或缺失");
        Boolean owned = jdbc.queryForObject("""
                SELECT state='RUNNING' AND lease_owner IS NOT NULL AND lease_until>clock_timestamp()
                FROM automation_run WHERE id=? AND user_id=?
                """, Boolean.class, parent, actor);
        long limit = object(row.get("definition_snapshot")).path("limits").path("maxModelCallsPerRun").asLong(0);
        if (!Boolean.TRUE.equals(owned) || limit <= 0) throw new BusinessException(409, "后台执行权或预算无效");
        return limit;
    }

    private Map<String, Object> lockRun(AgentRunRequest request, long actor) {
        var rows = jdbc.queryForList("""
                SELECT r.*,c.conversation_uuid FROM manga_agent_runs r
                JOIN ai_conversations c ON c.id=r.conversation_id AND c.user_id=r.user_id
                    AND c.story_id=r.story_id AND c.chapter_id=r.chapter_id
                WHERE r.user_id=? AND r.request_id=? FOR UPDATE OF r
                """, actor, request.requestId());
        if (rows.isEmpty()) {
            if (request.taskType().isMangaExecutionTask()) throw new BusinessException(409, "预算缺少实际业务运行");
            return Map.of();
        }
        var row = rows.getFirst();
        if (!Objects.equals(row.get("story_id"), request.storyId()) || !Objects.equals(row.get("chapter_id"), request.chapterId())
                || !Objects.equals(row.get("conversation_uuid"), request.conversationId()))
            throw new BusinessException(403, "预算运行归属不匹配");
        if (request.taskType().isMangaExecutionTask()) {
            Object fence = request.variables().get("fencing_token");
            Object owner = request.variables().get("execution_owner");
            if (!(owner instanceof String claimedOwner) || claimedOwner.isBlank()
                    || !claimedOwner.equals(row.get("owner_instance_id"))
                    || !(fence instanceof Number n) || !Objects.equals(n.longValue(), row.get("fencing_token")))
                throw new BusinessException(409, "预算运行执行身份已变化");
            Boolean owned = jdbc.queryForObject("""
                    SELECT status='RUNNING' AND owner_instance_id IS NOT NULL AND lease_until>clock_timestamp()
                    FROM manga_agent_runs WHERE id=?
                    """, Boolean.class, row.get("id"));
            if (!Boolean.TRUE.equals(owned)) throw new BusinessException(409, "预算运行已停止或租约过期");
            if (request.variables().containsKey("automation_run_id")) {
                String key = ToolIdempotencyService.sha256(claimedOwner + ":" + n.longValue());
                var claim = object(row.get("run_attributes_json")).path("automation_execution_claims").path(key);
                if (!claimedOwner.equals(claim.path("child_owner").asText())
                        || n.longValue() != claim.path("child_fencing_token").asLong(-1)
                        || !Objects.equals(request.variables().get("automation_run_id"), claim.path("automation_run_id").asText())
                        || !Objects.equals(request.variables().get("automation_execution_owner"), claim.path("automation_execution_owner").asText())
                        || !(request.variables().get("automation_fencing_token") instanceof Number parentFence)
                        || parentFence.longValue() != claim.path("automation_fencing_token").asLong(-1))
                    throw new BusinessException(409, "后台原执行凭证与子运行不匹配");
            }
            requireExpertBinding(request, row);
        }
        return row;
    }

    private static AgentReviewExpert expert(AgentRunRequest request) {
        Object value = request.variables().get("review_expert");
        if (value == null) return null;
        if (!(value instanceof AgentReviewExpert identity) || request.taskType() != AgentTaskType.MANGA_REVIEW
                || !Objects.equals(request.requestId(), identity.parentRequestId())
                || !Objects.equals(request.variables().getOrDefault("step_id", request.taskType().sessionSuffix()), identity.parentStepId())
                || identity.taskId() == null || identity.taskId().isBlank() || identity.taskId().length() > 256
                || !Set.of("camera-reviewer", "character-reviewer", "pacing-reviewer", "continuity-reviewer").contains(identity.expertId()))
            throw new BusinessException(403, "REVIEW_EXPERT_DENIED: invalid parent or expert identity");
        return identity;
    }

    private static void requireExpertBinding(AgentRunRequest request, Map<String, Object> run) {
        AgentReviewExpert expert = expert(request);
        if (expert == null) return;
        var binding = object(run.get("run_attributes_json")).path("review_expert_tasks")
                .path(expert.parentStepId()).path(expert.expertId());
        if (!expert.taskId().equals(binding.path("task_id").asText())
                || !expert.expertId().equals(binding.path("expert_id").asText())
                || !expert.parentRequestId().toString().equals(binding.path("parent_request_id").asText())
                || !expert.parentStepId().equals(binding.path("parent_step_id").asText())
                || !"local-tools-v1".equals(binding.path("profile").asText())
                || binding.path("spawn_call_id").asText().isBlank())
            throw new BusinessException(403, "REVIEW_EXPERT_DENIED: persisted native task binding changed");
    }

    private Limits scopeLimits(AgentRunRequest request) {
        return expert(request) == null ? limits(request.taskType(), request.businessSkillSelection()) : new Limits(3, 2);
    }

    private ObjectNode binding(AgentRunRequest request, Map<String, Object> run, UUID parent) {
        var node = JSON.createObjectNode();
        node.put("actor", actor(request));
        node.set("story", JSON.valueToTree(request.storyId()));
        node.set("chapter", JSON.valueToTree(request.chapterId()));
        node.set("conversation", JSON.valueToTree(request.conversationId()));
        String family = switch (request.taskType()) {
            case MANGA_ROUTER, MANGA_DIRECTOR, MANGA_CONVERSATION, MANGA_CREATIVE, MANGA_STORYBOARD, MANGA_REVIEW -> "MANGA";
            case STORY_CHAT_READ, STORY_CHAT_WRITE -> "STORY_CHAT";
            default -> request.taskType().name();
        };
        node.put("family", family);
        var execution = node.putObject("execution");
        execution.put("source", parent != null ? "AUTOMATION" : run.isEmpty() && request.taskType() == AgentTaskType.MANGA_ROUTER
                ? "ROUTING" : "INTERACTIVE");
        execution.set("run_id", JSON.valueToTree(run.get("id")));
        execution.set("automation_run_id", JSON.valueToTree(parent));
        return object(json(node));
    }

    private JsonNode modelIdentity(AgentRunRequest request) {
        var spec = request.modelSpec();
        if (spec == null) throw new BusinessException(400, "预算缺少模型配置");
        var node = JSON.createObjectNode().put("provider", spec.provider()).put("model", spec.model())
                .put("endpoint_hash", AgentModelSpecFactory.sha256Hex(spec.baseUrl().trim().replaceAll("/+$", "")));
        node.set("config_id", JSON.valueToTree(spec.configId()));
        node.set("capabilities", JSON.valueToTree(spec.capabilities()));
        return object(json(node));
    }

    private long usage(long actor, UUID root, String kind, String step) {
        Long value = jdbc.queryForObject("""
                SELECT coalesce(sum(amount),0) FROM agent_usage_ledger
                WHERE user_id=? AND request_id=? AND usage_kind=?
                """ + (step == null ? "" : " AND step_id=?"), Long.class,
                step == null ? new Object[]{actor, root, kind} : new Object[]{actor, root, kind, step});
        return value == null ? 0 : value;
    }

    public Limits limits(AgentTaskType type, BusinessSkillSelection skills) {
        var a = properties.getAgent();
        if (type == null) throw new BusinessException(400, "预算缺少任务类型");
        Limits limits = switch (type) {
            case MANGA_ROUTER, KNOWLEDGE_EXTRACTION -> new Limits(a.getRouterMaxModelCalls(), 0);
            case CHAT, NOVEL -> new Limits(a.getTextMaxModelCalls(), skills == null || skills.isEmpty() ? 0 : a.getTextMaxToolCalls());
            case READER_CHARACTER_CHAT -> new Limits(a.getTextMaxModelCalls(), 0);
            case MANGA_CONVERSATION, MANGA_CREATIVE -> new Limits(a.getConversationMaxModelCalls(), a.getConversationMaxToolCalls());
            case MANGA_STORYBOARD -> new Limits(a.getStoryboardMaxModelCalls(), a.getStoryboardMaxToolCalls());
            case MANGA_REVIEW -> new Limits(a.getReviewMaxModelCalls(), a.getReviewMaxToolCalls());
            case MANGA_DIRECTOR -> new Limits(a.getDirectorMaxModelCalls(), a.getDirectorMaxToolCalls());
            case STORY_CHAT_READ -> new Limits(a.getStoryChatReadMaxModelCalls(), a.getStoryChatReadMaxToolCalls());
            case STORY_CHAT_WRITE -> new Limits(a.getStoryChatWriteMaxModelCalls(), a.getStoryChatWriteMaxToolCalls());
        };
        if (limits.models <= 0 || limits.tools < 0) throw new BusinessException(400, "Agent 预算必须为明确的有限正值");
        return limits;
    }

    private Limits rootLimits(AgentRunRequest request, Map<String, Object> run, Limits scope) {
        if (expert(request) != null) scope = limits(AgentTaskType.MANGA_REVIEW, BusinessSkillSelection.empty());
        boolean manga = switch (request.taskType()) {
            case MANGA_ROUTER, MANGA_DIRECTOR, MANGA_CONVERSATION, MANGA_CREATIVE, MANGA_STORYBOARD, MANGA_REVIEW -> true;
            default -> false;
        };
        boolean storyChat = request.taskType() == AgentTaskType.STORY_CHAT_READ || request.taskType() == AgentTaskType.STORY_CHAT_WRITE;
        if (!run.isEmpty() && ((manga && !"MANGA_AGENT".equals(run.get("run_type")))
                || (storyChat && !"STORY_CHAT".equals(run.get("run_type")))
                || ("DIRECTOR".equals(run.get("route")) && !manga)))
            throw new BusinessException(403, "预算运行类型与工作流范围不匹配");
        if (request.taskType() == AgentTaskType.MANGA_ROUTER || "DIRECTOR".equals(run.get("route"))) {
            var limits = new Limits(properties.getAgent().getDirectorRootMaxModelCalls(), properties.getAgent().getDirectorRootMaxToolCalls());
            if (limits.models <= 0 || limits.tools < 0) throw new BusinessException(400, "导演根预算无效");
            return limits;
        }
        return scope;
    }

    public void finishModelCall(AgentRunRequest request, UUID attempt, String outcome) {
        if (!Set.of("FINISHED", "FAILED", "UNKNOWN").contains(outcome)) throw new IllegalArgumentException("Invalid observation");
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE agent_usage_ledger SET reservation_status=?,metadata=metadata||jsonb_build_object('model_finished_at',clock_timestamp())
                WHERE user_id=? AND root_request_id=? AND usage_kind='MODEL_CALL' AND attempt_id=? AND reservation_status='RESERVED'
                """, outcome, actor(request), requireRoot(request), attempt));
    }

    /** Native provider reports are observations, never substitutes for the durable attempt or token estimates. */
    public void finishModelCall(AgentRunRequest request, UUID attempt, String payloadHash, String outcome,
                                ChatUsage usage, boolean consistent) {
        if (!Set.of("FINISHED", "FAILED", "UNKNOWN").contains(outcome)) throw new IllegalArgumentException("Invalid observation");
        ObjectNode observation = JSON.createObjectNode().put("status", usage == null ? "UNAVAILABLE" : consistent ? "REPORTED" : "INCONSISTENT")
                .put("semantics", "native_openai_cumulative").put("source", "agentscope_chat_usage")
                .put("zero_values", "native_default_or_reported_zero");
        if (usage != null) observation.put("input_tokens", usage.getInputTokens()).put("output_tokens", usage.getOutputTokens())
                .put("cached_tokens", usage.getCachedTokens()).put("cache_creation_tokens", usage.getCacheCreationTokens())
                .put("reasoning_tokens", usage.getReasoningTokens()).put("tool_use_prompt_tokens", usage.getToolUsePromptTokens())
                .put("total_tokens", (long) usage.getInputTokens() + usage.getOutputTokens());
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE agent_usage_ledger SET reservation_status=?,metadata=metadata||jsonb_build_object('provider_usage',?::jsonb,'model_finished_at',clock_timestamp())
                WHERE user_id=? AND root_request_id=? AND usage_kind='MODEL_CALL' AND attempt_id=? AND attempt_hash=?
                    AND reservation_status='RESERVED'
                """, outcome, json(observation), actor(request), requireRoot(request), attempt, payloadHash));
    }

    /** Framework result observation only; a FINISHED native tool is not proof of a committed domain mutation. */
    public void observeToolResult(AgentRunRequest request, String callId, String toolName, String inputHash, ToolResultState state) {
        UUID attempt = UUID.nameUUIDFromBytes((scopeKey(request) + ":" + callId).getBytes(StandardCharsets.UTF_8));
        String outcome = state == null ? "UNKNOWN" : switch (state) {
            case SUCCESS -> "FINISHED";
            case ERROR, DENIED -> "FAILED";
            case INTERRUPTED -> "UNKNOWN";
            case RUNNING -> "RESERVED";
        };
        Map<String, String> observation = Map.of("observation", state == null ? "STREAM_TERMINATED" : "NATIVE_RESULT",
                "native_result_state", state == null ? "unknown" : state.getValue());
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE agent_usage_ledger SET reservation_status=?,metadata=metadata||?::jsonb
                WHERE user_id=? AND root_request_id=? AND usage_kind='TOOL_CALL' AND attempt_id=? AND attempt_hash=?
                    AND metadata->>'tool'=? AND reservation_status='RESERVED'
                """, outcome, json(observation), actor(request), requireRoot(request), attempt, inputHash, toolName));
    }

    public void recordModelMeasurement(AgentRunRequest request, UUID attempt, String payloadHash,
                                       String kind, long amount, long limit) {
        if (!Set.of("INPUT_TOKEN", "INPUT_BYTE", "OUTPUT_TOKEN", "OUTPUT_BYTE").contains(kind) || amount < 0)
            throw new IllegalArgumentException("Invalid measured usage");
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO agent_usage_ledger(user_id,request_id,root_request_id,automation_run_id,step_id,usage_kind,
                    amount,limit_value,task_type,attempt_id,attempt_hash,reservation_status,metadata)
                SELECT user_id,request_id,root_request_id,automation_run_id,step_id,?,?,?,task_type,attempt_id,attempt_hash,'FINISHED',?::jsonb
                FROM agent_usage_ledger WHERE user_id=? AND root_request_id=? AND usage_kind='MODEL_CALL'
                    AND attempt_id=? AND attempt_hash=? ON CONFLICT DO NOTHING
                """, kind, amount, limit, json(Map.of("measurement", kind.endsWith("TOKEN") ? "code_point_estimate"
                        : "INPUT_BYTE".equals(kind) ? "native_serialized_input_bytes" : "native_output_delta_bytes")),
                actor(request), requireRoot(request), attempt, payloadHash));
    }

    public void validateAndRecordInput(AgentRunRequest request, List<AgentMessage> effectiveMessages) {
        long estimate = effectiveMessages == null ? 0 : effectiveMessages.stream().mapToLong(m -> measureOutput(m.content()).estimatedTokens).sum();
        if (estimate > properties.getAgent().getMaxInputTokens())
            throw new AgentBudgetExceededException("INPUT_TOKEN_ESTIMATE", properties.getAgent().getMaxInputTokens(), estimate);
        // The Model delegate measures actual serialized input once per attempt.
    }
    public void validateAndRecordInput(AgentRunRequest request) { validateAndRecordInput(request, request.messages()); }
    public OutputUsage measureOutput(String text) {
        String value = text == null ? "" : text;
        // Code-point estimates are not provider-independent token upper bounds.
        return new OutputUsage(value.codePointCount(0, value.length()), value.getBytes(StandardCharsets.UTF_8).length);
    }
    public void requireOutputWithinLimit(OutputUsage usage) {
        requireRemaining("OUTPUT_TOKEN_ESTIMATE", 0, usage.estimatedTokens, properties.getAgent().getMaxOutputTokens());
        requireRemaining("OUTPUT_BYTE", 0, usage.bytes, properties.getAgent().getMaxOutputBytes());
    }
    private static void requireRemaining(String kind, long used, long count, long cap) {
        if (cap < 0 || used > cap || count > cap - used) throw new AgentBudgetExceededException(kind, cap, used + count);
    }
    private static long actor(AgentRunRequest r) {
        try { long id = Long.parseLong(r.userId()); if (id > 0) return id; }
        catch (RuntimeException ignored) { }
        throw new BusinessException(400, "Invalid agent user id");
    }
    private static UUID requireRoot(AgentRunRequest r) {
        if (r.requestId() == null) throw new BusinessException(400, "requestId is required for budgeted agent execution");
        return r.requestId();
    }
    private static String scopeKey(AgentRunRequest r) {
        AgentReviewExpert identity = expert(r);
        return ToolIdempotencyService.sha256(r.taskType().name() + ":" + r.variables().getOrDefault("step_id", r.taskType().sessionSuffix())
                + (identity == null ? "" : ":expert:" + identity.expertId()));
    }
    public static String hash(Object value) { return ToolIdempotencyService.sha256(json(value)); }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(400, "预算数据不可序列化"); }
    }
    private static ObjectNode object(Object value) {
        try { return (ObjectNode) JSON.readTree(value.toString()); }
        catch (Exception e) { throw new BusinessException(409, "预算持久记录无效"); }
    }

    private static ModelLimits frozenModelLimits(JsonNode value) {
        try {
            if (value == null || !value.isObject()) throw new IllegalArgumentException();
            return JSON.treeToValue(value, ModelLimits.class);
        } catch (Exception invalid) {
            throw new BusinessException(409, "原预算缺少有效生成配置，请保留产物并开始新请求");
        }
    }

    /** Immutable per-model ceiling; durable roots/scopes can only reduce it across reconstruction. */
    public record ModelLimits(int maxInputTokens, int maxInputBytes, int maxOutputTokens, int maxOutputBytes,
                              int modelTimeoutSeconds, int modelActivityBudgetSeconds) {
        public ModelLimits {
            if (maxInputTokens <= 0 || maxInputBytes <= 0 || maxOutputTokens <= 0 || maxOutputBytes <= 0
                    || modelTimeoutSeconds <= 0 || modelActivityBudgetSeconds <= 0)
                throw new IllegalArgumentException("Model generation limits must be positive and finite");
        }
        public static ModelLimits from(ArtVerseProperties properties) {
            var a = properties.getAgent();
            return new ModelLimits(a.getMaxInputTokens(), a.getMaxInputBytes(), a.getMaxOutputTokens(), a.getMaxOutputBytes(),
                    a.getModelIdleTimeoutSeconds(), a.getModelActivityBudgetSeconds());
        }
        ModelLimits shrink(ModelLimits current) {
            return new ModelLimits(Math.min(maxInputTokens, current.maxInputTokens), Math.min(maxInputBytes, current.maxInputBytes),
                    Math.min(maxOutputTokens, current.maxOutputTokens), Math.min(maxOutputBytes, current.maxOutputBytes),
                    Math.min(modelTimeoutSeconds, current.modelTimeoutSeconds), Math.min(modelActivityBudgetSeconds, current.modelActivityBudgetSeconds));
        }
        void requireWithin(ModelLimits frozen) {
            if (maxInputTokens > frozen.maxInputTokens || maxInputBytes > frozen.maxInputBytes
                    || maxOutputTokens > frozen.maxOutputTokens || maxOutputBytes > frozen.maxOutputBytes
                    || modelTimeoutSeconds > frozen.modelTimeoutSeconds || modelActivityBudgetSeconds > frozen.modelActivityBudgetSeconds)
                throw new BusinessException(409, "模型生成配置不能放大原预算范围，请使用原配置或开始新请求");
        }
    }
    private record Attempt(UUID id, String hash, String tool) { }
    public record Limits(long models, long tools) { }
    public record OutputUsage(long estimatedTokens, long bytes) {
        public OutputUsage plus(OutputUsage other) { return new OutputUsage(estimatedTokens + other.estimatedTokens, bytes + other.bytes); }
    }
}
