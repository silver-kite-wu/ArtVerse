package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Small SQL transactions provide cross-instance ownership; no future task lives only in memory. */
@Repository
@RequiredArgsConstructor
public class AutomationStore {
    static final String BLOCKING = "('QUEUED','RUNNING','RETRY_WAIT','WAITING_USER','CANCEL_REQUESTED')";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Plan(UUID id, long userId, String status, long version, Instant nextRunAt,
                       AutomationDefinition definition, Instant createdAt, Instant updatedAt) {}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Run(UUID id, UUID automationId, long userId, long automationVersion, String triggerKind,
                      Instant scheduledFor, AutomationDefinition definition, JsonNode inputSnapshot, String state,
                      int attemptCount, Instant nextAttemptAt, String leaseOwner, Instant leaseUntil, long fencingToken,
                      String summary, String errorCode, String errorMessage, String waitingReason, JsonNode response,
                      Instant createdAt, Instant startedAt, Instant lastProgressAt, Instant completedAt,
                      @com.fasterxml.jackson.annotation.JsonIgnore String responseKey,
                      @com.fasterxml.jackson.annotation.JsonIgnore String responseHash) {}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Execution(long id, UUID runId, String targetKey, String executionKind, Long agentRunId,
                            UUID scheduledMangaId, Long imageRecordId, UUID conversationId, UUID requestId) {}

    public record Action(String kind, String payloadHash, String requestHash, JsonNode target, JsonNode evidence) {
        @Override public String toString() { return "AutomationAction[kind=" + kind + "]"; }
    }

