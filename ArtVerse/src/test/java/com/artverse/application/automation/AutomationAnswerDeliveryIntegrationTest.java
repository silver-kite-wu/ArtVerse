package com.artverse.application.automation;

import com.artverse.agent.*;
import com.artverse.agent.gateway.AgentScopeRuntimeContextFactory;
import com.artverse.application.*;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
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

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Production Flyway, Spring transaction proxies and PostgreSQL; no local user database or providers. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AgentToolEffectService.class, AutomationStore.class, AutomationService.class, AutomationScheduleCalculator.class,
        MangaAgentRunService.class, MangaAgentRunSnapshotReader.class, AgentRunLeaseService.class,
        AutomationAnswerDeliveryIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class AutomationAnswerDeliveryIntegrationTest {
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
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE); }
        @Bean AutomationInputService inputs() { return mock(AutomationInputService.class); }
        @Bean NovelDraftReviewService reviews() { return mock(NovelDraftReviewService.class); }
    }
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AutomationStore store;
    @Autowired AutomationService service;
    @Autowired MangaAgentRunService agentRuns;
    @Autowired AgentRunLeaseService leases;

    @Test void responseAcceptanceAndQueueCommitTogetherBeforeAnyDeliveryClaim() {
        var f=fixture();
        var queued=service.respond(f.user(),f.request(),"answer-1",answer("q1",1));
        assertThat(queued.state()).isEqualTo("QUEUED");
        var question=question(f,"q1");
        assertThat(question.path("delivery_status").asText()).isEqualTo("ACCEPTED");
        var action=store.action(f.request(),"answer-1").orElseThrow();
        assertThat(action.kind()).isEqualTo("RESPOND");
        assertThat(action.target().path("accepted_action_id").asText()).isEqualTo(question.path("action_id").asText());
        assertThat(action.target().path("call_id").asText()).isEqualTo("q1");
        assertThat(action.target().path("profile").asText()).isEqualTo("local-tools-v1");
        assertThat(jdbc.queryForObject("SELECT owner_instance_id FROM manga_agent_runs WHERE id=?",String.class,f.agent())).isNull();
        assertThat(jdbc.queryForObject("SELECT fencing_token FROM manga_agent_runs WHERE id=?",Long.class,f.agent())).isEqualTo(f.context().fencingToken());
    }

    @Test void rollbackAfterQueueingAlsoRollsBackAcceptedAnswerAndAction() {
        var f=fixture();
        tx().executeWithoutResult(status->{service.respond(f.user(),f.request(),"rolled-back",answer("q1",1));status.setRollbackOnly();});
        assertThat(store.run(f.request()).response()).isNull();
        assertThat(store.run(f.request()).state()).isEqualTo("WAITING_USER");
        assertThat(store.action(f.request(),"rolled-back")).isEmpty();
        assertThat(question(f,"q1").path("status").asText()).isEqualTo("WAITING");
        assertThat(question(f,"q1").has("action_id")).isFalse();
    }

    @Test void acceptedAnswerAloneCannotClearTheResponseOrCreateConsumptionEvidence() {
        var f=fixture();service.respond(f.user(),f.request(),"answer-1",answer("q1",1));
        var claimed=claimParent(f);
        assertThat(store.consumeResponse(claimed)).isFalse();
        assertThat(store.responseConsumed(claimed)).isFalse();
        assertThat(store.run(f.request()).responseKey()).isEqualTo("answer-1");
        assertThat(store.action(f.request(),"answer-1").orElseThrow().evidence()).isNull();
    }

    @Test void clearingAndEvidenceAreAtomicAndReplayUsesTheSameTarget() {
        var f=fixture();service.respond(f.user(),f.request(),"answer-1",answer("q1",1));
        nativeProof(f);
        var claimed=claimParent(f);
        assertThat(store.consumeResponse(claimed)).isTrue();
        assertThat(store.run(f.request()).response()).isNull();
        var action=store.action(f.request(),"answer-1").orElseThrow();
        assertThat(action.evidence().path("kind").asText()).isEqualTo("NATIVE_RECORDED");
        assertThat(action.evidence().path("target")).isEqualTo(action.target());
        assertThat(store.consumeResponse(claimed)).isFalse();
        assertThat(store.responseConsumed(claimed)).isTrue();
    }

    @Test void aFailureWritingEvidenceRollsBackThePreviouslyClearedResponse() {
        var f=fixture();service.respond(f.user(),f.request(),"answer-1",answer("q1",1));nativeProof(f);
        var claimed=claimParent(f);
        jdbc.execute("ALTER TABLE automation_run_action ADD CONSTRAINT it_reject_consumption CHECK (consumption_evidence IS NULL) NOT VALID");
        try {
            assertThatThrownBy(()->store.consumeResponse(claimed)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { jdbc.execute("ALTER TABLE automation_run_action DROP CONSTRAINT it_reject_consumption"); }
        assertThat(store.run(f.request()).responseKey()).isEqualTo("answer-1");
        assertThat(store.action(f.request(),"answer-1").orElseThrow().evidence()).isNull();
        assertThat(store.responseConsumed(claimed)).isFalse();
    }

    @Test void oldConsumerAndIdempotencyReplayCannotReplaceOrClearTheSecondQuestion() {
        var f=fixture();service.respond(f.user(),f.request(),"answer-1",answer("q1",1));
        var continuation=nativeProof(f);
        var currentParent=store.run(f.request());
        var q2Context=new MangaAgentRuntimeContext(f.user(),f.story(),f.chapter(),f.conversation().getConversationUuid(),
                f.request(),null,AgentTaskType.STORY_CHAT_READ,"read-step",continuation.delivery().fencingToken(),null,
                continuation.run().getOwnerInstanceId(),new AutomationExecutionClaim(currentParent.id(),currentParent.leaseOwner(),currentParent.fencingToken()));
        var pending=agentRuns.recordExternalQuestion(q2Context,"q2",questionRequest());
        agentRuns.markWaiting(f.conversation(),f.request(),pending.request(),q2Context.fencingToken());
        jdbc.update("UPDATE automation_run SET state='WAITING_USER',waiting_reason='AGENT_INPUT' WHERE id=?",f.request());
        assertThatThrownBy(()->service.respond(f.user(),f.request(),"answer-2",answer("q2",2)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原响应仍在处理中");
        assertThat(store.action(f.request(),"answer-2")).isEmpty();
        var old=claimParent(f);
        assertThat(store.consumeResponse(old)).isTrue();
        jdbc.update("UPDATE automation_run SET state='WAITING_USER',waiting_reason='AGENT_INPUT',lease_owner=NULL,lease_until=NULL WHERE id=?",f.request());
        service.respond(f.user(),f.request(),"answer-2",answer("q2",2));
        assertThat(store.consumeResponse(old)).isFalse();
        assertThat(store.responseConsumed(old)).isTrue();
        service.respond(f.user(),f.request(),"answer-1",answer("q1",1));
        assertThat(store.run(f.request()).responseKey()).isEqualTo("answer-2");
        assertThat(question(f,"q2").path("delivery_status").asText()).isEqualTo("ACCEPTED");
        assertThatThrownBy(()->service.respond(f.user(),f.request(),"answer-1",mapper.createObjectNode()
                .put("decision","answer").put("answer","changed").put("toolCallId","q1").put("pendingVersion",1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("同一幂等键");
    }

    @Test void expiredParentLeaseCannotConsumeEvenWithValidNativeProof() {
        var f=fixture();service.respond(f.user(),f.request(),"answer-1",answer("q1",1));nativeProof(f);
        var claimed=claimParent(f);
        jdbc.update("UPDATE automation_run SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.request());
        assertThat(store.consumeResponse(claimed)).isFalse();
        assertThat(store.run(f.request()).responseKey()).isEqualTo("answer-1");
    }

    @Test void newActionConstraintRejectsConsumptionTimestampsWithoutTargetEvidence() {
        var f=fixture();service.respond(f.user(),f.request(),"answer-1",answer("q1",1));
        assertThatThrownBy(()->jdbc.update("UPDATE automation_run_action SET consumed_at=now(),consumed_fencing_token=1 WHERE run_id=?",f.request()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.responseConsumed(claimParent(f))).isFalse();
    }

    @Test void twoConcurrentResponseKeysAcceptOnlyOneQueueSlotAndOneAnswerAction() throws Exception {
        var f=fixture();
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Boolean>> outcomes=new ArrayList<>();
            for(String key:List.of("concurrent-a","concurrent-b"))outcomes.add(pool.submit(()->{
                ready.countDown();
                if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("barrier timeout");
                try { service.respond(f.user(),f.request(),key,answer("q1",1));return true; }
                catch(BusinessException conflict) { assertThat(conflict.getStatus()).isEqualTo(409);return false; }
            }));
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=new ArrayList<Boolean>();
            for(var result:outcomes)results.add(result.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true,false);
        } finally { start.countDown(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM automation_run_action WHERE run_id=?",Long.class,f.request())).isEqualTo(1);
        assertThat(question(f,"q1").path("delivery_status").asText()).isEqualTo("ACCEPTED");
    }

    @Test void upgradeFromV54PreservesLegacyActionsWithoutInventingConsumptionProof() {
        var f=fixture();
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
        try {
            Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).target("54").load().migrate();
            var parent=store.run(f.request());
            jdbc.update("INSERT INTO "+schema+".users SELECT * FROM public.users WHERE id=?",f.user());
            jdbc.update("INSERT INTO "+schema+".automation SELECT * FROM public.automation WHERE id=?",parent.automationId());
            jdbc.update("INSERT INTO "+schema+".automation_run SELECT * FROM public.automation_run WHERE id=?",f.request());
            jdbc.update("INSERT INTO "+schema+".automation_run_action(run_id,idempotency_key,request_hash) VALUES (?,'legacy-action','legacy-hash')",f.request());
            Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            var action=jdbc.queryForMap("SELECT * FROM "+schema+".automation_run_action WHERE run_id=?",f.request());
            assertThat(action.get("request_hash")).isEqualTo("legacy-hash");
            assertThat(action.get("action_kind")).isNull();
            assertThat(action.get("target_binding")).isNull();
            assertThat(action.get("consumed_at")).isNull();
            assertThat(action.get("consumption_evidence")).isNull();
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
    }

    private AutomationStore.Run claimParent(Fixture f) {
        jdbc.update("UPDATE automation_run SET state='RUNNING',lease_owner='fixture-worker',lease_until=clock_timestamp()+interval '90 seconds',fencing_token=fencing_token+1 WHERE id=?",f.request());
        return store.run(f.request());
    }

    private MangaAgentRunService.AnswerContinuation nativeProof(Fixture f) {
        var accepted=agentRuns.acceptAnswer(f.conversation(),f.request(),"q1",1L,"same answer");
        var continuation=agentRuns.claimAnswerDelivery(f.conversation(),f.request(),accepted);
        var parent=claimParent(f);
        var parentClaim=new AutomationExecutionClaim(parent.id(),parent.leaseOwner(),parent.fencingToken());
        agentRuns.bindAutomationExecutionClaim(f.conversation(),f.request(),
                com.artverse.application.workflow.MangaWorkflowExecutionContext.ExecutionClaim.from(continuation.run()),parentClaim);
        var variables=new LinkedHashMap<String,Object>(parentClaim.variables());
        variables.put("step_id","read-step");variables.put("fencing_token",continuation.delivery().fencingToken());
        variables.put("execution_owner",continuation.run().getOwnerInstanceId());
        var request=new AgentRunRequest(String.valueOf(f.user()),f.story(),f.chapter(),AgentTaskType.STORY_CHAT_READ,List.of(),
                variables,null,"",f.request(),
                f.conversation().getConversationUuid());
        agentRuns.recordNativeAnswer(request,continuation.delivery(),7,AgentScopeRuntimeContextFactory.createSessionId(request));
        return continuation;
    }

    private JsonNode answer(String call,long version) {
        return mapper.createObjectNode().put("decision","answer").put("answer","same answer").put("toolCallId",call).put("pendingVersion",version);
    }
    private AgentUserInputRequest questionRequest() { return new AgentUserInputRequest("Focus?",List.of(),true,"","USER_QUESTION"); }
    private JsonNode question(Fixture f,String call) {
        return store.node(jdbc.queryForObject("SELECT run_attributes_json::text FROM manga_agent_runs WHERE id=?",String.class,f.agent()))
                .path("external_questions").path(call);
    }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }

    private Fixture fixture() {
        var f=tx().execute(status->{
            var user=new User();user.setUsername("delivery_"+UUID.randomUUID());user.setEmail(user.getUsername()+"@example.test");
            user.setPasswordHash("isolated-fixture");user.setRole(Role.USER);em.persist(user);
            var story=new Story();story.setTitle("Delivery fixture");story.setUser(user);em.persist(story);
            var chapter=new Chapter();chapter.setStory(story);chapter.setChapterNumber(1);chapter.setNovelContent("Fixture source");em.persist(chapter);
            var conversation=new MangaAgentConversation();conversation.setConversationUuid(UUID.randomUUID());conversation.setTitle("Delivery fixture");
            conversation.setConversationType(AiConversationType.STORY_CHAT);conversation.setUser(user);conversation.setStory(story);conversation.setChapter(chapter);em.persist(conversation);
            var run=new MangaAgentRun();run.setUser(user);run.setStory(story);run.setChapter(chapter);run.setConversation(conversation);
            run.setRunType(AgentRunType.STORY_CHAT);run.setRouteKey("READ");run.setInputMessage("Read fixture");em.persist(run);em.flush();leases.claim(run);
            var context=new MangaAgentRuntimeContext(user.getId(),story.getId(),chapter.getId(),conversation.getConversationUuid(),
                    run.getRequestId(),null,AgentTaskType.STORY_CHAT_READ,"read-step",run.getFencingToken(),null,run.getOwnerInstanceId());
            return new Fixture(user.getId(),story.getId(),chapter.getId(),run.getId(),run.getRequestId(),conversation,context);
        });
        var question=agentRuns.recordExternalQuestion(f.context(),"q1",questionRequest());
        agentRuns.markWaiting(f.conversation(),f.request(),question.request(),f.context().fencingToken());
        var definition=new AutomationDefinition("Delivery fixture","AGENT","STORY_CHAT","Read fixture",
                new AutomationDefinition.Schedule("ONCE","2026-11-06T09:00:00",null,"Asia/Shanghai",List.of()),
                mapper.createObjectNode().put("storyId",f.story()).put("chapterId",f.chapter()),
                new AutomationDefinition.Limits(0,8,600),"RUN_LATEST","SKIP",null,null,"TASK_CENTER",null);
        UUID plan=UUID.randomUUID();
        jdbc.update("INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,idempotency_key,request_hash) VALUES (?,?,'Delivery fixture','AGENT','STORY_CHAT',?::jsonb,'Asia/Shanghai',?,'fixture')",
                plan,f.user(),store.json(definition),plan.toString());
        jdbc.update("INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,definition_snapshot,state,waiting_reason) VALUES (?,?,?,0,'MANUAL',now(),?,?::jsonb,'WAITING_USER','AGENT_INPUT')",
                f.request(),plan,f.user(),f.request().toString(),store.json(definition));
        jdbc.update("INSERT INTO automation_run_execution(run_id,target_key,execution_kind,agent_run_id,conversation_uuid,request_id) VALUES (?,'agent','STORY_CHAT',?,?,?)",
                f.request(),f.agent(),f.conversation().getConversationUuid(),f.request());
        return f;
    }
    private record Fixture(long user,long story,long chapter,long agent,UUID request,MangaAgentConversation conversation,MangaAgentRuntimeContext context) { }
}
