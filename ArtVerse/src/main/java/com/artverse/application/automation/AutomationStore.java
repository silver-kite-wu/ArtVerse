package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
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
                      Instant createdAt, Instant startedAt, Instant lastProgressAt, Instant completedAt) {}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Execution(long id, UUID runId, String targetKey, String executionKind, Long agentRunId,
                            UUID scheduledMangaId, Long imageRecordId, UUID conversationId, UUID requestId) {}

    public Plan plan(UUID id, long userId, boolean lock) {
        return one(jdbc.query("SELECT * FROM automation WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""), planMapper(), id, userId), "任务不存在");
    }
    public Run run(UUID id, long userId) {
        return one(jdbc.query("SELECT * FROM automation_run WHERE id=? AND user_id=?", runMapper(), id, userId), "运行记录不存在");
    }
    public Run run(UUID id) { return one(jdbc.query("SELECT * FROM automation_run WHERE id=?", runMapper(), id), "运行记录不存在"); }
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
                UPDATE automation_run r SET lease_owner=?,lease_until=now()+make_interval(secs=>?),
                  fencing_token=fencing_token+1,state=CASE WHEN r.state='CANCEL_REQUESTED' THEN r.state ELSE 'RUNNING' END,
                  started_at=COALESCE(started_at,now()),last_progress_at=now(),attempt_count=attempt_count+1
                FROM (SELECT id FROM automation_run WHERE
                  state IN ('QUEUED','RETRY_WAIT','RUNNING','CANCEL_REQUESTED') AND next_attempt_at<=now()
                  AND (lease_owner IS NULL OR lease_until<=now()) ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED) q
                WHERE r.id=q.id RETURNING r.*
                """, runMapper(), owner, leaseSeconds, batch);
    }
    public boolean owned(Run run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM automation_run WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>now())",
                Boolean.class, run.id(), run.leaseOwner(), run.fencingToken()));
    }
    public boolean renew(Run run, int leaseSeconds) {
        return jdbc.update("UPDATE automation_run SET lease_until=now()+make_interval(secs=>?),last_progress_at=now() WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>now() AND state IN ('RUNNING','CANCEL_REQUESTED')",
                leaseSeconds, run.id(), run.leaseOwner(), run.fencingToken()) == 1;
    }
    public boolean transition(Run run, String state, String code, String summary, String waiting, int retrySeconds) {
        return jdbc.update("""
                UPDATE automation_run SET state=?,error_code=?,summary=?,waiting_reason=?,
                completed_at=CASE WHEN ? IN ('SUCCEEDED','PARTIAL_FAILED','FAILED','CANCELLED','SKIPPED') THEN now() ELSE NULL END,
                next_attempt_at=now()+make_interval(secs=>?),lease_owner=NULL,lease_until=NULL,last_progress_at=now()
                WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>now()
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
                SELECT ?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM automation_run WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>now())
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
    public static String hash(String value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static String clip(String value,int length) { return value == null ? "" : value.substring(0, Math.min(value.length(),length)); }
    public static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet r,String key) throws SQLException { Timestamp t=r.getTimestamp(key); return t==null?null:t.toInstant(); }
    private static UUID uuid(ResultSet r,String key) throws SQLException { return r.getObject(key,UUID.class); }
    private static <T> T one(List<T> values,String message) { if(values.isEmpty())throw new BusinessException(404,message);return values.getFirst(); }
    private RowMapper<Plan> planMapper() { return (r,n) -> new Plan(uuid(r,"id"),r.getLong("user_id"),r.getString("status"),r.getLong("version"),instant(r,"next_run_at"),definition(r.getString("definition")),instant(r,"created_at"),instant(r,"updated_at")); }
    private RowMapper<Run> runMapper() { return (r,n) -> new Run(uuid(r,"id"),uuid(r,"automation_id"),r.getLong("user_id"),r.getLong("automation_version"),r.getString("trigger_kind"),instant(r,"scheduled_for"),definition(r.getString("definition_snapshot")),node(r.getString("input_snapshot")),r.getString("state"),r.getInt("attempt_count"),instant(r,"next_attempt_at"),r.getString("lease_owner"),instant(r,"lease_until"),r.getLong("fencing_token"),r.getString("summary"),r.getString("error_code"),r.getString("error_message"),r.getString("waiting_reason"),node(r.getString("response")),instant(r,"created_at"),instant(r,"started_at"),instant(r,"last_progress_at"),instant(r,"completed_at")); }
}