    public Plan plan(UUID id, long userId, boolean lock) {
        return one(jdbc.query("SELECT * FROM automation WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""), planMapper(), id, userId), "任务不存在");
    }
    public Run run(UUID id, long userId) {
        return one(jdbc.query("SELECT * FROM automation_run WHERE id=? AND user_id=?", runMapper(), id, userId), "运行记录不存在");
    }
    public Run run(UUID id) { return one(jdbc.query("SELECT * FROM automation_run WHERE id=?", runMapper(), id), "运行记录不存在"); }
    public Run lockRun(UUID id, long userId) {
        return one(jdbc.query("SELECT * FROM automation_run WHERE id=? AND user_id=? FOR UPDATE", runMapper(), id, userId), "运行记录不存在");
    }
    public Optional<Action> action(UUID runId, String key) {
        return jdbc.query("SELECT * FROM automation_run_action WHERE run_id=? AND idempotency_key=?", (r,n) ->
                new Action(r.getString("action_kind"), r.getString("payload_hash"), r.getString("request_hash"),
                        node(r.getString("target_binding")), node(r.getString("consumption_evidence"))), runId, key).stream().findFirst();
    }
    public List<Plan> plansForStory(long userId, long storyId, UUID before, int size) {
        if (before != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM automation WHERE id=? AND user_id=? AND definition->'payload'->>'storyId'=?)
                """, Boolean.class, before, userId, String.valueOf(storyId))))
            throw new BusinessException(400, "任务游标不属于当前作品");
        return jdbc.query("""
                SELECT a.* FROM automation a
                WHERE a.user_id=? AND a.definition->'payload'->>'storyId'=?
                  AND (?::uuid IS NULL OR (a.created_at,a.id)<
                      (SELECT created_at,id FROM automation WHERE id=? AND user_id=?))
                ORDER BY a.created_at DESC,a.id DESC LIMIT ?
                """, planMapper(), userId, String.valueOf(storyId), before, before, userId, Math.min(50, Math.max(1,size)));
    }

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlanPage(List<Plan> items, String nextCursor, boolean hasMore, String snapshot) {
        public PlanPage { items = List.copyOf(items); }
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record PlanCursor(int version, long userId, long storyId, String status, String taskType,
                              String snapshot, UUID lastId, Instant lastCreatedAt, Instant expiresAt) {}
    private record PlanSnapshot(String fingerprint, Instant observedAt) {}
    private static final String STORY_PLAN_FILTER = """
            a.user_id=? AND a.definition->'payload'->>'storyId'=?
            AND (?='' OR a.status=?) AND (?='' OR a.task_type=?)
            """;

    /** All statements on one page share a snapshot; a changed result set invalidates later pages. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlanPage planPageForStory(long userId, long storyId, String status, String taskType,
                                     String encodedCursor, Integer requestedLimit) {
        if (userId <= 0 || storyId <= 0) throw new BusinessException(403, "任务查询范围无效");
        String statusFilter = status == null ? "" : status;
        String typeFilter = taskType == null ? "" : taskType;
        if (!statusFilter.isEmpty() && !Set.of("ACTIVE", "PAUSED", "COMPLETED", "ARCHIVED").contains(statusFilter)
                || !typeFilter.isEmpty() && !Set.of("AGENT", "IMAGE").contains(typeFilter))
            throw new BusinessException(400, "任务查询筛选无效");
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 50) throw new BusinessException(400, "任务分页大小必须为 1–50");
        PlanCursor cursor = decodePlanCursor(encodedCursor);
        if (cursor != null && (cursor.version() != 1 || cursor.userId() != userId || cursor.storyId() != storyId
                || !statusFilter.equals(cursor.status()) || !typeFilter.equals(cursor.taskType())))
            throw new BusinessException(400, "任务游标与用户、作品或筛选不一致");

        PlanSnapshot current = jdbc.queryForObject("""
                SELECT md5(coalesce(string_agg(md5(to_jsonb(a)::text), '' ORDER BY a.created_at DESC,a.id DESC), '')) AS fingerprint,
                       clock_timestamp() AS observed_at FROM automation a WHERE
                """ + STORY_PLAN_FILTER, (r, n) -> new PlanSnapshot(r.getString("fingerprint"),
                r.getTimestamp("observed_at").toInstant()), userId, String.valueOf(storyId),
                statusFilter, statusFilter, typeFilter, typeFilter);
        if (current == null) throw new IllegalStateException("Missing automation list snapshot");
        if (cursor != null && (!cursor.snapshot().equals(current.fingerprint())
                || !cursor.expiresAt().isAfter(current.observedAt())))
            throw new BusinessException(409, "STALE_VERSION: 任务列表已变化或游标过期，请从第一页重新读取");
        if (cursor != null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM automation a WHERE "
                        + STORY_PLAN_FILTER + " AND a.id=? AND a.created_at=?)", Boolean.class,
                userId, String.valueOf(storyId), statusFilter, statusFilter, typeFilter, typeFilter,
                cursor.lastId(), timestamp(cursor.lastCreatedAt()))))
            throw new BusinessException(400, "任务游标的末项无效");

        List<Plan> rows = jdbc.query("SELECT a.* FROM automation a WHERE " + STORY_PLAN_FILTER
                        + " AND (?::uuid IS NULL OR (a.created_at,a.id)<(?::timestamptz,?::uuid))"
                        + " ORDER BY a.created_at DESC,a.id DESC LIMIT ?", planMapper(),
                userId, String.valueOf(storyId), statusFilter, statusFilter, typeFilter, typeFilter,
                cursor == null ? null : cursor.lastId(), cursor == null ? null : timestamp(cursor.lastCreatedAt()),
                cursor == null ? null : cursor.lastId(), limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Plan> items = rows.subList(0, Math.min(rows.size(), limit));
        String next = null;
        if (hasMore) {
            Plan last = items.getLast();
            Instant expiresAt = cursor == null ? current.observedAt().plusSeconds(900) : cursor.expiresAt();
            var nextCursor = new PlanCursor(1, userId, storyId, statusFilter, typeFilter, current.fingerprint(),
                    last.id(), last.createdAt(), expiresAt);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(json(nextCursor).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new PlanPage(items, next, hasMore, current.fingerprint());
    }

    private PlanCursor decodePlanCursor(String encoded) {
        if (encoded == null) return null;
        try {
            if (encoded.isBlank() || encoded.length() > 4096) throw new IllegalArgumentException();
            PlanCursor cursor = mapper.readValue(Base64.getUrlDecoder().decode(encoded), PlanCursor.class);
            if (cursor.snapshot() == null || !cursor.snapshot().matches("[0-9a-f]{32}")
                    || cursor.lastId() == null || cursor.lastCreatedAt() == null || cursor.expiresAt() == null)
                throw new IllegalArgumentException();
            return cursor;
        } catch (Exception malformed) { throw new BusinessException(400, "任务游标无效，请使用上一页返回的 next_cursor"); }
    }

    public List<Plan> plans(long userId, String status, String taskType, boolean needsAttention, String search, int page, int size) {
        return jdbc.query("""
                SELECT a.* FROM automation a WHERE a.user_id=? AND (?='' OR a.status=?)
                AND (?='' OR a.task_type=?) AND (?=false OR EXISTS
                  (SELECT 1 FROM automation_run r WHERE r.automation_id=a.id AND r.state='WAITING_USER'))
                AND (?='' OR lower(a.name) LIKE ? ESCAPE '!') ORDER BY a.created_at DESC,a.id LIMIT ? OFFSET ?
                """, planMapper(), userId, status, status, taskType, taskType, needsAttention,
                search, "%" + search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", size, page * size);
    }
    public List<Run> runs(UUID planId, UUID before, int size) {
        // UUID is a stable tie breaker; the tuple avoids losing runs with equal timestamps.
        return jdbc.query("""
                SELECT * FROM automation_run WHERE automation_id=?
                AND (?::uuid IS NULL OR (created_at,id)<(SELECT created_at,id FROM automation_run WHERE id=? AND automation_id=?))
                ORDER BY created_at DESC,id DESC LIMIT ?
                """, runMapper(), planId, before, before, planId, size);
    }
    public Optional<Run> invocation(UUID planId, String key) {
        return jdbc.query("SELECT * FROM automation_run WHERE automation_id=? AND invocation_key=?", runMapper(), planId, key).stream().findFirst();
    }
    public boolean blocking(UUID planId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM automation_run WHERE automation_id=? AND state IN " + BLOCKING + ")", Boolean.class, planId));
    }
    public List<Plan> duePlans(int batch) {
        return jdbc.query("SELECT * FROM automation WHERE status='ACTIVE' AND next_run_at<=now() ORDER BY next_run_at LIMIT ? FOR UPDATE SKIP LOCKED", planMapper(), batch);
    }
    public UUID insertRun(Plan plan, Instant due, String kind, String key, String state, String code, String summary) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,
                  definition_snapshot,state,error_code,summary,completed_at)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,CASE WHEN ?='SKIPPED' THEN now() END) ON CONFLICT DO NOTHING
                """, id, plan.id(), plan.userId(), plan.version(), kind, timestamp(due), key, json(plan.definition()), state, code, summary, state);
        UUID actual = invocation(plan.id(), key).map(Run::id).orElseThrow(() -> new BusinessException(409, "已有待处理或运行中的任务"));
        event(actual, state, Map.of("summary", Objects.toString(summary, "已登记运行")));
        return actual;
    }
    public void advance(Plan plan, Instant next) {
        jdbc.update("UPDATE automation SET next_run_at=?,updated_at=now() WHERE id=?", timestamp(next), plan.id());
    }
    public List<Run> claim(String owner, int batch, int leaseSeconds) {
        return jdbc.query("""
                UPDATE automation_run r SET lease_owner=?,lease_until=clock_timestamp()+make_interval(secs=>?),
                  fencing_token=fencing_token+1,state=CASE WHEN r.state='CANCEL_REQUESTED' THEN r.state ELSE 'RUNNING' END,
                  started_at=COALESCE(started_at,now()),last_progress_at=now(),attempt_count=attempt_count+1
                FROM (SELECT id FROM automation_run WHERE
                  state IN ('QUEUED','RETRY_WAIT','RUNNING','CANCEL_REQUESTED') AND next_attempt_at<=now()
                  AND (lease_owner IS NULL OR lease_until<=clock_timestamp()) ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED) q
                WHERE r.id=q.id RETURNING r.*
                """, runMapper(), owner, leaseSeconds, batch);
    }
    public boolean owned(Run run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM automation_run WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp())",
                Boolean.class, run.id(), run.leaseOwner(), run.fencingToken()));
    }
    public boolean renew(Run run, int leaseSeconds) {
        return jdbc.update("UPDATE automation_run SET lease_until=clock_timestamp()+make_interval(secs=>?),last_progress_at=now() WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp() AND state IN ('RUNNING','CANCEL_REQUESTED')",
                leaseSeconds, run.id(), run.leaseOwner(), run.fencingToken()) == 1;
    }
    public boolean transition(Run run, String state, String code, String summary, String waiting, int retrySeconds) {
        return jdbc.update("""
                UPDATE automation_run SET state=?,error_code=?,summary=?,waiting_reason=?,
                completed_at=CASE WHEN ? IN ('SUCCEEDED','PARTIAL_FAILED','FAILED','CANCELLED','SKIPPED') THEN now() ELSE NULL END,
                next_attempt_at=now()+make_interval(secs=>?),lease_owner=NULL,lease_until=NULL,last_progress_at=now()
                WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()
                """, state, code, summary, waiting, state, retrySeconds, run.id(), run.leaseOwner(), run.fencingToken()) == 1;
    }
    public void freeze(Run run, Object input) {
        String snapshot = json(input);
        jdbc.update("UPDATE automation_run SET input_snapshot=?::jsonb,snapshot_hash=? WHERE id=? AND input_snapshot IS NULL AND lease_owner=? AND fencing_token=? AND lease_until>now()",
                snapshot, hash(snapshot), run.id(), run.leaseOwner(), run.fencingToken());
    }
    public void attach(Run run, String target, String kind, Long agentId, UUID mangaId, Long imageId, UUID conversation, UUID requestId) {
        jdbc.update("""
                INSERT INTO automation_run_execution(run_id,target_key,execution_kind,agent_run_id,scheduled_manga_id,image_record_id,conversation_uuid,request_id)
                SELECT ?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM automation_run WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp())
                ON CONFLICT(run_id,target_key) DO UPDATE SET agent_run_id=COALESCE(automation_run_execution.agent_run_id,EXCLUDED.agent_run_id),
                  scheduled_manga_id=COALESCE(automation_run_execution.scheduled_manga_id,EXCLUDED.scheduled_manga_id),
                  image_record_id=COALESCE(automation_run_execution.image_record_id,EXCLUDED.image_record_id)
                """, run.id(), target, kind, agentId, mangaId, imageId, conversation, requestId, run.id(), run.leaseOwner(), run.fencingToken());
    }
    public List<Execution> executions(UUID runId) {
        return jdbc.query("SELECT * FROM automation_run_execution WHERE run_id=? ORDER BY id", (r,n) -> new Execution(
                r.getLong("id"), uuid(r,"run_id"), r.getString("target_key"), r.getString("execution_kind"),
                (Long)r.getObject("agent_run_id"), uuid(r,"scheduled_manga_id"), (Long)r.getObject("image_record_id"),
                uuid(r,"conversation_uuid"), uuid(r,"request_id")), runId);
    }
    public List<Map<String,Object>> events(UUID id, long after, int count) {
        return jdbc.query("SELECT * FROM automation_run_event WHERE run_id=? AND id>? ORDER BY id LIMIT ?", (r,n) -> Map.of(
                "id", r.getLong("id"), "type", r.getString("type"), "payload", node(r.getString("safe_payload")), "createdAt", instant(r,"created_at")), id, after, count);
    }
    public void event(UUID id, String type, Object payload) {
        jdbc.update("INSERT INTO automation_run_event(run_id,type,safe_payload) VALUES (?,?,?::jsonb)", id, type, json(payload));
    }
    public void notify(Run run, String kind, String summary) {
        jdbc.update("INSERT INTO automation_notification(user_id,run_id,notification_kind,summary) VALUES (?,?,?,?) ON CONFLICT(run_id,notification_kind) DO UPDATE SET summary=EXCLUDED.summary,read_at=NULL,created_at=now()",
                run.userId(), run.id(), kind, clip(summary,500));
    }
    public JdbcTemplate jdbc() { return jdbc; }
    @Transactional
    public boolean consumeResponse(Run run) {
        if (run.response() == null || run.responseKey() == null || run.responseHash() == null) return false;
        // The plan/run/child lock order is shared with respond, retry and worker completion.
        plan(run.automationId(), run.userId(), true);
        Run current = lockRun(run.id(), run.userId());
        if (!"RUNNING".equals(current.state()) || current.fencingToken() != run.fencingToken()
                || !Objects.equals(current.leaseOwner(), run.leaseOwner()) || !owned(run)
                || !Objects.equals(current.responseKey(), run.responseKey())
                || !Objects.equals(current.responseHash(), run.responseHash())
                || !Objects.equals(current.response(), run.response())) return false;
        Action action = action(run.id(), run.responseKey()).orElse(null);
        if (!matchesAction(run, action)) return false;
        JsonNode evidence = consumptionEvidence(run, action.target());
        if (evidence == null) return false;
        int cleared = jdbc.update("""
                UPDATE automation_run SET response=NULL,response_key=NULL,response_hash=NULL
                WHERE id=? AND state='RUNNING' AND fencing_token=? AND lease_owner=? AND lease_until>clock_timestamp()
                    AND response_key=? AND response_hash=? AND response=?::jsonb
                """, run.id(), run.fencingToken(), run.leaseOwner(), run.responseKey(), run.responseHash(), json(run.response()));
        if (cleared == 0) return false;
        int recorded = jdbc.update("""
                UPDATE automation_run_action SET consumed_at=clock_timestamp(),consumed_fencing_token=?,consumption_evidence=?::jsonb
                WHERE run_id=? AND idempotency_key=? AND request_hash=? AND target_binding=?::jsonb
                    AND consumed_at IS NULL AND consumption_evidence IS NULL
                """, run.fencingToken(), json(evidence), run.id(), run.responseKey(), run.responseHash(), json(action.target()));
        if (recorded != 1) throw new BusinessException(409, "原响应消费记录发生冲突，已回滚清理");
        return true;
    }

    public boolean responseConsumed(Run run) {
        if (run.responseKey() == null || run.responseHash() == null || run.response() == null) return false;
        Action action = action(run.id(), run.responseKey()).orElse(null);
        return matchesAction(run, action) && action.evidence() != null
                && action.target().equals(action.evidence().path("target"))
                && run.responseHash().equals(action.evidence().path("request_hash").asText())
                && (("ANSWER".equals(action.target().path("response_kind").asText())
                        && "NATIVE_RECORDED".equals(action.evidence().path("kind").asText()))
                    || ("REVIEWED".equals(action.target().path("response_kind").asText())
                        && "REVIEW_SUMMARY_COMPLETED".equals(action.evidence().path("kind").asText())))
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM automation_run_action WHERE run_id=? AND idempotency_key=?
                        AND request_hash=? AND consumed_at IS NOT NULL AND consumed_fencing_token IS NOT NULL)
                    """, Boolean.class, run.id(), run.responseKey(), run.responseHash()));
    }

    private boolean matchesAction(Run run, Action action) {
        if (action == null || action.kind() == null || action.payloadHash() == null || action.target() == null
                || !action.target().isObject() || !Objects.equals(run.responseHash(), action.requestHash())) return false;
        JsonNode target = action.target();
        String digest = hash(canonical(Map.of("kind", action.kind(), "payload_hash", action.payloadHash(), "target", target)));
        return digest.equals(action.requestHash()) && target.path("actor").asLong(-1) == run.userId()
                && run.id().toString().equals(target.path("automation_run_id").asText())
                && hash(canonical(run.definition())).equals(target.path("definition_hash").asText())
                && hash(canonical(run.response())).equals(target.path("response_hash").asText());
    }

    /** Only durable child evidence for this exact response can authorize clearing the queue slot. */
    private JsonNode consumptionEvidence(Run run, JsonNode target) {
        if (!Set.of("ANSWER", "REVIEWED").contains(target.path("response_kind").asText())) return null;
        UUID conversation;
        UUID request;
        try {
            conversation = UUID.fromString(target.path("conversation_id").asText());
            request = UUID.fromString(target.path("request_id").asText());
        } catch (IllegalArgumentException malformed) { return null; }
        var children = jdbc.queryForList("""
                SELECT r.run_attributes_json::text AS attributes,r.status,r.fencing_token
                FROM automation_run_execution e JOIN manga_agent_runs r ON r.id=e.agent_run_id
                JOIN ai_conversations c ON c.id=r.conversation_id
                WHERE e.run_id=? AND e.agent_run_id=? AND r.user_id=? AND r.chapter_id=?
                    AND e.request_id=r.request_id AND e.conversation_uuid=c.conversation_uuid
                    AND c.user_id=r.user_id AND c.story_id=r.story_id AND c.chapter_id=r.chapter_id
                    AND c.conversation_uuid=? AND r.request_id=? AND r.run_type='STORY_CHAT' AND e.execution_kind='STORY_CHAT'
                FOR UPDATE OF r
                """, run.id(), target.path("agent_run_id").asLong(-1), run.userId(), target.path("chapter_id").asLong(-1),
                conversation, request);
        if (children.size() != 1) return null;
        var child = children.getFirst();
        JsonNode attributes = node(String.valueOf(child.get("attributes")));
        var evidence = mapper.createObjectNode().put("request_hash", run.responseHash());
        evidence.set("target", target.deepCopy());
        if ("ANSWER".equals(target.path("response_kind").asText())) {
            JsonNode question = attributes.path("external_questions").path(target.path("call_id").asText());
            String canonicalResult = question.path("canonical_result").asText("");
            if (!"NATIVE_RECORDED".equals(question.path("delivery_status").asText())
                    || !target.path("accepted_action_id").asText().equals(question.path("action_id").asText())
                    || !target.path("answer_hash").asText().equals(question.path("answer_hash").asText())
                    || target.path("pending_version").asLong(-1) != question.path("pending_version").asLong(-2)
                    || !target.path("step_id").asText().equals(question.path("step_id").asText())
                    || !target.path("profile").asText().equals(question.path("profile").asText())
                    || !target.path("session_id").asText().equals(question.path("native_session_id").asText())
                    || question.path("native_checkpoint_version").asLong(-1) < 0 || canonicalResult.isBlank()
                    || !hash(canonicalResult).equals(question.path("native_result_hash").asText())) return null;
            return evidence.put("kind", "NATIVE_RECORDED").put("checkpoint_version", question.path("native_checkpoint_version").asLong())
                    .put("native_result_hash", question.path("native_result_hash").asText());
        }
        if ("REVIEWED".equals(target.path("response_kind").asText())) {
            JsonNode receipt = attributes.path("review_result");
            JsonNode continuation = attributes.path("review_summary_continuation");
            if (!"SUCCEEDED".equals(child.get("status")) || !"COMPLETED".equals(attributes.path("review_summary_status").asText())
                    || !target.path("artifact_id").asText().equals(continuation.path("artifact_id").asText())
                    || continuation.path("id").asText().isBlank()
                    || continuation.path("fencing_token").asLong(-1) != ((Number)child.get("fencing_token")).longValue()
                    || !target.path("receipt_hash").asText().equals(hash(canonical(receipt)))) return null;
            Integer artifacts = jdbc.queryForObject("""
                    SELECT count(*) FROM manga_agent_run_artifacts
                    WHERE run_id=? AND artifact_uuid=? AND artifact_type='NOVEL_CONTENT_DRAFT' AND status=?
                        AND COALESCE(payload->>'reviewed_content_hash',payload->>'content_hash')=?
                        AND payload->>'reviewed_chapter_version'=? AND payload->>'base_version'=?
                    """, Integer.class, target.path("agent_run_id").asLong(), UUID.fromString(target.path("artifact_id").asText()),
                    receipt.path("status").asText(), receipt.path("content_hash").asText(), receipt.path("chapter_version").asText(),
                    receipt.path("base_version").asText());
            if (artifacts == null || artifacts != 1) return null;
            return evidence.put("kind", "REVIEW_SUMMARY_COMPLETED").put("continuation_id", continuation.path("id").asText());
        }
        return null;
    }
    public String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Cannot serialize automation data", e); } }
    public String canonical(Object value) { return json(sorted(mapper.valueToTree(value))); }
    private JsonNode sorted(JsonNode node) {
        if(node.isObject()) {
            var result=mapper.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);
            names.forEach(name->result.set(name,sorted(node.get(name))));return result;
        }
        if(node.isArray()) {var result=mapper.createArrayNode();node.forEach(item->result.add(sorted(item)));return result;}
        return node;
    }
    public JsonNode node(String value) { try { return value == null ? null : mapper.readTree(value); } catch(Exception e) { throw new IllegalStateException("Invalid automation data",e); } }
    public AutomationDefinition definition(String value) { try { return mapper.readValue(value, AutomationDefinition.class); } catch(Exception e) { throw new IllegalStateException("Invalid automation definition",e); } }
    public Plan commandPlan(JsonNode value) { return mapper.convertValue(value,Plan.class); }
    public static String hash(String value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static String clip(String value,int length) { return value == null ? "" : value.substring(0, Math.min(value.length(),length)); }
    public static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet r,String key) throws SQLException { Timestamp t=r.getTimestamp(key); return t==null?null:t.toInstant(); }
    private static UUID uuid(ResultSet r,String key) throws SQLException { return r.getObject(key,UUID.class); }
    private static <T> T one(List<T> values,String message) { if(values.isEmpty())throw new BusinessException(404,message);return values.getFirst(); }
    private RowMapper<Plan> planMapper() { return (r,n) -> new Plan(uuid(r,"id"),r.getLong("user_id"),r.getString("status"),r.getLong("version"),instant(r,"next_run_at"),definition(r.getString("definition")),instant(r,"created_at"),instant(r,"updated_at")); }
    private RowMapper<Run> runMapper() { return (r,n) -> new Run(uuid(r,"id"),uuid(r,"automation_id"),r.getLong("user_id"),r.getLong("automation_version"),r.getString("trigger_kind"),instant(r,"scheduled_for"),definition(r.getString("definition_snapshot")),node(r.getString("input_snapshot")),r.getString("state"),r.getInt("attempt_count"),instant(r,"next_attempt_at"),r.getString("lease_owner"),instant(r,"lease_until"),r.getLong("fencing_token"),r.getString("summary"),r.getString("error_code"),r.getString("error_message"),r.getString("waiting_reason"),node(r.getString("response")),instant(r,"created_at"),instant(r,"started_at"),instant(r,"last_progress_at"),instant(r,"completed_at"),r.getString("response_key"),r.getString("response_hash")); }
}
