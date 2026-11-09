package com.artverse.application.automation;

import com.artverse.agent.*;
import com.artverse.application.*;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated PostgreSQL, production Flyway and transaction proxies; no provider or local user data. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AgentToolEffectService.class, AutomationStore.class, AutomationService.class, AutomationScheduleCalculator.class,
        MangaAgentRunService.class, MangaAgentRunSnapshotReader.class, AgentRunLeaseService.class,
        AutomationModelCommandIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class AutomationModelCommandIntegrationTest {
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
    @Autowired AutomationInputService inputs;
    @Autowired MangaAgentRunService agentRuns;
    @Autowired AgentRunLeaseService leases;

    @BeforeEach void normalizeFixture() {
        reset(inputs);
        when(inputs.validateAndNormalize(anyLong(),any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(inputs.chapterIds(any(),anyString())).thenAnswer(invocation -> List.of(((JsonNode)invocation.getArgument(0)).path("chapterId").asLong()));
        when(inputs.config(anyLong(),any(),anyBoolean())).thenReturn(new UserProviderConfig("llm","fixture","Fixture","","https://fixture.invalid/v1","fixture-model",7L));
    }

    @Test void proposalHasNoFormalEffectAndAuthorDecisionExecutesOnceWithFrozenReplay() {
        var f=fixture();var proposal=propose(f);
        assertThat(proposal.status()).isEqualTo("AWAITING_REVIEW");assertThat(proposal.applied()).isFalse();
        assertThat(planCount(f)).isZero();
        assertThat(propose(f).artifactId()).isEqualTo(proposal.artifactId());
        var committed=decide(f,proposal,"confirm");
        assertThat(committed.applied()).isTrue();assertThat(planCount(f)).isEqualTo(1);
        var changed=service.lifecycle(f.user(),committed.result().id(),"pause",committed.result().version());
        assertThat(changed.status()).isEqualTo("PAUSED");
        assertThat(decide(f,proposal,"confirm").result()).isEqualTo(committed.result());
        assertThat(service.modelCommands(f.user(),f.chapter(),f.conversation().getConversationUuid(),null,20).items())
                .extracting(AutomationService.ModelCommandView::status).containsExactly("COMMITTED");
        assertThat(planCount(f)).isEqualTo(1);
    }

    @Test void nativeProposalAndAuthorDecisionKeepEvidenceAfterLateNativeFailure() throws Exception {
        var f = fixture();
        var budget = new AgentBudgetService(jdbc, transactions, new com.artverse.config.ArtVerseProperties());
        var request = nativeRequest(f);
        var call = new io.agentscope.core.message.ToolUseBlock("create-plan", "create_automation", Map.of("definition", "fixture"));
        assertThat(budget.reserveToolCalls(request, List.of(call))).isTrue();
        var attempt = AgentToolAttempt.capture(call, io.agentscope.core.agent.RuntimeContext.builder()
                .put(MangaAgentRuntimeContext.class, f.context()).build());
        var proposal = service.proposeModelCommand(f.context(), "create", null, null, definition(f), attempt);
        assertThat(planCount(f)).isZero();
        var saved = decide(f, proposal, "confirm");
        budget.observeToolResult(request, call.getId(), call.getName(), attempt.inputHash(), io.agentscope.core.message.ToolResultState.ERROR);
        var evidence = mapper.readTree(jdbc.queryForObject("SELECT metadata::text FROM agent_usage_ledger WHERE request_id=? AND usage_kind='TOOL_CALL'", String.class, f.context().requestId()));
        assertThat(evidence.path("domain_effect").path("artifact_id").asText()).isEqualTo(proposal.artifactId().toString());
        assertThat(evidence.path("domain_effect").path("status").asText()).isEqualTo("DRAFT");
        assertThat(evidence.path("author_review_receipt").path("status").asText()).isEqualTo("COMMITTED");
        assertThat(decide(f, proposal, "confirm").result()).isEqualTo(saved.result());
        assertThat(planCount(f)).isEqualTo(1);
    }

    @Test void mismatchedNativeAttemptRollsBackTheProposal() {
        var f = fixture();
        var call = new io.agentscope.core.message.ToolUseBlock("unreserved", "create_automation", Map.of());
        var attempt = AgentToolAttempt.capture(call, io.agentscope.core.agent.RuntimeContext.builder()
                .put(MangaAgentRuntimeContext.class, f.context()).build());
        assertThatThrownBy(() -> service.proposeModelCommand(f.context(), "create", null, null, definition(f), attempt))
                .hasMessageContaining("TOOL_EFFECT_DENIED");
        assertThat(service.modelCommands(f.user(), f.chapter(), f.context().conversationId(), null, 20).items()).isEmpty();
        assertThat(planCount(f)).isZero();
    }

    private AgentRunRequest nativeRequest(Fixture f) {
        jdbc.update("UPDATE manga_agent_runs SET route='CONVERSATION' WHERE id=?", f.run());
        return new AgentRunRequest(String.valueOf(f.user()), f.story(), f.chapter(), f.context().taskType(), List.of(),
                Map.of("step_id", f.context().stepId(), "fencing_token", f.context().fencingToken(),
                        "execution_owner", f.context().executionOwner()), new AgentModelSpec("fixture", "https://fixture.invalid/v1", "fixture", "hash"), "", f.context().requestId(),
                f.context().conversationId(), BusinessSkillSelection.empty());
    }

    @Test void exactHashVersionActorAndDecisionAreRequired() {
        var f=fixture();var proposal=propose(f);var other=fixture();
        assertThatThrownBy(()->service.decideModelCommand(other.user(),proposal.artifactId(),"confirm",proposal.commandHash(),null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
        assertThatThrownBy(()->service.decideModelCommand(f.user(),proposal.artifactId(),"confirm","0".repeat(64),null)).hasMessageContaining("内容");
        assertThatThrownBy(()->service.decideModelCommand(f.user(),proposal.artifactId(),"confirm",proposal.commandHash(),1L)).hasMessageContaining("基准版本");
        assertThat(decide(f,proposal,"discard").status()).isEqualTo("REJECTED");
        assertThat(decide(f,proposal,"discard").applied()).isFalse();
        assertThatThrownBy(()->decide(f,proposal,"confirm")).hasMessageContaining("另一项决定");
        assertThat(planCount(f)).isZero();
    }

    @Test void lifecycleAndEditsUseExactCurrentVersionAndDoNotWriteUntilConfirmation() {
        var f=fixture();var plan=decide(f,propose(f),"confirm").result();
        for(String action:List.of("edit","pause","resume","archive")) {
            AutomationDefinition definition="edit".equals(action)?definition(f):null;
            assertThatThrownBy(()->service.proposeModelCommand(f.context(),action,plan.id(),null,definition)).hasMessageContaining("expected_version");
        }
        var pause=service.proposeModelCommand(f.context(),"pause",plan.id(),plan.version(),null);
        assertThat(service.get(f.user(),plan.id()).status()).isEqualTo("ACTIVE");
        service.lifecycle(f.user(),plan.id(),"pause",plan.version());
        assertThatThrownBy(()->decide(f,pause,"confirm")).hasMessageContaining("任务已更新");
        assertThat(service.modelCommands(f.user(),f.chapter(),f.conversation().getConversationUuid(),null,20).items())
                .filteredOn(item->item.artifactId().equals(pause.artifactId())).extracting(AutomationService.ModelCommandView::status).containsExactly("AWAITING_REVIEW");
    }

    @Test void authorCanConfirmEveryLifecycleActionAndExactEdit() {
        var f=fixture();var plan=decide(f,propose(f),"confirm").result();
        var edit=service.proposeModelCommand(f.context(),"edit",plan.id(),plan.version(),definition(f));
        plan=decide(f,edit,"confirm").result();
        for(String action:List.of("pause","resume","archive")) {
            var proposal=service.proposeModelCommand(f.context(),action,plan.id(),plan.version(),null);
            plan=decide(f,proposal,"confirm").result();
            assertThat(plan.status()).isEqualTo(switch(action){case "pause"->"PAUSED";case "resume"->"ACTIVE";default->"ARCHIVED";});
        }
        assertThat(planCount(f)).isEqualTo(1);
    }

    @Test void cancelledRunCannotProposeOrConfirmButCommittedReceiptSurvivesCancellation() {
        var before=fixture();var proposal=propose(before);
        agentRuns.cancel(before.conversation(),before.context().requestId(),"fixture cancellation");
        assertThatThrownBy(()->propose(before)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThatThrownBy(()->decide(before,proposal,"confirm")).hasMessageContaining("原运行已停止");
        assertThat(decide(before,proposal,"discard").status()).isEqualTo("REJECTED");assertThat(planCount(before)).isZero();
        var after=fixture();var accepted=propose(after);var committed=decide(after,accepted,"confirm");
        agentRuns.cancel(after.conversation(),after.context().requestId(),"fixture cancellation");
        assertThat(decide(after,accepted,"confirm").result()).isEqualTo(committed.result());
    }

    @Test void cancelledTransactionHoldsTheRootLockAndWinsBeforeAuthorConfirmation() throws Exception {
        var f=fixture();var proposal=propose(f);
        var cancelled=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var cancel=pool.submit(()->tx().executeWithoutResult(status->{
                agentRuns.cancel(f.conversation(),f.context().requestId(),"race fixture");cancelled.countDown();await(release);
            }));
            assertThat(cancelled.await(5,TimeUnit.SECONDS)).isTrue();
            var confirm=pool.submit(()->{try{decide(f,proposal,"confirm");return false;}catch(BusinessException conflict){return conflict.getMessage().contains("原运行已停止");}});
            release.countDown();cancel.get(15,TimeUnit.SECONDS);assertThat(confirm.get(15,TimeUnit.SECONDS)).isTrue();
        } finally {release.countDown();}
        assertThat(planCount(f)).isZero();
    }

    @Test void confirmationTransactionWinsBeforeConcurrentCancellationWithoutLosingTheReceipt() throws Exception {
        var f=fixture();var proposal=propose(f);var applied=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var confirm=pool.submit(()->tx().execute(status->{var receipt=decide(f,proposal,"confirm");applied.countDown();await(release);return receipt;}));
            assertThat(applied.await(5,TimeUnit.SECONDS)).isTrue();
            var cancel=pool.submit(()->agentRuns.cancel(f.conversation(),f.context().requestId(),"race fixture"));
            release.countDown();var receipt=confirm.get(15,TimeUnit.SECONDS);cancel.get(15,TimeUnit.SECONDS);
            assertThat(decide(f,proposal,"confirm").result()).isEqualTo(receipt.result());
        } finally {release.countDown();}
        assertThat(planCount(f)).isEqualTo(1);
    }

    @Test void sqlFailureRollsBackTheFormalPlanAndDecisionTogether() {
        var f=fixture();var proposal=propose(f);
        jdbc.execute("ALTER TABLE manga_agent_run_artifacts ADD CONSTRAINT it_reject_command_commit CHECK (artifact_type<>'AUTOMATION_COMMAND' OR status<>'COMMITTED') NOT VALID");
        try {assertThatThrownBy(()->decide(f,proposal,"confirm")).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally {jdbc.execute("ALTER TABLE manga_agent_run_artifacts DROP CONSTRAINT it_reject_command_commit");}
        assertThat(planCount(f)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM manga_agent_run_artifacts WHERE artifact_uuid=?",String.class,proposal.artifactId())).isEqualTo("DRAFT");
        assertThat(decide(f,proposal,"confirm").applied()).isTrue();
    }

    @Test void sourceProfileLostFenceLeaseAndSummaryStageRejectBeforeAnyArtifact() {
        var f=fixture();
        jdbc.update("UPDATE manga_agent_runs SET run_attributes_json='{}'::jsonb WHERE id=?",f.run());
        assertThatThrownBy(()->propose(f)).hasMessageContaining("原任务步骤");
        jdbc.update("UPDATE manga_agent_runs SET run_attributes_json=?::jsonb,fencing_token=fencing_token+1 WHERE id=?",execution(),f.run());
        assertThatThrownBy(()->propose(f)).hasMessageContaining("RUN_NOT_ACTIVE");
        jdbc.update("UPDATE manga_agent_runs SET fencing_token=?,lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.context().fencingToken(),f.run());
        assertThatThrownBy(()->propose(f)).hasMessageContaining("RUN_NOT_ACTIVE");
        jdbc.update("UPDATE manga_agent_runs SET lease_until=clock_timestamp()+interval '5 minutes',run_attributes_json=?::jsonb WHERE id=?",mapper.createObjectNode().put("review_summary_continuation",true).toString(),f.run());
        assertThatThrownBy(()->propose(f)).hasMessageContaining("审核总结");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manga_agent_run_artifacts WHERE run_id=?",Long.class,f.run())).isZero();
    }

    @Test void crossStoryTargetAndDefinitionCannotEscapeCurrentConversationScope() {
        var f=fixture();var other=fixture();var foreignPlan=decide(other,propose(other),"confirm").result();
        assertThatThrownBy(()->service.proposeModelCommand(f.context(),"pause",foreignPlan.id(),foreignPlan.version(),null)).hasMessageContaining("不存在");
        var raw=definition(f);var foreign=new AutomationDefinition(raw.name(),raw.taskType(),raw.executorKey(),raw.instruction(),raw.schedule(),
                mapper.createObjectNode().put("storyId",f.story()).put("chapterId",other.chapter()),raw.limits(),null,null,null,null,null,null);
        assertThatThrownBy(()->service.proposeModelCommand(f.context(),"create",null,null,foreign)).hasMessageContaining("当前作品");
        assertThat(planCount(f)).isZero();
    }

    @Test void expiredOrChangedInputProposalRequiresNewReviewAndPaginationIsActorBound() {
        var f=fixture();var proposal=propose(f);
        when(inputs.validateAndNormalize(eq(f.user()),any())).thenAnswer(invocation->{
            var d=(AutomationDefinition)invocation.getArgument(1);var payload=d.payload().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)payload).put("model","changed-model");
            return new AutomationDefinition(d.name(),d.taskType(),d.executorKey(),d.instruction(),d.schedule(),payload,d.limits(),d.misfirePolicy(),d.overlapPolicy(),d.maxLatenessSeconds(),null,d.origin(),d.sourceConversationId());
        });
        assertThatThrownBy(()->decide(f,proposal,"confirm")).hasMessageContaining("已变化");assertThat(planCount(f)).isZero();
        jdbc.update("UPDATE manga_agent_run_artifacts SET created_at=clock_timestamp()-interval '31 minutes' WHERE artifact_uuid=?",proposal.artifactId());
        assertThatThrownBy(()->decide(f,proposal,"confirm")).hasMessageContaining("已过期");
        var other=fixture();assertThat(service.modelCommands(other.user(),null,null,null,20).items()).isEmpty();
        assertThatThrownBy(()->service.modelCommands(other.user(),null,null,proposal.artifactId(),20)).hasMessageContaining("游标");
        assertThat(service.modelCommands(f.user(),f.chapter(),f.conversation().getConversationUuid(),null,1).items()).hasSize(1);
    }

    private AutomationService.ModelCommandView propose(Fixture f) { return service.proposeModelCommand(f.context(),"create",null,null,definition(f)); }
    private AutomationService.ModelCommandView decide(Fixture f,AutomationService.ModelCommandView p,String decision) {
        return service.decideModelCommand(f.user(),p.artifactId(),decision,p.commandHash(),p.expectedVersion());
    }
    private long planCount(Fixture f) { return jdbc.queryForObject("SELECT count(*) FROM automation WHERE user_id=?",Long.class,f.user()); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private static void await(CountDownLatch latch) {try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("race barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private String execution(){return "{\"story_chat_execution\":{\"profile\":\"local-tools-v1\",\"route\":\"READ\",\"task_type\":\"STORY_CHAT_READ\",\"step_id\":\"story-chat:READ\",\"version\":1}}";}
    private AutomationDefinition definition(Fixture f) {
        return new AutomationDefinition("Review fixture","AGENT","STORY_CHAT","Read fixture",
                new AutomationDefinition.Schedule("DAILY",null,"09:00","Asia/Shanghai",List.of()),
                mapper.createObjectNode().put("storyId",f.story()).put("chapterId",f.chapter()).put("modelConfigId",7).put("model","fixture-model"),
                new AutomationDefinition.Limits(1,8,600),null,null,null,null,null,null);
    }
    private Fixture fixture() {
        return tx().execute(status->{
            var user=new User();user.setUsername("command_"+UUID.randomUUID());user.setEmail(user.getUsername()+"@example.test");user.setPasswordHash("isolated-fixture");user.setRole(Role.USER);em.persist(user);
            var story=new Story();story.setTitle("Command fixture");story.setUser(user);em.persist(story);
            var chapter=new Chapter();chapter.setStory(story);chapter.setChapterNumber(1);chapter.setNovelContent("Fixture source");em.persist(chapter);
            var conversation=new MangaAgentConversation();conversation.setConversationUuid(UUID.randomUUID());conversation.setTitle("Command fixture");conversation.setConversationType(AiConversationType.STORY_CHAT);conversation.setUser(user);conversation.setStory(story);conversation.setChapter(chapter);em.persist(conversation);
            var run=new MangaAgentRun();run.setUser(user);run.setStory(story);run.setChapter(chapter);run.setConversation(conversation);run.setRunType(AgentRunType.STORY_CHAT);run.setRouteKey("READ");run.setInputMessage("Read fixture");run.setRunAttributesJson(execution());em.persist(run);em.flush();leases.claim(run);
            var context=new MangaAgentRuntimeContext(user.getId(),story.getId(),chapter.getId(),conversation.getConversationUuid(),run.getRequestId(),null,AgentTaskType.STORY_CHAT_READ,"story-chat:READ",run.getFencingToken(),null,run.getOwnerInstanceId());
            return new Fixture(user.getId(),story.getId(),chapter.getId(),run.getId(),conversation,context);
        });
    }
    private record Fixture(long user,long story,long chapter,long run,MangaAgentConversation conversation,MangaAgentRuntimeContext context){}
}
