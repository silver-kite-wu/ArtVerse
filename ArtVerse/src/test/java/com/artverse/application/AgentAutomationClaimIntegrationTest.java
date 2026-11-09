package com.artverse.application;

import com.artverse.agent.AutomationExecutionClaim;
import com.artverse.agent.*;
import com.artverse.agent.gateway.AgentScopeRuntimeContextFactory;
import com.artverse.agent.gateway.tools.AgentToolCapability;
import com.artverse.application.workflow.*;
import com.artverse.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real parent/child row locks, Spring transactions, Flyway and assistant persistence in an isolated container. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MangaAgentRunService.class, MangaAgentRunSnapshotReader.class, AgentRunLeaseService.class,
        MangaAgentConversationService.class, ResultFinalizer.class, ExecutionFactVerifier.class,
        AgentAutomationClaimIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class AgentAutomationClaimIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean ChapterAccessService access() { return mock(ChapterAccessService.class); }
        @Bean MangaAgentConversationWriteService conversations() { return mock(MangaAgentConversationWriteService.class); }
        @Bean AiConversationService aiConversations() { return mock(AiConversationService.class); }
        @Bean StoryboardArtifactService artifacts() { return mock(StoryboardArtifactService.class); }
    }
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MangaAgentRunService runs;
    @Autowired AgentRunLeaseService leases;
    @Autowired ResultFinalizer finalizer;

    @Test void capabilityUsesThePersistedParentWorkflowAndOriginalOwnerInsteadOfClaimedSource() {
        var f = fixture(); bind(f);
        bindTask(f, AgentTaskType.MANGA_CONVERSATION);
        var request = request(f, AgentTaskType.MANGA_CONVERSATION);
        var scope = runs.captureToolExecutionScope(request);
        assertThat(scope.source()).isEqualTo(AgentToolCapability.Source.AUTOMATION);
        assertThat(scope.actorId()).isEqualTo(f.conversation().getUser().getId());
        assertThat(scope.writeChapterId()).isEqualTo(f.conversation().getChapter().getId());
        assertThat(scope.rootRequestId()).isEqualTo(f.request());
        assertThat(scope.automation()).isEqualTo(f.parent());
        assertThat(scope.sessionId()).isEqualTo(AgentScopeRuntimeContextFactory.createSessionId(request));
        assertThat(scope.profile()).isEqualTo("local-tools-v1");
        assertThat(scope.owner()).isEqualTo(f.child().owner());
        assertThat(scope.executionBindingHash()).hasSize(64);
        var variables = new LinkedHashMap<>(request.variables());
        variables.put("source", "INTERACTIVE"); variables.put("profile", "unrestricted"); variables.put("confirmed", true);
        assertThat(runs.captureToolExecutionScope(withVariables(request, variables))).isEqualTo(scope);
        variables.put("review_completed", true);
        assertThatThrownBy(() -> runs.captureToolExecutionScope(withVariables(request, variables)))
                .hasMessageContaining("persisted review phase differs");
        jdbc.update("UPDATE automation_run SET lease_owner='replacement' WHERE id=?", f.parent().runId());
        assertThatThrownBy(() -> runs.captureToolExecutionScope(request)).hasMessageContaining("原执行身份已变化");
    }

    @Test void capabilityRejectsMissingParentClaimAndAChangedPersistentTaskBinding() {
        var f = fixture(); bind(f); bindTask(f, AgentTaskType.MANGA_CONVERSATION);
        var request = request(f, AgentTaskType.MANGA_CONVERSATION);
        var variables = new LinkedHashMap<>(request.variables());
        variables.keySet().removeAll(f.parent().variables().keySet());
        assertThatThrownBy(() -> runs.captureToolExecutionScope(withVariables(request, variables)))
                .hasMessageContaining("原执行身份已变化");
        jdbc.update("UPDATE manga_agent_runs SET run_attributes_json=run_attributes_json-'manga_execution_bindings' WHERE id=?", f.childId());
        assertThatThrownBy(() -> runs.captureToolExecutionScope(request)).hasMessageContaining("workflow binding missing");
    }

    @Test void expertCapabilityRequiresItsActualPersistentTaskAndExactNativeSession() {
        var f = fixture(); bind(f);
        jdbc.update("UPDATE manga_agent_runs SET route='REVIEW',route_key='REVIEW' WHERE id=?", f.childId());
        bindTask(f, AgentTaskType.MANGA_REVIEW);
        var request = request(f, AgentTaskType.MANGA_REVIEW);
        String expertId = MangaReviewSubagentDeclarations.all().getFirst().getName();
        String step = AgentTaskType.MANGA_REVIEW.sessionSuffix();
        var identity = new AgentReviewExpert(expertId, "native-task-1", f.request(), step);
        try {
            String mapping = new ObjectMapper().writeValueAsString(Map.of(step, Map.of(expertId, Map.of(
                    "task_id", identity.taskId(), "expert_id", expertId, "parent_request_id", f.request().toString(),
                    "parent_step_id", step, "profile", "local-tools-v1", "spawn_call_id", "spawn-1"))));
            jdbc.update("UPDATE manga_agent_runs SET run_attributes_json=jsonb_set(run_attributes_json,'{review_expert_tasks}',?::jsonb) WHERE id=?", mapping, f.childId());
        } catch (Exception failure) { throw new AssertionError(failure); }
        String repository = "review-" + AgentModelSpecFactory.sha256Hex(request.userId() + ":" + request.storyId() + ":"
                + request.chapterId() + ":" + request.requestId() + ":" + step);
        String session = "expert-" + AgentModelSpecFactory.sha256Hex(repository + ":" + expertId + ":" + identity.taskId());
        var variables = new LinkedHashMap<>(request.variables());
        variables.put("review_expert", identity); variables.put("review_expert_session", session);
        var scope = runs.captureToolExecutionScope(withVariables(request, variables));
        assertThat(scope.source()).isEqualTo(AgentToolCapability.Source.EXPERT);
        assertThat(scope.expert()).isEqualTo(identity);
        assertThat(scope.sessionId()).isEqualTo(session);
        variables.put("review_expert_session", AgentScopeRuntimeContextFactory.createSessionId(request));
        assertThatThrownBy(() -> runs.captureToolExecutionScope(withVariables(request, variables)))
                .hasMessageContaining("expert session differs");
        variables.put("review_expert", new AgentReviewExpert(expertId, "foreign-task", f.request(), step));
        assertThatThrownBy(() -> runs.captureToolExecutionScope(withVariables(request, variables)))
                .hasMessageContaining("expert task binding changed");
    }

    private void bindTask(Fixture f, AgentTaskType type) {
        var context = new MangaWorkflowExecutionContext(f.conversation(), "scope fixture", f.request(), "", null, null,
                f.conversation().getUser(), f.conversation().getChapter(), null, false, type.sessionSuffix(), f.child());
        runs.bindMangaExecution(context, type);
    }
    private AgentRunRequest request(Fixture f, AgentTaskType type) {
        var variables = new LinkedHashMap<String,Object>(f.parent().variables());
        variables.put("execution_owner", f.child().owner()); variables.put("fencing_token", f.child().fencingToken());
        variables.put("step_id", type.sessionSuffix());
        return new AgentRunRequest(f.conversation().getUser().getId().toString(), f.conversation().getStory().getId(),
                f.conversation().getChapter().getId(), type, List.of(new AgentMessage("user", "scope fixture")),
                variables, null, "", f.request(), f.conversation().getConversationUuid());
    }
    private AgentRunRequest withVariables(AgentRunRequest request, Map<String,Object> variables) {
        return new AgentRunRequest(request.userId(),request.storyId(),request.chapterId(),request.taskType(),request.messages(),
                Map.copyOf(variables),request.modelSpec(),request.llmApiKey(),request.requestId(),request.conversationId());
    }

    @Test void firstAttachmentBindsTheOriginalParentAndCannotAdoptItsReplacement() {
        var f = fixture();
        assertThat(jdbc.queryForObject("SELECT agent_run_id FROM automation_run_execution WHERE run_id=?", Long.class, f.parent().runId())).isNull();
        bind(f);
        assertThat(jdbc.queryForObject("SELECT agent_run_id FROM automation_run_execution WHERE run_id=?", Long.class, f.parent().runId())).isEqualTo(f.childId());
        assertThat(runs.automationExecutionVariables(f.conversation(), f.request(), f.child())).containsAllEntriesOf(f.parent().variables());
        bind(f);
        jdbc.update("UPDATE automation_run SET lease_owner='replacement', fencing_token=fencing_token+1 WHERE id=?", f.parent().runId());
        var replacement = new AutomationExecutionClaim(f.parent().runId(), "replacement", f.parent().fencingToken() + 1);
        assertThatThrownBy(() -> runs.bindAutomationExecutionClaim(f.conversation(), f.request(), f.child(), replacement))
                .hasMessageContaining("不能替换原后台身份");
        assertThat(runs.automationExecutionVariables(f.conversation(), f.request(), f.child())).containsAllEntriesOf(f.parent().variables());
        assertThatThrownBy(() -> finish(f)).hasMessageContaining("原执行身份已变化");
        assertNoFinalEffects(f);
    }

    @Test void anExpiredParentCannotSaveAnAssistantOrFinalizeTheStillActiveChild() {
        var f = fixture(); bind(f);
        jdbc.update("UPDATE automation_run SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", f.parent().runId());
        assertThatThrownBy(() -> finish(f)).hasMessageContaining("停止或过期");
        assertNoFinalEffects(f);
    }

    @Test void cancellationHoldingTheParentLockWinsBeforeTheChildFinalSave() throws Exception {
        var f = fixture(); bind(f);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> tx().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM automation_run WHERE id=? FOR UPDATE", UUID.class, f.parent().runId());
                locked.countDown(); await(release);
                jdbc.update("UPDATE automation_run SET state='CANCEL_REQUESTED' WHERE id=?", f.parent().runId());
                runs.cancel(f.conversation(), f.request(), "parent cancelled");
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var save = pool.submit(() -> finish(f));
            assertThatThrownBy(() -> save.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); cancel.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> save.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasRootCauseMessage("后台原执行权已停止或过期");
            assertThat(jdbc.queryForObject("SELECT status FROM manga_agent_runs WHERE id=?", String.class, f.childId())).isEqualTo("CANCELLED");
            assertThat(messageCount(f)).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void finalSaveHoldingTheParentLockCommitsBeforeALaterCancellation() throws Exception {
        var f = fixture(); bind(f);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var save = pool.submit(() -> tx().executeWithoutResult(status -> {
                runs.lockAutomationExecution(f.conversation(), f.request(), f.child());
                runs.requireFinalizable(f.conversation(), f.request(), f.child());
                locked.countDown(); await(release); finish(f);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var cancel = pool.submit(() -> tx().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM automation_run WHERE id=? FOR UPDATE", UUID.class, f.parent().runId());
                jdbc.update("UPDATE automation_run SET state='CANCEL_REQUESTED' WHERE id=?", f.parent().runId());
                runs.cancel(f.conversation(), f.request(), "parent cancelled after result");
            }));
            assertThatThrownBy(() -> cancel.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); save.get(5, TimeUnit.SECONDS); cancel.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT status FROM manga_agent_runs WHERE id=?", String.class, f.childId())).isEqualTo("SUCCEEDED");
            assertThat(messageCount(f)).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void appendingARunEventMustNotRevertAConcurrentWaitingTransition() throws Exception {
        var f = fixture(); bind(f);
        var stale = tx().execute(status -> em.find(MangaAgentRun.class, f.childId()));
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var waiting = pool.submit(() -> tx().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM manga_agent_runs WHERE id=? FOR UPDATE", Long.class, f.childId());
                jdbc.update("UPDATE manga_agent_runs SET status='WAITING_USER', user_input_request_json=? WHERE id=?",
                        "{\"question\":\"继续吗\"}", f.childId());
                jdbc.update("UPDATE manga_agent_runs SET run_attributes_json=jsonb_set("
                                + "run_attributes_json,'{waiting_execution_claim}',?::jsonb) WHERE id=?",
                        "{\"owner\":\"" + f.child().owner() + "\",\"fencing_token\":" + f.child().fencingToken() + "}",
                        f.childId());
                locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var append = pool.submit(() -> runs.appendEvent(stale, "run_event", Map.of("type", "reply")));
            assertThatThrownBy(() -> append.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); waiting.get(5, TimeUnit.SECONDS); append.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT status FROM manga_agent_runs WHERE id=?", String.class, f.childId()))
                    .isEqualTo("WAITING_USER");
            assertThat(jdbc.queryForObject("SELECT user_input_request_json FROM manga_agent_runs WHERE id=?",
                    String.class, f.childId())).isEqualTo("{\"question\":\"继续吗\"}");
            assertThat(jdbc.queryForObject("SELECT run_attributes_json->'waiting_execution_claim'->>'owner' "
                            + "FROM manga_agent_runs WHERE id=?", String.class, f.childId()))
                    .isEqualTo(f.child().owner());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private void bind(Fixture f) { runs.bindAutomationExecutionClaim(f.conversation(), f.request(), f.child(), f.parent()); }
    private void finish(Fixture f) { finalizer.finalizeResult(f.conversation(), f.request(), MangaWorkflowRoute.CONVERSATION, MangaWorkflowResult.success("verified reply"), f.child()); }
    private long messageCount(Fixture f) { return jdbc.queryForObject("SELECT count(*) FROM manga_agent_messages WHERE request_id=?", Long.class, f.request()); }
    private void assertNoFinalEffects(Fixture f) {
        assertThat(messageCount(f)).isZero();
        assertThat(jdbc.queryForMap("SELECT status,final_reply,verified_at FROM manga_agent_runs WHERE id=?", f.childId()))
                .containsEntry("status", "RUNNING").containsEntry("final_reply", null).containsEntry("verified_at", null);
    }
    private TransactionTemplate tx() { var value = new TransactionTemplate(transactions); value.setTimeout(10); return value; }
    private static void await(CountDownLatch latch) { try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lock coordination timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }

    private Fixture fixture() {
        var f = tx().execute(status -> {
            User user = new User(); user.setUsername("claim_" + UUID.randomUUID()); user.setEmail(user.getUsername() + "@example.test"); user.setPasswordHash("isolated-fixture"); user.setRole(Role.USER); em.persist(user);
            Story story = new Story(); story.setTitle("claim fixture"); story.setUser(user); em.persist(story);
            Chapter chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1); em.persist(chapter);
            MangaAgentConversation conversation = new MangaAgentConversation(); conversation.setConversationUuid(UUID.randomUUID()); conversation.setTitle("claim fixture"); conversation.setUser(user); conversation.setStory(story); conversation.setChapter(chapter); em.persist(conversation);
            MangaAgentRun run = new MangaAgentRun(); run.setUser(user); run.setStory(story); run.setChapter(chapter); run.setConversation(conversation); run.setInputMessage("claim fixture"); run.setRoute(MangaWorkflowRoute.CONVERSATION); em.persist(run); em.flush(); leases.claim(run);
            return new Fixture(run.getId(), run.getRequestId(), conversation, MangaWorkflowExecutionContext.ExecutionClaim.from(run), new AutomationExecutionClaim(UUID.randomUUID(), "parent-original", 5L));
        });
        UUID plan = UUID.randomUUID();
        jdbc.update("INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,idempotency_key,request_hash) VALUES (?,?,'Claim fixture','AGENT','MANGA_AGENT','{}'::jsonb,'Asia/Shanghai',?,'fixture')", plan, f.conversation().getUser().getId(), plan.toString());
        jdbc.update("INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,definition_snapshot,state,lease_owner,lease_until,fencing_token) VALUES (?,?,?,0,'MANUAL',now(),?,'{}'::jsonb,'RUNNING',?,clock_timestamp()+interval '90 seconds',?)", f.parent().runId(), plan, f.conversation().getUser().getId(), f.parent().runId().toString(), f.parent().owner(), f.parent().fencingToken());
        jdbc.update("INSERT INTO automation_run_execution(run_id,target_key,execution_kind,conversation_uuid,request_id) VALUES (?,'agent','MANGA_AGENT',?,?)", f.parent().runId(), f.conversation().getConversationUuid(), f.request());
        return f;
    }
    private record Fixture(long childId, UUID request, MangaAgentConversation conversation, MangaWorkflowExecutionContext.ExecutionClaim child, AutomationExecutionClaim parent) { }
}
