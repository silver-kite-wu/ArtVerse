package com.artverse.application;

import com.artverse.agent.AgentTaskType;
import com.artverse.agent.*;
import com.artverse.agent.gateway.*;
import com.artverse.agent.gateway.tools.AgentToolConfigurationRegistry;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.application.tools.MangaHitlTools;
import com.artverse.application.tools.MangaToolSupport;
import com.artverse.application.tools.MangaContextTools;
import com.artverse.application.tools.MangaChapterContextReader;
import com.artverse.application.tools.MangaStoryboardTools;
import com.artverse.application.tools.StoryboardAgentTools;
import com.artverse.application.tools.StoryboardCommitAgentTools;
import com.artverse.application.tools.StoryChatContextTools;
import com.artverse.application.tools.StoryChatNovelContentTools;
import com.artverse.application.tools.AutomationReadAgentTools;
import com.artverse.application.tools.AutomationManageAgentTools;
import com.artverse.application.tools.StoryContextReadTools;
import com.artverse.application.tools.KnowledgeReadTools;
import com.artverse.application.tools.ReferenceAssetReadTools;
import com.artverse.application.tools.KnowledgeCandidateReadTools;
import com.artverse.application.tools.ArtifactReadAgentTools;
import com.artverse.application.tools.ArtifactDraftAgentTools;
import com.artverse.application.tools.NovelHistoryAgentTools;
import com.artverse.application.tools.SettingsDraftAgentTools;
import com.artverse.application.tools.KnowledgeDraftAgentTools;
import com.artverse.application.tools.ImageProposalAgentTools;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import io.agentscope.core.agent.RuntimeContext;
import com.artverse.config.ArtVerseProperties;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.ai.CozeClient;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.state.lettuce.LettuceClientAdapter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisClient;
import reactor.core.publisher.Flux;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StoryboardArtifactService.class, ChapterAccessService.class, StructuredStoryboardService.class,
        AgentRunLeaseService.class, MangaAgentRunService.class, MangaAgentRunSnapshotReader.class, AgentToolEffectService.class,
        MangaContextTools.class, MangaChapterContextReader.class, MangaToolSupport.class,
        StoryboardArtifactIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class StoryboardArtifactIntegrationTest {
    @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379).withImagePullPolicy(ignored -> false);
    @TempDir Path workspace;
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
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean CozeClient coze() { return mock(CozeClient.class); }
        @Bean AgentRunToolStatus statuses() { return mock(AgentRunToolStatus.class); }
        @Bean SceneService scenes(ChapterRepository chapters, CozeClient coze, ObjectMapper mapper) {
            return new SceneService(chapters, coze, mapper);
        }
    }
    @Autowired StoryboardArtifactService artifacts;
    @Autowired ChapterAccessService chapters;
    @Autowired AgentRunLeaseService leases;
    @Autowired MangaAgentRunService runs;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired CozeClient coze;
    @Autowired MangaContextTools chapterContext;
    @Autowired MangaChapterContextReader chapterReader;
    @Autowired MangaToolSupport toolScope;
    @Autowired AgentRunToolStatus toolStatuses;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void acceptedAnswerSurvivesFailedDeliveryClaimAndExpiredDeliveryCanBeReclaimed() {
        var f = questionFixture();
        var conversation = conversation(f);
        var pending = runs.recordExternalQuestion(f.context(), "durable-question", new AgentUserInputRequest(
                "Focus?", List.of(), true, "", "USER_QUESTION"));
        runs.markWaiting(conversation, f.context().requestId(), pending.request(), f.context().fencingToken());
        var accepted = runs.acceptAnswer(conversation, f.context().requestId(), "durable-question", 1L, "focus");
        tx().executeWithoutResult(tx -> {
            var stored = em.find(MangaAgentRun.class, f.runId());
            assertThat(stored.getStatus()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
            assertThat(stored.getOwnerInstanceId()).isNull();
            assertThat(stored.getRunAttributesJson()).contains("ACCEPTED", accepted.actionId().toString());
        });
        var rolledBack = tx().execute(tx -> {
            var claim = runs.claimAnswerDelivery(conversation, f.context().requestId(), accepted);
            assertThat(claim.claimed()).isTrue(); tx.setRollbackOnly(); return claim;
        });
        assertThat(rolledBack).isNotNull();
        tx().executeWithoutResult(tx -> assertThat(em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson())
                .contains("ACCEPTED").doesNotContain("DELIVERING"));
        var claimed = runs.claimAnswerDelivery(conversation, f.context().requestId(), accepted);
        assertThat(claimed.claimed()).isTrue();
        assertThat(runs.claimAnswerDelivery(conversation, f.context().requestId(), accepted).claimed()).isFalse();
        tx().executeWithoutResult(tx -> em.find(MangaAgentRun.class, f.runId()).setLeaseUntil(java.time.OffsetDateTime.now().minusMinutes(2)));
        var replacement = runs.claimAnswerDelivery(conversation, f.context().requestId(), accepted);
        assertThat(replacement.claimed()).isTrue();
        assertThat(replacement.delivery().actionId()).isEqualTo(claimed.delivery().actionId());
        assertThat(replacement.delivery().fencingToken()).isEqualTo(claimed.delivery().fencingToken() + 1);
        var request = deliveryRequest(f, replacement);
        assertThatThrownBy(() -> runs.requireAnswerDelivery(request, claimed.delivery())).hasMessageContaining("执行权或关联已变化");
    }

    @Test void realHarnessPersistsAnswerBeforeSqlEvidenceAndRecoveryDoesNotInjectItTwice() {
        var f = questionFixture();
        var conversation = conversation(f);
        var client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        var adapter = LettuceClientAdapter.of(client);
        var nativeStore = RedisAgentStateStore.builder().keyPrefix(AgentTurnStateStores.KEY_PREFIX).clientAdapter(adapter).build();
        var gate = new AgentSessionTurns(adapter);
        var stores = new AgentTurnStateStores(adapter, false);
        var registrations = productionToolRegistrations();
        var hitl = registrations.getBean(MangaHitlTools.class);
        var registry = registrations.getBean(AgentToolConfigurationRegistry.class);
        var budget = mock(AgentBudgetService.class);
        when(budget.reserveModelCall(any(), any(), anyString(), any())).thenReturn(true);
        when(budget.modelTimeAllowance(any(), any())).thenReturn(Duration.ofMinutes(3));
        when(budget.reserveToolCalls(any(), anyList())).thenReturn(true);
        when(budget.measureOutput(anyString())).thenReturn(new AgentBudgetService.OutputUsage(1, 1));
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().triggerMessages(2).build(), new ArtVerseProperties(),
                mock(AgentWorkspaceService.class), new MangaAgentPromptProvider(), StrictAgentStateStore.readOnly(nativeStore),
                stores, registry, mock(ArtVerseSkillRegistry.class), mock(ArtVerseSkillRepository.class), runs,
                mock(PostgresAgentWorkspaceStore.class), new AgentToolBoundaryMiddleware(runs, mock(AutomationAgentTools.class), budget));
        var factorySpy = spy(factory);
        var calls = new AtomicInteger();
        Model model = new Model() {
            @Override public String getModelName() { return "isolated-answer-delivery"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    int n = calls.getAndIncrement();
                    ContentBlock output;
                    if (n == 0) {
                        var input = Map.<String,Object>of("question", "Focus?", "options", List.of("A", "B"), "allow_free_text", true);
                        output = new ToolUseBlock("durable-question", "ask_user", input, writeJson(input), Map.of());
                    } else if (n == 1) {
                        assertThat(messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                                .filter(r -> "durable-question".equals(r.getId())).toList()).hasSize(1);
                        output = TextBlock.builder().text("Accepted answer consumed.").build();
                    } else throw new AssertionError("Recovery must not call model/compaction again");
                    return Flux.just(ChatResponse.builder().content(List.of(output))
                            .finishReason(output instanceof ToolUseBlock ? "tool_calls" : "stop").build());
                });
            }
        };
        doAnswer(invocation -> {
            AgentRunRequest request = invocation.getArgument(0);
            AgentSessionTurns.Lease turn = invocation.getArgument(1);
            var scoped = stores.forTurn(request, turn);
            HarnessAgent agent = ReflectionTestUtils.invokeMethod(factory, "buildAgent", request, workspace,
                    BusinessSkillSelection.empty(), model, scoped);
            if (request.variables().containsKey("answer_delivery")) {
                assertThat(agent.getCompactionHook()).isNull();
                assertThat(agent.getDelegate().getMiddlewares()).noneMatch(m -> m.getClass().getSimpleName().equals("ToolResultEvictionMiddleware"));
            }
            return agent;
        }).when(factorySpy).getOrCreate(any(), any(AgentSessionTurns.Lease.class));
        var initial = deliveryRequest(f, null);
        // Force a SQL failure after native state is durable; both final and error reconciliation must retain it.
        var failEvidence = new AtomicBoolean(true);
        var evidenceRuns = mock(MangaAgentRunService.class);
        doAnswer(invocation -> { runs.requireAnswerDelivery(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                .when(evidenceRuns).requireAnswerDelivery(any(), any());
        doAnswer(invocation -> {
            if (failEvidence.get()) throw new BusinessException(503, "isolated SQL evidence failure");
            runs.recordNativeAnswer(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(evidenceRuns).recordNativeAnswer(any(), any(), anyLong(), anyString());
        var gateway = new AgentScopeHarnessAgentGateway(factorySpy, new AgentScopeRuntimeContextFactory(), new ArtVerseProperties(),
                CircuitBreakerRegistry.ofDefaults(), budget, new AgentSessionHydrator(StrictAgentStateStore.readOnly(nativeStore)), gate);
        ReflectionTestUtils.setField(gateway, "hitlTools", hitl);
        ReflectionTestUtils.setField(gateway, "runService", evidenceRuns);
        gateway.init();
        try {
            gateway.streamEvents(initial).collectList().block(Duration.ofSeconds(20));
            var current = tx().execute(tx -> runs.waitingInput(em.find(MangaAgentRun.class, f.runId())));
            runs.markWaiting(conversation, f.context().requestId(), current, f.context().fencingToken());
            var accepted = runs.acceptAnswer(conversation, f.context().requestId(), "durable-question", 1L, "focus");
            try (var turn = gateway.acquireSessionTurn(initial)) {
                var claimed = runs.claimAnswerDelivery(conversation, f.context().requestId(), accepted);
                var request = deliveryRequest(f, claimed);
                assertThatThrownBy(() -> gateway.streamEvents(request, List.of(claimed.delivery().toolResult()), turn)
                        .collectList().block(Duration.ofSeconds(20))).hasMessageContaining("SQL evidence failure");
            }
            assertThat(calls).hasValue(2);
            tx().executeWithoutResult(tx -> {
                var row = em.find(MangaAgentRun.class, f.runId());
                assertThat(row.getRunAttributesJson()).contains("DELIVERING").doesNotContain("NATIVE_RECORDED");
                row.setLeaseUntil(java.time.OffsetDateTime.now().minusMinutes(2));
            });
            failEvidence.set(false);
            try (var turn = gateway.acquireSessionTurn(initial)) {
                var replacement = runs.claimAnswerDelivery(conversation, f.context().requestId(), accepted);
                var restored = deliveryRequest(f, replacement);
                var events = gateway.streamEvents(restored, List.of(replacement.delivery().toolResult()), turn)
                        .collectList().block(Duration.ofSeconds(20));
                assertThat(events).anyMatch(io.agentscope.core.event.AgentResultEvent.class::isInstance);
            }
            assertThat(calls).hasValue(2);
            tx().executeWithoutResult(tx -> assertThat(em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson())
                    .contains("NATIVE_RECORDED", "native_checkpoint_version"));
        } finally { nativeStore.close(); registrations.close(); }
    }

    private Fixture questionFixture() {
        var f = fixture(false);
        tx().executeWithoutResult(tx -> {
            var run = em.find(MangaAgentRun.class, f.runId());
            run.setRunType(AgentRunType.STORY_CHAT); run.setRouteKey("WRITE");
            run.setRunAttributesJson(writeJson(Map.of("story_chat_execution", Map.of(
                    "version", 1, "profile", "local-tools-v1", "route", "WRITE",
                    "task_type", AgentTaskType.STORY_CHAT_WRITE.name(), "step_id", "story-chat:WRITE"))));
        });
        var c = f.context();
        return new Fixture(new MangaAgentRuntimeContext(c.userId(), c.storyId(), c.chapterId(), c.conversationId(),
                c.requestId(), null, AgentTaskType.STORY_CHAT_WRITE, "story-chat:WRITE", c.fencingToken(), c.tenantId(), c.executionOwner()), f.runId(), f.baseVersion());
    }

    private MangaAgentConversation conversation(Fixture f) {
        return tx().execute(tx -> {
            var conversation = em.find(MangaAgentRun.class, f.runId()).getConversation();
            conversation.getConversationUuid(); return conversation;
        });
    }

    private AgentRunRequest deliveryRequest(Fixture f, MangaAgentRunService.AnswerContinuation claim) {
        var c = f.context();
        var variables = new LinkedHashMap<String,Object>();
        variables.put("step_id", c.stepId());
        variables.put("fencing_token", claim == null ? c.fencingToken() : claim.delivery().fencingToken());
        variables.put("execution_owner", c.executionOwner());
        if (claim != null) variables.put("answer_delivery", claim.delivery());
        return new AgentRunRequest(String.valueOf(c.userId()), c.storyId(), c.chapterId(), c.taskType(),
                List.of(new AgentMessage("user", "Isolated delivery fixture")), variables,
                new AgentModelSpec("fixture", "https://provider.example.test/v1", "fixture", "hash", null,
                        new AgentModelCapabilities(true, false, false, "v1")), "", c.requestId(), c.conversationId(), BusinessSkillSelection.empty());
    }

    private String writeJson(Object input) {
        try { return mapper.writeValueAsString(input); } catch (Exception error) { throw new IllegalStateException(error); }
    }

    @Test void concurrentAnswersClaimOneContinuationAndAdvanceTheFenceOnce() throws Exception {
        Fixture f = fixture(false);
        var conversation = tx().execute(tx -> {
            var run = em.find(MangaAgentRun.class, f.runId());
            run.setRunType(AgentRunType.STORY_CHAT); run.setRouteKey("READ");
            return run.getConversation();
        });
        var pending = runs.recordExternalQuestion(f.context(), "question-1", new AgentUserInputRequest(
                "What should the review focus on?", List.of(), true, "", "USER_QUESTION"));
        runs.markWaiting(conversation, f.context().requestId(), pending.request(), f.context().fencingToken());
        var ready = new java.util.concurrent.CountDownLatch(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) attempts.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
                try { return answer(conversation, f.context().requestId(), "question-1", 1L, "focus").claimed(); }
                catch (BusinessException conflict) { return false; }
            }));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); go.countDown();
            var results = new ArrayList<Boolean>();
            for (var result : attempts) results.add(result.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true, false);
        } finally { go.countDown(); }
        tx().executeWithoutResult(tx -> {
            var actual = em.find(MangaAgentRun.class, f.runId());
            assertThat(actual.getFencingToken()).isEqualTo(f.context().fencingToken() + 1);
            assertThat(actual.getRouteKey()).isEqualTo("READ");
            assertThat(actual.getStatus()).isEqualTo(MangaAgentRunStatus.RUNNING);
        });
        assertThat(answer(conversation, f.context().requestId(), "question-1", 1L, "focus").claimed()).isFalse();
        assertThatThrownBy(() -> answer(conversation, f.context().requestId(), "question-1", 1L, "different"))
                .isInstanceOf(BusinessException.class);
    }

    @Test void expiredLeaseCannotBeRevivedByTheRenewalSweep() {
        Fixture f = fixture(false);
        tx().executeWithoutResult(tx -> em.find(MangaAgentRun.class, f.runId())
                .setLeaseUntil(java.time.OffsetDateTime.now().minusMinutes(2)));
        leases.renewOwnedRuns();
        tx().executeWithoutResult(tx -> {
            var actual = em.find(MangaAgentRun.class, f.runId());
            assertThat(actual.getLeaseUntil()).isBefore(java.time.OffsetDateTime.now());
            assertThat(leases.owns(actual, f.context().fencingToken())).isFalse();
        });
    }

    @Test void consecutiveQuestionsAcceptTheSameTextWithoutConsumingTheNextPending() {
        Fixture f = fixture(false);
        var conversation = tx().execute(tx -> {
            var run = em.find(MangaAgentRun.class, f.runId()); run.setRunType(AgentRunType.STORY_CHAT);
            return run.getConversation();
        });
        var question = new AgentUserInputRequest("Focus?", List.of(), true, "", "USER_QUESTION");
        var first = runs.recordExternalQuestion(f.context(), "question-1", question);
        assertThat(first.created()).isTrue();
        assertThat(runs.recordExternalQuestion(f.context(), "question-1", question).created()).isFalse();
        runs.markWaiting(f.context().requestId(), f.context().userId(), f.context().chapterId(), first.request());
        var accepted = answer(conversation, f.context().requestId(), "question-1", 1L, "same text");
        assertThat(accepted.claimed()).isTrue();
        var c = f.context();
        var nextContext = new MangaAgentRuntimeContext(c.userId(), c.storyId(), c.chapterId(), c.conversationId(),
                c.requestId(), null, c.taskType(), c.stepId(), accepted.run().getFencingToken(), c.tenantId(), accepted.run().getOwnerInstanceId());
        var second = runs.recordExternalQuestion(nextContext, "question-2", question);
        assertThat(second.request().pendingVersion()).isEqualTo(2L);
        runs.markWaiting(conversation, c.requestId(), second.request(), nextContext.fencingToken());

        assertThat(answer(conversation, c.requestId(), "question-1", 1L, "same text").claimed()).isFalse();
        assertThatThrownBy(() -> runs.markWaiting(conversation, c.requestId(), first.request(), c.fencingToken()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> answer(conversation, c.requestId(), "question-1", 1L, "different"))
                .isInstanceOf(BusinessException.class);
        tx().executeWithoutResult(tx -> {
            var current = em.find(MangaAgentRun.class, f.runId());
            assertThat(current.getStatus()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
            assertThat(runs.waitingInput(current).toolCallId()).isEqualTo("question-2");
            assertThat(current.getFencingToken()).isEqualTo(nextContext.fencingToken());
        });
        assertThat(answer(conversation, c.requestId(), "question-2", 2L, "same text").claimed()).isTrue();
        runs.cancel(conversation, c.requestId(), "cancel fixture");
        assertThatThrownBy(() -> runs.markWaiting(conversation, c.requestId(), second.request(), nextContext.fencingToken()))
                .isInstanceOf(BusinessException.class);
    }

    @Test void overwriteAuthorizationIsBoundToTheExactArtifactAndReplaysOnlyOnce() {
        Fixture f = fixture(true);
        String ref = read(f.context());
        var first = artifacts.createStructuredDraft(pages("门前"), ref, f.context());
        var other = artifacts.createStructuredDraft(pages("窗边"), ref, f.context());
        var waiting = catchThrowableOfType(() -> artifacts.commit(first.artifactId(), f.context()),
                AgentUserInputRequiredException.class);
        assertThat(waiting.request().reason()).contains(first.artifactId().toString(), first.checksum());
        assertThat(visible(f.context()).getScenesText()).isEqualTo("[\"existing storyboard\"]");
        pause(f, waiting.request());
        artifacts.authorizeOverwrite(f.context().userId(), f.context().chapterId(), f.context().requestId(), f.context().conversationId());
        var resumed = resume(f);
        assertThatThrownBy(() -> artifacts.commit(other.artifactId(), resumed))
                .isInstanceOf(AgentUserInputRequiredException.class);
        var saved = artifacts.commit(first.artifactId(), resumed);
        assertThat(saved.status()).isEqualTo("COMMITTED");
        long after = visible(resumed).getVersion();
        assertThat(after).isEqualTo(f.baseVersion() + 1);
        assertThat(artifacts.commit(first.artifactId(), resumed).artifactId()).isEqualTo(first.artifactId());
        assertThat(visible(resumed).getVersion()).isEqualTo(after);
        assertThat(saved.evaluation()).containsKey("authorization_consumed_at").containsEntry("expert_review_status", "NOT_REVIEWED");
        verifyNoInteractions(coze);
    }

    @Test void firstCreationRequiresAnActualReadAndRejectsChangedBase() {
        Fixture f = fixture(false);
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), "missing", f.context())).hasMessageContaining("Read");
        String ref = read(f.context());
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent("new author text"));
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), ref, f.context())).hasMessageContaining("STALE_VERSION");
        String refreshed = read(f.context());
        var draft = artifacts.createStructuredDraft(pages("门前"), refreshed, f.context());
        assertThat(artifacts.commit(draft.artifactId(), f.context()).status()).isEqualTo("COMMITTED");
    }

    @Test void overviewIsTruncatedAndReadingBodyDoesNotCreateDeliveryEvidence() {
        Fixture f = fixture(false);
        String material = "不可重复存储的原文标记" + "甲".repeat(2500);
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent(material));
        var overview = contextPage(f.context(), MangaContextTools.Section.overview, null, null, null);
        assertThat(overview).containsEntry("source_length", material.length()).containsEntry("source_excerpt_truncated", true)
                .containsEntry("source_excerpt_total_length", material.length());
        assertThat(overview.get("source_excerpt").toString()).hasSize(1200);
        assertThat(overview).doesNotContainKeys("source_text", "data");
        String ref = overview.get("read_ref").toString();
        assertThat(visible(f.context()).getNovelContent()).isEqualTo(material);
        tx().executeWithoutResult(tx -> assertThat(em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson()).doesNotContain("storyboard_reads"));
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), ref, f.context())).hasMessageContaining("Read");
        artifacts.recordReadResult(f.context(), "overview-result", overview);
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), ref, f.context())).hasMessageContaining("INCOMPLETE_READ");
        tx().executeWithoutResult(tx -> assertThat(em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson())
                .contains(ref, "source_ranges", "overview-result").doesNotContain("不可重复存储的原文标记"));
    }

    @Test void sourcePagesAccumulateWithoutGapsAndBindTheExplicitDraftReference() {
        Fixture f = fixture(true);
        String material = "源".repeat(3000);
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent(material));
        var first = contextPage(f.context(), MangaContextTools.Section.source, null, 1000, null);
        String ref = first.get("read_ref").toString();
        var middle = contextPage(f.context(), MangaContextTools.Section.source, first.get("next_cursor").toString(), 1000, ref);
        var last = contextPage(f.context(), MangaContextTools.Section.source, middle.get("next_cursor").toString(), 1000, ref);
        var storyboard = contextPage(f.context(), MangaContextTools.Section.storyboard, null, null, ref);
        assertThat(first.get("source_text").toString() + middle.get("source_text") + last.get("source_text")).isEqualTo(material);
        handOffRead(f.context(), "source-first", first);
        handOffRead(f.context(), "source-last", last);
        handOffRead(f.context(), "storyboard-whole", storyboard);
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), ref, f.context())).hasMessageContaining("INCOMPLETE_READ");
        handOffRead(f.context(), "source-middle", middle);
        artifacts.recordReadResult(f.context(), "source-middle", middle); // Same successful event replay does not duplicate ranges.
        var draft = artifacts.createStructuredDraft(pages("门前"), ref, f.context());
        assertThat(draft.payload()).containsEntry("read_ref", ref).containsEntry("source_hash", first.get("source_hash"));
        var other = fixture(false);
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), ref, other.context())).hasMessageContaining("Read");
        var c = f.context();
        var anotherStep = new MangaAgentRuntimeContext(c.userId(), c.storyId(), c.chapterId(), c.conversationId(), c.requestId(),
                null, c.taskType(), "another-step", c.fencingToken(), c.tenantId(), c.executionOwner());
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), ref, anotherStep)).hasMessageContaining("STALE_VERSION");
        assertThatThrownBy(() -> contextPage(other.context(), MangaContextTools.Section.source, first.get("next_cursor").toString(), 1000, ref))
                .hasMessageContaining("STALE_VERSION");
    }

    @Test void staleAndForgedReadResultsCannotBeRegisteredAndRereadingDoesNotRewriteOldArtifactBase() {
        Fixture f = fixture(false);
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent("第一版来源"));
        var first = contextPage(f.context(), MangaContextTools.Section.source, null, null, null);
        String ref = first.get("read_ref").toString();
        var forged = new LinkedHashMap<>(first); forged.put("source_text", "模型伪造的原文");
        assertThatThrownBy(() -> artifacts.recordReadResult(f.context(), "forged", forged)).hasMessageContaining("SCOPE_DENIED");
        handOffRead(f.context(), "source-result", first);
        var draft = artifacts.createStructuredDraft(pages("门前"), ref, f.context());
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent("第二版来源"));
        assertThatThrownBy(() -> contextPage(f.context(), MangaContextTools.Section.source, null, null, ref)).hasMessageContaining("STALE_VERSION");
        assertThatThrownBy(() -> artifacts.recordReadResult(f.context(), "late-result", first)).hasMessageContaining("STALE_VERSION");
        String newRef = read(f.context());
        assertThat(newRef).isNotEqualTo(ref);
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("窗边"), ref, f.context())).hasMessageContaining("STALE_VERSION");
        assertThat(artifacts.list(f.context().userId(), f.context().chapterId(), f.context().requestId()).getFirst().payload()).containsEntry("read_ref", ref);
        assertThatThrownBy(() -> artifacts.commit(draft.artifactId(), f.context())).hasMessageContaining("STALE_VERSION");
    }

    @Test void imageSnapshotUsesIndependentEntityVersionsAndDoesNotReplaceChapterSourceReference() {
        Fixture f = fixture(false);
        Long imageId = tx().execute(tx -> {
            var image = new MangaImage(); image.setChapter(em.find(Chapter.class, f.context().chapterId()));
            image.setImageNumber(1); image.setImagePath("fixture-image-one"); em.persist(image); em.flush(); return image.getId();
        });
        var before = contextPage(f.context(), MangaContextTools.Section.overview, null, null, null);
        tx().executeWithoutResult(tx -> em.find(MangaImage.class, imageId).setImagePath("fixture-image-two"));
        var after = contextPage(f.context(), MangaContextTools.Section.overview, null, null, null);
        assertThat(after.get("chapter_version")).isEqualTo(before.get("chapter_version"));
        assertThat(after.get("read_ref")).isEqualTo(before.get("read_ref"));
        assertThat(after.get("images_collection_hash")).isNotEqualTo(before.get("images_collection_hash"));
        assertThat(after.get("generated_images").toString()).contains("image_version=1");
    }

    @Test void readPlanIsReadOnlyAndRequiresAnObservedReadEvenForAnEmptyChapter() {
        Fixture f = fixture(false);
        String before = tx().execute(tx -> em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson());
        var initial = artifacts.remainingReadPlan(f.context());
        assertThat(initial.remainingReadCalls()).isEqualTo(1);
        assertThat(initial.minimumToolCalls()).isEqualTo(3);
        assertThat(initial.minimumModelCalls()).isEqualTo(4);
        tx().executeWithoutResult(tx -> assertThat(em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson()).isEqualTo(before));
        handOffRead(f.context(), "empty-overview", contextPage(f.context(), MangaContextTools.Section.overview, null, null, null));
        var observed = artifacts.remainingReadPlan(f.context());
        assertThat(observed.readRef()).isEqualTo(initial.readRef());
        assertThat(observed.remainingReadCalls()).isZero();
        assertThat(observed.minimumToolCalls()).isEqualTo(2);
        assertThat(observed.minimumModelCalls()).isEqualTo(3);
    }

    @Test void readPlanUsesObservedOverviewRangesAndExactSurrogateSafePageBoundaries() {
        Fixture f = fixture(false);
        String source = "a".repeat(9199) + "😀" + "z".repeat(7999);
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent(source));
        handOffRead(f.context(), "plan-overview", contextPage(f.context(), MangaContextTools.Section.overview, null, null, null));
        handOffRead(f.context(), "issued-cursor-1200", contextPage(f.context(), MangaContextTools.Section.source, null, 1200, null));
        var plan = artifacts.remainingReadPlan(f.context());
        // The actually issued cursor at 1200 leaves 16000 characters, but surrogate-safe boundaries require three pages.
        assertThat(plan.remainingReadCalls()).isEqualTo(3);
        assertThat(plan.minimumToolCalls()).isEqualTo(5);
        assertThat(plan.minimumModelCalls()).isEqualTo(6);
        var first = contextPage(f.context(), MangaContextTools.Section.source, null, 8000, plan.readRef());
        var middle = contextPage(f.context(), MangaContextTools.Section.source, first.get("next_cursor").toString(), 8000, plan.readRef());
        var last = contextPage(f.context(), MangaContextTools.Section.source, middle.get("next_cursor").toString(), 8000, plan.readRef());
        handOffRead(f.context(), "plan-first", first);
        handOffRead(f.context(), "plan-last", last);
        assertThat(artifacts.remainingReadPlan(f.context()).remainingReadCalls()).isEqualTo(1);
        handOffRead(f.context(), "plan-middle", middle);
        assertThat(artifacts.remainingReadPlan(f.context()).remainingReadCalls()).isZero();
    }

    @Test void readPlanCannotStartAtOverviewEndUntilASourceResultActuallyIssuesThatCursor() {
        Fixture f = fixture(false);
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent("源".repeat(9200)));
        handOffRead(f.context(), "overview-only", contextPage(f.context(), MangaContextTools.Section.overview, null, null, null));
        assertThat(artifacts.remainingReadPlan(f.context()).remainingReadCalls()).isEqualTo(2);
        handOffRead(f.context(), "real-source-continuation", contextPage(f.context(), MangaContextTools.Section.source, null, 1200, null));
        assertThat(artifacts.remainingReadPlan(f.context()).remainingReadCalls()).isEqualTo(1);
    }

    @Test void formedReadResultNeedsTheExactFrozenNativeInputAndPreviewDoesNotCreateAHandoff() {
        Fixture f = fixture(false);
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent("完整来源"));
        var result = contextPage(f.context(), MangaContextTools.Section.source, null, null, null);
        artifacts.recordReadResult(f.context(), "formed-only", result);
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), result.get("read_ref").toString(), f.context()))
                .hasMessageContaining("INCOMPLETE_READ");
        String before = tx().execute(tx -> em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson());
        var inputs = List.of(new StoryboardArtifactService.ModelReadInput("formed-only", result));
        assertThat(artifacts.previewModelReadPlan(f.context(), inputs).remainingReadCalls()).isZero();
        tx().executeWithoutResult(tx -> assertThat(em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson())
                .isEqualTo(before).contains("RESULT_FORMED").doesNotContain("HANDED_TO_MODEL_ADAPTER"));
        var altered = new LinkedHashMap<>(result); altered.put("source_text", "伪造的冻结输入");
        assertThatThrownBy(() -> artifacts.previewModelReadPlan(f.context(), List.of(
                new StoryboardArtifactService.ModelReadInput("formed-only", altered)))).hasMessageContaining("READ_RECEIPT_UNAVAILABLE");
        assertThatThrownBy(() -> artifacts.previewModelReadPlan(f.context(), List.of(
                new StoryboardArtifactService.ModelReadInput("foreign-call", result)))).hasMessageContaining("READ_RECEIPT_UNAVAILABLE");
        UUID attempt = UUID.randomUUID();
        artifacts.recordModelReadInput(f.context(), attempt, "a".repeat(64), inputs);
        artifacts.recordModelReadInput(f.context(), attempt, "a".repeat(64), inputs);
        assertThatThrownBy(() -> artifacts.recordModelReadInput(f.context(), attempt, "b".repeat(64), inputs))
                .hasMessageContaining("SCOPE_DENIED");
        assertThat(artifacts.createStructuredDraft(pages("门前"), result.get("read_ref").toString(), f.context()).status()).isEqualTo("VALIDATED");
        tx().executeWithoutResult(tx -> assertThat(em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson())
                .contains("HANDED_TO_MODEL_ADAPTER", attempt.toString(), "input_hash").doesNotContain("完整来源"));
    }

    @Test void readPlanReusesOnlyTheCurrentDraftAndCountsExpiredOverwriteAuthorization() {
        Fixture f = fixture(true);
        var draft = artifacts.createStructuredDraft(pages("门前"), read(f.context()), f.context());
        var waiting = catchThrowableOfType(() -> artifacts.commit(draft.artifactId(), f.context()), AgentUserInputRequiredException.class);
        var unapproved = artifacts.remainingReadPlan(f.context());
        assertThat(unapproved.reusableValidatedDraft()).isTrue();
        assertThat(unapproved.overwriteConfirmationRequired()).isTrue();
        assertThat(unapproved.minimumToolCalls()).isEqualTo(2);
        pause(f, waiting.request());
        artifacts.authorizeOverwrite(f.context().userId(), f.context().chapterId(), f.context().requestId(), f.context().conversationId());
        var current = resume(f);
        var approved = artifacts.remainingReadPlan(current);
        assertThat(approved.minimumToolCalls()).isEqualTo(1);
        assertThat(approved.minimumModelCalls()).isEqualTo(2);
        assertThat(approved.overwriteConfirmationRequired()).isFalse();
        tx().executeWithoutResult(tx -> {
            var stored = em.createQuery("select a from MangaAgentRunArtifact a where a.artifactUuid = :id", MangaAgentRunArtifact.class)
                    .setParameter("id", draft.artifactId()).getSingleResult();
            try {
                var evaluation = mapper.readValue(stored.getEvaluation(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
                @SuppressWarnings("unchecked") var authorization = (Map<String,Object>) evaluation.get("overwrite_authorization");
                authorization.put("expires_at", java.time.OffsetDateTime.now().minusMinutes(1).toString());
                stored.setEvaluation(writeJson(evaluation));
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        });
        assertThat(artifacts.remainingReadPlan(current).overwriteConfirmationRequired()).isTrue();
        tx().executeWithoutResult(tx -> em.find(Chapter.class, current.chapterId()).setNovelContent("new author source"));
        var changed = artifacts.remainingReadPlan(current);
        assertThat(changed.reusableValidatedDraft()).isFalse();
        assertThat(changed.readRef()).isNotEqualTo(approved.readRef());
        assertThat(changed.remainingReadCalls()).isEqualTo(2);
    }

    @Test void readPlanNeedsOnlyTheFinalModelAfterThisStepsCommitAndRejectsInactiveScope() {
        Fixture f = fixture(false);
        var draft = artifacts.createStructuredDraft(pages("门前"), read(f.context()), f.context());
        artifacts.commit(draft.artifactId(), f.context());
        var plan = artifacts.remainingReadPlan(f.context());
        assertThat(plan.committedForStep()).isTrue();
        assertThat(plan.minimumToolCalls()).isZero();
        assertThat(plan.minimumModelCalls()).isEqualTo(1);
        tx().executeWithoutResult(tx -> em.find(MangaAgentRun.class, f.runId()).setStatus(MangaAgentRunStatus.CANCELLED));
        assertThatThrownBy(() -> artifacts.remainingReadPlan(f.context())).hasMessageContaining("RUN_NOT_ACTIVE");
    }

    @Test void nativeHarnessReadsAllPagesThenDraftsUsingObservedReadReference() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(chapterContext)).isFalse();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(chapterReader)).isTrue();
        Fixture f = fixture(false);
        String material = "完整原生分页材料" + "乙".repeat(9000);
        var workflow = tx().execute(tx -> {
            var run = em.find(MangaAgentRun.class, f.runId());
            run.setRoute(MangaWorkflowRoute.STORYBOARD); run.setRouteKey("STORYBOARD");
            run.getChapter().setNovelContent(material);
            run.getConversation().getConversationUuid();
            return new MangaWorkflowExecutionContext(run.getConversation(), run.getInputMessage(), run.getRequestId(),
                    "", null, null, run.getUser(), run.getChapter(), null, false, f.context().stepId(),
                    MangaWorkflowExecutionContext.ExecutionClaim.from(run));
        });
        runs.bindMangaExecution(workflow, AgentTaskType.MANGA_STORYBOARD);
        var budget = new AgentBudgetService(jdbc, transactions, new ArtVerseProperties());
        var registrations = productionToolRegistrations();
        var registry = registrations.getBean(AgentToolConfigurationRegistry.class);
        var boundary = new AgentToolBoundaryMiddleware(runs, mock(AutomationAgentTools.class), budget);
        boundary.setStoryboardArtifacts(artifacts);
        var request = new AgentRunRequest(String.valueOf(f.context().userId()), f.context().storyId(), f.context().chapterId(),
                AgentTaskType.MANGA_STORYBOARD, List.of(new AgentMessage("user", "Prepare a complete storyboard")),
                Map.of("step_id", f.context().stepId(), "fencing_token", f.context().fencingToken(), "execution_owner", f.context().executionOwner()), new AgentModelSpec("fixture", "https://fixture.invalid/v1", "fixture", "hash", null, new AgentModelCapabilities(true, false, false, "fixture-v1")), "",
                f.context().requestId(), f.context().conversationId(), BusinessSkillSelection.empty());
        var rounds = new AtomicInteger();
        Model scripted = new Model() {
            @Override public String getModelName() { return "native-storyboard-read-fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    assertThat(tools).extracting(ToolSchema::getName).containsExactlyInAnyOrder(
                            "get_chapter_context", "draft_structured_storyboard", "commit_storyboard", "ask_user",
                            "get_story_context", "list_chapters", "get_artifact", "list_artifacts", "discard_artifact");
                    assertThat(tools.stream().filter(tool -> "draft_structured_storyboard".equals(tool.getName()))
                            .findFirst().orElseThrow().getParameters().get("required")).asList().contains("read_ref");
                    ContentBlock block = switch (rounds.getAndIncrement()) {
                        case 0 -> nativeCall("overview", "get_chapter_context", Map.of());
                        case 1 -> {
                            var overview = nativeResult(messages, "overview");
                            assertThat(overview.get("source_excerpt_truncated")).isEqualTo(true);
                            yield nativeCall("source-first", "get_chapter_context", Map.of("section", "source", "read_ref", overview.get("read_ref")));
                        }
                        case 2 -> {
                            var first = nativeResult(messages, "source-first");
                            tx().executeWithoutResult(tx -> assertThat(em.find(MangaAgentRun.class, f.runId()).getRunAttributesJson())
                                    .contains("source-first", "source_ranges").doesNotContain("完整原生分页材料"));
                            assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("门前"), first.get("read_ref").toString(), f.context()))
                                    .hasMessageContaining("INCOMPLETE_READ");
                            yield nativeCall("source-last", "get_chapter_context", Map.of("section", "source", "read_ref", first.get("read_ref"), "cursor", first.get("next_cursor")));
                        }
                        case 3 -> {
                            var last = nativeResult(messages, "source-last");
                            assertThat(last.get("next_cursor")).isEqualTo("");
                            yield nativeCall("storyboard-empty", "get_chapter_context", Map.of("section", "storyboard", "read_ref", last.get("read_ref")));
                        }
                        case 4 -> nativeCall("draft", "draft_structured_storyboard", Map.of("pages", pages("门前"), "read_ref", nativeResult(messages, "source-last").get("read_ref")));
                        case 5 -> {
                            assertThat(nativeResult(messages, "draft").get("validated")).isEqualTo(true);
                            yield nativeCall("commit", "commit_storyboard", Map.of("artifact_id", nativeResult(messages, "draft").get("artifact_id")));
                        }
                        case 6 -> {
                            assertThat(nativeResult(messages, "commit").get("status")).isEqualTo("COMMITTED");
                            yield TextBlock.builder().text("Complete source read; storyboard draft created and saved.").build();
                        }
                        default -> throw new AssertionError("Unexpected native round");
                    };
                    return Flux.just(ChatResponse.builder().content(List.of(block)).finishReason(block instanceof ToolUseBlock ? "tool_calls" : "stop").build());
                });
            }
        };
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().build(), new ArtVerseProperties(),
                mock(AgentWorkspaceService.class), new MangaAgentPromptProvider(),
                new StrictAgentStateStore(new io.agentscope.core.state.InMemoryAgentStateStore()), mock(AgentTurnStateStores.class),
                registry, mock(ArtVerseSkillRegistry.class), mock(ArtVerseSkillRepository.class), runs,
                mock(PostgresAgentWorkspaceStore.class), boundary);
        HarnessAgent agent = ReflectionTestUtils.invokeMethod(factory, "buildAgent", request, workspace, BusinessSkillSelection.empty(), scripted);
        try {
            assertThat(agent.call(List.of(Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("Read then draft.").build()).build()),
                    new AgentScopeRuntimeContextFactory().create(request)).block(Duration.ofSeconds(30)).getTextContent()).contains("draft created");
            assertThat(rounds).hasValue(7);
            assertThat(artifacts.list(f.context().userId(), f.context().chapterId(), f.context().requestId())).singleElement()
                    .satisfies(value -> assertThat(value.status()).isEqualTo("COMMITTED"));
            assertThat(visible(f.context()).getScenesText()).isNotBlank();
            var receipts = jdbc.queryForList("SELECT metadata->'domain_effect' AS receipt FROM agent_usage_ledger WHERE request_id=? AND metadata->'domain_effect' IS NOT NULL ORDER BY created_at", f.context().requestId());
            assertThat(receipts).hasSize(2);
            assertThat(receipts.toString()).contains("draft_structured_storyboard", "commit_storyboard", "COMMITTED");
        } finally { agent.close(); registrations.close(); }
    }

    /** Use the same Spring registry, strategy, groups and guarded delegates as the application. */
    private AnnotationConfigApplicationContext productionToolRegistrations() {
        var registrations = new AnnotationConfigApplicationContext();
        registrations.registerBean(ObjectMapper.class, () -> mapper);
        registrations.registerBean(com.artverse.application.automation.AutomationReadService.class,
                () -> mock(com.artverse.application.automation.AutomationReadService.class));
        var audit = new AgentToolAuditService(toolStatuses);
        var storyboard = new MangaStoryboardTools(audit, toolScope, artifacts);
        var automation = mock(AutomationAgentTools.class);
        var readService = mock(AgentStoryKnowledgeReadService.class);
        var artifactAccess = mock(AgentArtifactAccessService.class);
        var novelHistory = mock(NovelHistoryAgentService.class);
        registrations.registerBean(NovelModelReadService.class, () -> mock(NovelModelReadService.class));
        registrations.registerBean(NovelHistoryAgentService.class, () -> novelHistory);
        registrations.registerBean(NativeNovelDraftService.class, () -> mock(NativeNovelDraftService.class));
        registrations.registerBean(MangaContextTools.class, () -> chapterContext);
        registrations.registerBean(MangaHitlTools.class, () -> new MangaHitlTools(audit, toolScope, runs));
        registrations.registerBean(StoryChatContextTools.class, () -> new StoryChatContextTools(toolScope, audit, chapters));
        registrations.registerBean(StoryboardAgentTools.class, () -> new StoryboardAgentTools(storyboard, mapper));
        registrations.registerBean(StoryboardCommitAgentTools.class, () -> new StoryboardCommitAgentTools(storyboard));
        registrations.registerBean(StoryChatNovelContentTools.class,
                () -> new StoryChatNovelContentTools(toolScope, audit, mock(NovelContentArtifactService.class)));
        registrations.registerBean(AutomationReadAgentTools.class, () -> new AutomationReadAgentTools(automation, toolScope));
        registrations.registerBean(AutomationManageAgentTools.class,
                () -> new AutomationManageAgentTools(automation, toolScope, mapper, audit));
        registrations.registerBean(StoryContextReadTools.class, () -> new StoryContextReadTools(readService, toolScope));
        registrations.registerBean(KnowledgeReadTools.class, () -> new KnowledgeReadTools(readService, toolScope));
        registrations.registerBean(ReferenceAssetReadTools.class, () -> new ReferenceAssetReadTools(readService, toolScope));
        registrations.registerBean(KnowledgeCandidateReadTools.class, () -> new KnowledgeCandidateReadTools(readService, toolScope));
        registrations.registerBean(ArtifactReadAgentTools.class, () -> new ArtifactReadAgentTools(toolScope, audit, artifactAccess));
        registrations.registerBean(ArtifactDraftAgentTools.class, () -> new ArtifactDraftAgentTools(toolScope, audit, artifactAccess));
        registrations.registerBean(NovelHistoryAgentTools.class, () -> new NovelHistoryAgentTools(toolScope, audit, novelHistory));
        registrations.registerBean(SettingsDraftAgentTools.class,
                () -> new SettingsDraftAgentTools(toolScope, audit, mock(SettingsProposalService.class)));
        registrations.registerBean(KnowledgeDraftAgentTools.class,
                () -> new KnowledgeDraftAgentTools(toolScope, audit, mock(KnowledgeProposalService.class)));
        registrations.registerBean(ImageProposalAgentTools.class,
                () -> new ImageProposalAgentTools(toolScope, audit,
                        mock(com.artverse.application.imageproposal.ImageProposalService.class)));
        var scanner = new org.springframework.context.annotation.ClassPathBeanDefinitionScanner(registrations);
        scanner.addExcludeFilter((metadata, factory) -> metadata.getClassMetadata().getClassName().contains("Test$"));
        scanner.scan(AgentToolConfigurationRegistry.class.getPackageName());
        registrations.refresh();
        return registrations;
    }

    private Map<String,Object> contextPage(MangaAgentRuntimeContext context, MangaContextTools.Section section, String cursor, Integer limit, String ref) {
        return chapterContext.getChapterContext(section, cursor, limit, ref,
                RuntimeContext.builder().put(MangaAgentRuntimeContext.class, context).build());
    }
    private ToolUseBlock nativeCall(String id, String tool, Map<String,Object> input) {
        return new ToolUseBlock(id, tool, input, writeJson(input), Map.of());
    }
    private Map<String,Object> nativeResult(List<Msg> messages, String id) {
        String output = messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> id.equals(result.getId())).flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat);
        try { return mapper.readValue(output, new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
        catch (Exception error) { throw new AssertionError("Missing native tool JSON: " + id, error); }
    }

    @Test void cancellationOldFenceAndAnotherRunCannotCommitADraft() {
        Fixture f = fixture(false);
        String ref = read(f.context());
        var draft = artifacts.createStructuredDraft(pages("门前"), ref, f.context());
        var other = fixture(false);
        assertThatThrownBy(() -> artifacts.commit(draft.artifactId(), other.context()))
                .isInstanceOf(BusinessException.class);
        var refreshed = resume(f);
        assertThatThrownBy(() -> artifacts.commit(draft.artifactId(), f.context())).hasMessageContaining("RUN_NOT_ACTIVE");
        tx().executeWithoutResult(tx -> em.find(MangaAgentRun.class, f.runId()).setStatus(MangaAgentRunStatus.CANCELLED));
        assertThatThrownBy(() -> artifacts.commit(draft.artifactId(), refreshed)).hasMessageContaining("RUN_NOT_ACTIVE");
        assertThat(visible(refreshed).getScenesText()).isNull();
    }

    @Test void parentCancellationWinningTheLockPreventsTheRealStoryboardCommit() throws Exception {
        var f = fixture(false);
        var context = attachAutomationParent(f);
        var draft = artifacts.createStructuredDraft(pages("门前"), read(context), context);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var cancellation = pool.submit(() -> tx().executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM automation_run WHERE id=? FOR UPDATE", UUID.class, context.automationClaim().runId());
                locked.countDown();
                await(release);
                jdbc.update("UPDATE automation_run SET state='CANCEL_REQUESTED' WHERE id=?", context.automationClaim().runId());
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var committing = pool.submit(() -> artifacts.commit(draft.artifactId(), context));
            try {
                assertThatThrownBy(() -> committing.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            cancellation.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> committing.get(15, java.util.concurrent.TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(BusinessException.class)
                    .hasRootCauseMessage("后台原执行权已停止或过期");
        } finally { release.countDown(); }
        assertThat(visible(context).getScenesText()).isNull();
        assertThat(artifacts.list(context.userId(), context.chapterId(), context.requestId())).singleElement()
                .satisfies(saved -> assertThat(saved.status()).isEqualTo("VALIDATED"));
    }

    @Test void realStoryboardCommitWinningTheParentLockKeepsItsReceiptAfterCancellation() throws Exception {
        var f = fixture(false);
        var context = attachAutomationParent(f);
        var draft = artifacts.createStructuredDraft(pages("门前"), read(context), context);
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var committing = pool.submit(() -> tx().execute(tx -> {
                var saved = artifacts.commit(draft.artifactId(), context);
                written.countDown();
                await(release);
                return saved;
            }));
            assertThat(written.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var cancellation = pool.submit(() -> jdbc.update("UPDATE automation_run SET state='CANCEL_REQUESTED' WHERE id=?",
                    context.automationClaim().runId()));
            try {
                assertThatThrownBy(() -> cancellation.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            assertThat(committing.get(15, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("COMMITTED");
            assertThat(cancellation.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        } finally { release.countDown(); }
        assertThat(visible(context).getScenesText()).contains("门前");
        assertThat(artifacts.list(context.userId(), context.chapterId(), context.requestId())).singleElement()
                .satisfies(saved -> assertThat(saved.status()).isEqualTo("COMMITTED"));
        assertThatThrownBy(() -> artifacts.createStructuredDraft(pages("窗边"), draft.payload().get("read_ref").toString(), context))
                .hasMessageContaining("后台原执行权已停止或过期");
    }

    @Test void authorCanApproveTheExactStoryboardWhileItsParentWaitsButNotAfterCancellation() {
        var f = fixture(true);
        var context = attachAutomationParent(f);
        var draft = artifacts.createStructuredDraft(pages("门前"), read(context), context);
        var waiting = catchThrowableOfType(() -> artifacts.commit(draft.artifactId(), context), AgentUserInputRequiredException.class);
        pause(f, waiting.request());
        jdbc.update("UPDATE automation_run SET state='WAITING_USER',lease_owner=NULL,lease_until=NULL WHERE id=?",
                context.automationClaim().runId());
        artifacts.authorizeOverwrite(context.userId(), context.chapterId(), context.requestId(), context.conversationId());
        assertThat(artifacts.list(context.userId(), context.chapterId(), context.requestId())).singleElement()
                .satisfies(saved -> assertThat(saved.evaluation()).containsKey("overwrite_authorization"));
        jdbc.update("UPDATE automation_run SET state='CANCEL_REQUESTED' WHERE id=?", context.automationClaim().runId());
        assertThatThrownBy(() -> artifacts.authorizeOverwrite(context.userId(), context.chapterId(), context.requestId(), context.conversationId()))
                .hasMessageContaining("后台运行已停止");
    }

    private MangaAgentRuntimeContext attachAutomationParent(Fixture fixture) {
        var c = fixture.context();
        UUID plan = UUID.randomUUID(), parent = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,idempotency_key,request_hash)
                VALUES (?,?,'Storyboard parent fixture','AGENT','MANGA_AGENT','{}'::jsonb,'Asia/Shanghai',?,'fixture')
                """, plan, c.userId(), plan.toString());
        jdbc.update("""
                INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,
                    definition_snapshot,state,lease_owner,lease_until,fencing_token)
                VALUES (?,?,?,0,'MANUAL',clock_timestamp(),?,'{}'::jsonb,'RUNNING','storyboard-parent',clock_timestamp()+interval '5 minutes',1)
                """, parent, plan, c.userId(), parent.toString());
        jdbc.update("""
                INSERT INTO automation_run_execution(run_id,target_key,execution_kind,agent_run_id,conversation_uuid,request_id)
                VALUES (?,'storyboard','MANGA_AGENT',?,?,?)
                """, parent, fixture.runId(), c.conversationId(), c.requestId());
        var parentClaim = new AutomationExecutionClaim(parent, "storyboard-parent", 1L);
        tx().executeWithoutResult(tx -> {
            var run = em.find(MangaAgentRun.class, fixture.runId());
            runs.bindAutomationExecutionClaim(run.getConversation(), c.requestId(),
                    new MangaWorkflowExecutionContext.ExecutionClaim(c.executionOwner(), c.fencingToken()), parentClaim);
        });
        return new MangaAgentRuntimeContext(c.userId(), c.storyId(), c.chapterId(), c.conversationId(), c.requestId(),
                c.workflowConfig(), c.taskType(), c.stepId(), c.fencingToken(), c.tenantId(), c.executionOwner(), parentClaim);
    }

    private static void await(java.util.concurrent.CountDownLatch barrier) {
        try {
            if (!barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    @Test void confirmationCannotApproveAChangedChapterOrAHashFromModelText() {
        Fixture f = fixture(true);
        String ref = read(f.context());
        var draft = artifacts.createStructuredDraft(pages("门前"), ref, f.context());
        var wait = catchThrowableOfType(() -> artifacts.commit(draft.artifactId(), f.context()), AgentUserInputRequiredException.class);
        pause(f, new AgentUserInputRequest(wait.request().question(), wait.request().options(), false,
                "storyboard-overwrite:" + draft.artifactId() + ":forged", "MUTATION_CONFIRMATION"));
        assertThatThrownBy(() -> artifacts.authorizeOverwrite(f.context().userId(), f.context().chapterId(),
                f.context().requestId(), f.context().conversationId())).hasMessageContaining("SCOPE_DENIED");
        pause(f, wait.request());
        tx().executeWithoutResult(tx -> em.find(Chapter.class, f.context().chapterId()).setNovelContent("author edit"));
        assertThatThrownBy(() -> artifacts.authorizeOverwrite(f.context().userId(), f.context().chapterId(),
                f.context().requestId(), f.context().conversationId())).hasMessageContaining("STALE_VERSION");
    }

    private Fixture fixture(boolean existing) {
        return tx().execute(tx -> {
            var user = new User(); user.setUsername("sb_" + UUID.randomUUID());
            user.setEmail(user.getUsername() + "@example.test"); user.setPasswordHash("isolated-fixture"); em.persist(user);
            var story = new Story(); story.setTitle("Storyboard fixture"); story.setUser(user); em.persist(story);
            var chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1); chapter.setImageCount(4);
            if (existing) chapter.setScenesText("[\"existing storyboard\"]");
            em.persist(chapter);
            var conversation = new MangaAgentConversation(); conversation.setTitle("Storyboard fixture");
            conversation.setUser(user); conversation.setStory(story); conversation.setChapter(chapter); em.persist(conversation);
            var run = new MangaAgentRun(); run.setUser(user); run.setStory(story); run.setChapter(chapter);
            run.setConversation(conversation); run.setInputMessage("Prepare a storyboard"); em.persist(run); em.flush();
            leases.claim(run);
            return new Fixture(new MangaAgentRuntimeContext(user.getId(), story.getId(), chapter.getId(),
                    conversation.getConversationUuid(), run.getRequestId(), null,
                    AgentTaskType.MANGA_STORYBOARD, "manga-storyboard", run.getFencingToken(), run.getTenantId(), run.getOwnerInstanceId()), run.getId(), chapter.getVersion());
        });
    }
    private String read(MangaAgentRuntimeContext ctx) {
        Chapter chapter = visible(ctx);
        String source = chapter.novelContentOrJoinedMessages(), storyboard = Objects.toString(chapter.getScenesText(), "");
        var sourceReceipt = artifacts.readReceipt(ctx, chapter, "source", 0, source.length(), source);
        handOffRead(ctx, UUID.randomUUID().toString(), Map.of("source_text", source, "read_receipt", sourceReceipt));
        handOffRead(ctx, UUID.randomUUID().toString(), Map.of("storyboard_text", storyboard, "read_receipt",
                artifacts.readReceipt(ctx, chapter, "storyboard", 0, storyboard.length(), storyboard)));
        return sourceReceipt.get("read_ref").toString();
    }
    private void handOffRead(MangaAgentRuntimeContext context, String callId, Map<String,Object> result) {
        artifacts.recordReadResult(context, callId, result);
        artifacts.recordModelReadInput(context, UUID.randomUUID(), "a".repeat(64),
                List.of(new StoryboardArtifactService.ModelReadInput(callId, result)));
    }
    private Chapter visible(MangaAgentRuntimeContext ctx) { return chapters.requireVisible(ctx.chapterId(), ctx.userId()); }
    private void pause(Fixture f, AgentUserInputRequest question) {
        tx().executeWithoutResult(tx -> {
            var run = em.find(MangaAgentRun.class, f.runId()); run.setStatus(MangaAgentRunStatus.WAITING_USER);
            run.setOwnerInstanceId(null); run.setLeaseUntil(null);
            try { run.setUserInputRequestJson(mapper.writeValueAsString(question)); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
    }
    private MangaAgentRuntimeContext resume(Fixture f) {
        return tx().execute(tx -> {
            var run = em.find(MangaAgentRun.class, f.runId()); run.setStatus(MangaAgentRunStatus.RUNNING);
            run.setLeaseUntil(java.time.OffsetDateTime.now().minusMinutes(2)); em.flush();
            leases.claim(run);
            var c = f.context();
            return new MangaAgentRuntimeContext(c.userId(), c.storyId(), c.chapterId(), c.conversationId(), c.requestId(),
                    null, c.taskType(), c.stepId(), run.getFencingToken(), run.getTenantId(), run.getOwnerInstanceId());
        });
    }
    private List<Map<String,Object>> pages(String place) {
        var panels = IntStream.range(0,4).mapToObj(i -> Map.of("description", "少年站在" + place + "观察街道，雨水沿屋檐落下。")).toList();
        return IntStream.range(0,4).mapToObj(page -> Map.<String,Object>of("panels", panels)).toList();
    }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private MangaAgentRunService.AnswerContinuation answer(MangaAgentConversation conversation, UUID request,
                                                           String call, Long version, String text) {
        var accepted = runs.acceptAnswer(conversation, request, call, version, text);
        return runs.claimAnswerDelivery(conversation, request, accepted);
    }
    private record Fixture(MangaAgentRuntimeContext context, Long runId, long baseVersion) {}
}
