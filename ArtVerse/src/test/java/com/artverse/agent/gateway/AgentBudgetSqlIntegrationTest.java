package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.agent.gateway.tools.AgentToolConfigurationRegistry;
import com.artverse.application.*;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.*;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real migrations/transactions and native Model/Harness. Never uses the user's database or a provider. */
@Testcontainers(disabledWithoutDocker = true)
class AgentBudgetSqlIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;
    ArtVerseProperties properties;
    AgentBudgetService budget;
    long user;
    @TempDir Path workspace;

    @BeforeAll static void migrate() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl() + "?stringtype=unspecified", postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach void fixture() {
        jdbc = new JdbcTemplate(dataSource);
        properties = new ArtVerseProperties();
        budget = service();
        String name = "budget_" + UUID.randomUUID();
        user = jdbc.queryForObject("INSERT INTO users(username,email,password_hash,role) VALUES (?,?,?,'USER') RETURNING id",
                Long.class, name, name + "@example.test", "isolated-fixture");
    }

    @Test void twoWorkersCompetingForTheLastSlotDispatchExactlyOneModel() throws Exception {
        properties.getAgent().setTextMaxModelCalls(1);
        var request = request(AgentTaskType.CHAT);
        var calls = new AtomicInteger();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
                var model = new BudgetedAgentModel(textModel(calls), request, service(), properties);
                ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                try { model.stream(List.of(message("fixture")), List.of(), null).blockLast(Duration.ofSeconds(10)); return true; }
                catch (AgentBudgetExceededException exhausted) { return false; }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(calls).hasValue(1);
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
        assertThat(count(request, "BUDGET_ROOT")).isEqualTo(1);
    }

    @Test void sameAttemptCannotDispatchAgainAndUnknownRemainsChargedAfterReconstruction() {
        properties.getAgent().setTextMaxModelCalls(1);
        var request = request(AgentTaskType.CHAT); var id = UUID.randomUUID(); String hash = "a".repeat(64);
        assertThat(budget.reserveModelCall(request, id, hash)).isTrue();
        budget.finishModelCall(request, id, "UNKNOWN");
        assertThat(service().reserveModelCall(request, id, hash)).isFalse();
        assertThatThrownBy(() -> service().reserveModelCall(request, id, "b".repeat(64))).hasMessageContaining("输入已变化");
        assertThatThrownBy(() -> service().reserveModelCall(request, UUID.randomUUID(), hash)).isInstanceOf(AgentBudgetExceededException.class);
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='MODEL_CALL'",
                String.class, request.requestId())).isEqualTo("UNKNOWN");
    }

    @Test void everyPreviouslySkippedTextKindAndStructuredRouterHasADurableRoot() {
        var calls = new AtomicInteger();
        for (var type : List.of(AgentTaskType.CHAT, AgentTaskType.NOVEL, AgentTaskType.READER_CHARACTER_CHAT,
                AgentTaskType.KNOWLEDGE_EXTRACTION, AgentTaskType.MANGA_ROUTER)) {
            var request = request(type);
            var model = new BudgetedAgentModel(textModel(calls), request, budget, properties);
            model.stream(List.of(message("fixture")), List.of(), null).blockLast();
            assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
            assertThat(count(request, "BUDGET_ROOT")).isEqualTo(1);
            assertThat(count(request, "INPUT_BYTE")).isEqualTo(1);
            assertThat(count(request, "OUTPUT_BYTE")).isEqualTo(1);
        }
        assertThat(calls).hasValue(5);
    }

    @Test void nativeColdStreamRejectsSecondSubscriptionWithoutAnotherReservationOrDispatch() {
        var request = request(AgentTaskType.CHAT); var calls = new AtomicInteger();
        var wrapped = new BudgetedAgentModel(textModel(calls), request, budget, properties);
        var stream = wrapped.stream(List.of(message("fixture")), List.of(), null);
        assertThat(count(request, "MODEL_CALL")).isZero();
        stream.blockLast();
        assertThatThrownBy(stream::blockLast).hasMessageContaining("重复订阅");
        assertThat(calls).hasValue(1);
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
        wrapped.stream(List.of(message("next")), List.of(), null).blockLast();
        assertThat(calls).hasValue(2);
    }

    @Test void rootAndStepGenerationCeilingsCannotGrowAfterReconstruction() {
        var request = request(AgentTaskType.CHAT);
        var calls = new AtomicInteger();
        var initial = AgentBudgetService.ModelLimits.from(properties);
        new BudgetedAgentModel(textModel(calls), request, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast();
        var configuration = properties.getAgent();
        var increases = List.<Runnable>of(
                () -> configuration.setMaxInputTokens(initial.maxInputTokens() + 1),
                () -> configuration.setMaxInputBytes(initial.maxInputBytes() + 1),
                () -> configuration.setMaxOutputTokens(initial.maxOutputTokens() + 1),
                () -> configuration.setMaxOutputBytes(initial.maxOutputBytes() + 1),
                () -> configuration.setModelIdleTimeoutSeconds(initial.modelTimeoutSeconds() + 1),
                () -> configuration.setModelActivityBudgetSeconds(initial.modelActivityBudgetSeconds() + 1));
        for (var increase : increases) {
            increase.run();
            var changedStep = new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                    request.messages(), Map.of("step_id", UUID.randomUUID().toString()), request.modelSpec(), "",
                    request.requestId(), request.conversationId());
            assertThatThrownBy(() -> new BudgetedAgentModel(textModel(calls), changedStep, service(), properties)
                    .stream(List.of(message("fixture")), List.of(), null).blockLast()).hasMessageContaining("不能放大原预算");
            configuration.setMaxInputTokens(initial.maxInputTokens()); configuration.setMaxInputBytes(initial.maxInputBytes());
            configuration.setMaxOutputTokens(initial.maxOutputTokens()); configuration.setMaxOutputBytes(initial.maxOutputBytes());
            configuration.setModelIdleTimeoutSeconds(initial.modelTimeoutSeconds());
            configuration.setModelActivityBudgetSeconds(initial.modelActivityBudgetSeconds());
        }
        configuration.setMaxOutputTokens(initial.maxOutputTokens() - 1);
        new BudgetedAgentModel(textModel(calls), request, service(), properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast();
        configuration.setMaxOutputTokens(initial.maxOutputTokens());
        assertThatThrownBy(() -> new BudgetedAgentModel(textModel(calls), request, service(), properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast()).hasMessageContaining("不能放大原预算");
        assertThat(calls).hasValue(2);
        assertThat(count(request, "MODEL_CALL")).isEqualTo(2);
        var root = JsonUtils.getJsonCodec().fromJson(jdbc.queryForObject(
                "SELECT metadata::text FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='BUDGET_ROOT'",
                String.class, request.requestId()), Map.class);
        assertThat(((Map<?, ?>) root.get("generation_limits")).get("maxOutputTokens")).isEqualTo(initial.maxOutputTokens() - 1);
    }

    @Test void minimumChainUsesRemainingFrozenRootAndScopeWithoutPrecharging() {
        var request = workflow(AgentTaskType.MANGA_STORYBOARD);
        budget.requireMinimumRemaining(request, 8, 12);
        assertThat(count(request, "BUDGET_ROOT")).isZero();
        assertThat(count(request, "MODEL_CALL")).isZero();
        new BudgetedAgentModel(textModel(new AtomicInteger()), request, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast();
        budget.requireMinimumRemaining(request, 7, 12);
        assertThatThrownBy(() -> budget.requireMinimumRemaining(request, 8, 12)).hasMessageContaining("MODEL_CALL_MINIMUM_CHAIN");
        properties.getAgent().setStoryboardMaxModelCalls(80);
        assertThatThrownBy(() -> service().requireMinimumRemaining(request, 8, 12)).hasMessageContaining("MODEL_CALL_MINIMUM_CHAIN");
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
    }

    @Test void insufficientToolChainFailsBeforeAnyReservationOrDomainEffect() {
        var request = workflow(AgentTaskType.MANGA_STORYBOARD);
        assertThatThrownBy(() -> budget.requireMinimumRemaining(request, 2, 13)).hasMessageContaining("TOOL_CALL_MINIMUM_CHAIN");
        assertThat(count(request, "BUDGET_ROOT")).isZero();
        assertThat(count(request, "TOOL_CALL")).isZero();
        assertThat(count(request, "MODEL_CALL")).isZero();
    }

    @Test void uncertainActivityHoldsItsAllocationAcrossStepsAndReconstruction() {
        properties.getAgent().setModelActivityBudgetSeconds(1);
        properties.getAgent().setModelIdleTimeoutSeconds(1);
        var request = request(AgentTaskType.CHAT);
        UUID first = UUID.randomUUID();
        assertThat(budget.reserveModelCall(request, first, "a".repeat(64))).isTrue();
        assertThat(budget.modelTimeAllowance(request, first)).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
        budget.finishModelCall(request, first, "UNKNOWN");
        var next = new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                request.messages(), Map.of("step_id", "next-step"), request.modelSpec(), "", request.requestId(), request.conversationId());
        assertThatThrownBy(() -> service().reserveModelCall(next, UUID.randomUUID(), "b".repeat(64)))
                .isInstanceOf(AgentBudgetExceededException.class).hasMessageContaining("MODEL_ACTIVITY_MILLIS");
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
    }

    @Test void completedActivityChargesOnlyElapsedTimeAndPausesDoNotResetTheOriginalRoot() {
        properties.getAgent().setModelActivityBudgetSeconds(2);
        properties.getAgent().setModelIdleTimeoutSeconds(1);
        var request = request(AgentTaskType.CHAT);
        UUID first = UUID.randomUUID();
        budget.reserveModelCall(request, first, "a".repeat(64));
        jdbc.update("""
                UPDATE agent_usage_ledger SET reservation_status='FINISHED',metadata=metadata||jsonb_build_object(
                    'model_started_at','2000-01-01T00:00:00Z','model_finished_at','2000-01-01T00:00:00.200Z')
                WHERE user_id=? AND root_request_id=? AND attempt_id=?
                """, user, request.requestId(), first);
        UUID second = UUID.randomUUID(), third = UUID.randomUUID();
        assertThat(service().reserveModelCall(request, second, "b".repeat(64))).isTrue();
        assertThat(service().reserveModelCall(request, third, "c".repeat(64))).isTrue();
        Long lastAllocation = jdbc.queryForObject("""
                SELECT (metadata->>'model_time_budget_ms')::bigint FROM agent_usage_ledger
                WHERE user_id=? AND root_request_id=? AND attempt_id=?
                """, Long.class, user, request.requestId(), third);
        assertThat(lastAllocation).isEqualTo(800);
        assertThatThrownBy(() -> service().reserveModelCall(request, UUID.randomUUID(), "d".repeat(64)))
                .hasMessageContaining("MODEL_ACTIVITY_MILLIS");
        assertThat(count(request, "MODEL_CALL")).isEqualTo(3);
    }

    @Test void activeAllocationsCannotOverbookTheSameRootDurationAcrossWorkers() throws Exception {
        properties.getAgent().setModelActivityBudgetSeconds(1);
        properties.getAgent().setModelIdleTimeoutSeconds(1);
        var request = request(AgentTaskType.CHAT);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
                ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                try { return service().reserveModelCall(request, UUID.randomUUID(), "a".repeat(64)); }
                catch (AgentBudgetExceededException exhausted) { return false; }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            int accepted = 0; for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
    }

    @Test void delayedDispatchCannotRestartAnExpiredAttemptClock() {
        properties.getAgent().setModelActivityBudgetSeconds(1);
        var request = request(AgentTaskType.CHAT); UUID attempt = UUID.randomUUID();
        budget.reserveModelCall(request, attempt, "a".repeat(64));
        jdbc.update("""
                UPDATE agent_usage_ledger SET metadata=metadata||jsonb_build_object('model_started_at',clock_timestamp()-interval '2 seconds')
                WHERE user_id=? AND root_request_id=? AND attempt_id=?
                """, user, request.requestId(), attempt);
        assertThatThrownBy(() -> service().modelTimeAllowance(request, attempt)).hasMessageContaining("MODEL_ACTIVITY_MILLIS");
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
    }

    @Test void originalOwnerIsRequiredAtReservationAndAgainImmediatelyBeforeDispatch() {
        var request = workflow(AgentTaskType.MANGA_CONVERSATION);
        var missingOwner = new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                List.of(), Map.of("fencing_token", 1L, "step_id", "fixture"), request.modelSpec(), "",
                request.requestId(), request.conversationId());
        assertThatThrownBy(() -> budget.reserveModelCall(missingOwner, UUID.randomUUID(), "a".repeat(64)))
                .hasMessageContaining("执行身份已变化");
        assertThat(count(request, "BUDGET_ROOT")).isZero();
        UUID attempt = UUID.randomUUID();
        assertThat(budget.reserveModelCall(request, attempt, "b".repeat(64))).isTrue();
        jdbc.update("UPDATE manga_agent_runs SET owner_instance_id='new-worker' WHERE user_id=? AND request_id=?",
                user, request.requestId());
        assertThatThrownBy(() -> service().modelTimeAllowance(request, attempt)).hasMessageContaining("执行身份已变化");
        assertThatThrownBy(() -> service().reserveModelCall(request, UUID.randomUUID(), "c".repeat(64)))
                .hasMessageContaining("执行身份已变化");
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='MODEL_CALL'",
                String.class, request.requestId())).isEqualTo("RESERVED");
    }

    @Test void adapterConstructionCannotRestartTheDeadlineBeforeItsColdStreamIsSubscribed() {
        properties.getAgent().setModelActivityBudgetSeconds(1);
        var request = request(AgentTaskType.CHAT); var subscriptions = new AtomicInteger();
        Model adapter = new Model() {
            @Override public String getModelName() { return "fixture-delayed-adapter"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                jdbc.update("""
                        UPDATE agent_usage_ledger SET metadata=metadata||jsonb_build_object(
                            'model_started_at',clock_timestamp()-interval '2 seconds')
                        WHERE user_id=? AND root_request_id=? AND usage_kind='MODEL_CALL'
                        """, user, request.requestId());
                return Flux.defer(() -> {
                    subscriptions.incrementAndGet();
                    return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("late").build())).build());
                });
            }
        };
        assertThatThrownBy(() -> new BudgetedAgentModel(adapter, request, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast()).hasMessageContaining("MODEL_ACTIVITY_MILLIS");
        assertThat(subscriptions).hasValue(0);
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='MODEL_CALL'",
                String.class, request.requestId())).isEqualTo("UNKNOWN");
    }

    @Test void nativeExpertHasFinitePerExpertAllowanceAndSharesItsParentsDurableRoot() {
        var parent = workflow(AgentTaskType.MANGA_REVIEW);
        var child = withExpert(parent, "camera-reviewer", "native-task-camera");
        var identity = (AgentReviewExpert) child.variables().get("review_expert");
        assertThat(budget.reserveReviewExpert(parent, identity)).isTrue();
        assertThat(service().reserveReviewExpert(parent, identity)).isFalse();
        for (int i = 0; i < 3; i++) {
            UUID attempt = UUID.randomUUID();
            assertThat(service().reserveModelCall(child, attempt, "a".repeat(64))).isTrue();
            budget.finishModelCall(child, attempt, "FINISHED");
        }
        assertThatThrownBy(() -> service().reserveModelCall(child, UUID.randomUUID(), "b".repeat(64)))
                .isInstanceOf(AgentBudgetExceededException.class);
        for (int i = 0; i < 2; i++) assertThat(budget.reserveToolCalls(child, List.of(new ToolUseBlock(
                "read-" + i, "get_chapter_context", Map.of())))).isTrue();
        assertThatThrownBy(() -> budget.reserveToolCalls(child, List.of(new ToolUseBlock("extra", "get_chapter_context", Map.of()))))
                .isInstanceOf(AgentBudgetExceededException.class);
        assertThat(count(parent, "SUBAGENT")).isEqualTo(1);
        assertThat(count(parent, "MODEL_CALL")).isEqualTo(3);
        assertThat(count(parent, "BUDGET_ROOT")).isEqualTo(1);
        assertThat(budget.reserveModelCall(parent, UUID.randomUUID(), "c".repeat(64))).isTrue();
    }

    @Test void expertIdentityCannotBorrowAnotherTaskOrStartANewBudgetRoot() {
        var parent = workflow(AgentTaskType.MANGA_REVIEW);
        var child = withExpert(parent, "character-reviewer", "native-task-character");
        var identity = (AgentReviewExpert) child.variables().get("review_expert");
        assertThat(budget.reserveReviewExpert(parent, identity)).isTrue();
        assertThatThrownBy(() -> service().reserveReviewExpert(parent, new AgentReviewExpert(
                identity.expertId(), "different-native-task", parent.requestId(), identity.parentStepId())))
                .hasMessageContaining("persisted native task binding changed");
        assertThatThrownBy(() -> service().reserveReviewExpert(parent, new AgentReviewExpert(
                identity.expertId(), identity.taskId(), UUID.randomUUID(), identity.parentStepId())))
                .hasMessageContaining("invalid parent or expert identity");
        var variables = new LinkedHashMap<>(child.variables());
        variables.put("review_expert", Map.of("expertId", identity.expertId()));
        var untyped = new AgentRunRequest(child.userId(), child.storyId(), child.chapterId(), child.taskType(), List.of(),
                variables, child.modelSpec(), "", child.requestId(), child.conversationId());
        assertThatThrownBy(() -> service().reserveModelCall(untyped, UUID.randomUUID(), "d".repeat(64)))
                .hasMessageContaining("invalid parent or expert identity");
        assertThat(count(parent, "BUDGET_ROOT")).isEqualTo(1);
        assertThat(count(parent, "MODEL_CALL")).isZero();
    }

    @Test void fourNativeExpertsExhaustTheCumulativeSpawnAllowanceAcrossSteps() {
        var parent = workflow(AgentTaskType.MANGA_REVIEW);
        for (String expert : List.of("camera-reviewer", "character-reviewer", "pacing-reviewer", "continuity-reviewer")) {
            var child = withExpert(parent, expert, "native-" + expert);
            assertThat(budget.reserveReviewExpert(parent, (AgentReviewExpert) child.variables().get("review_expert"))).isTrue();
        }
        var values = new LinkedHashMap<>(parent.variables()); values.put("step_id", "another-step");
        var later = new AgentRunRequest(parent.userId(), parent.storyId(), parent.chapterId(), parent.taskType(), List.of(),
                values, parent.modelSpec(), "", parent.requestId(), parent.conversationId());
        var fifth = withExpert(later, "camera-reviewer", "native-later-camera");
        assertThatThrownBy(() -> service().reserveReviewExpert(later, (AgentReviewExpert) fifth.variables().get("review_expert")))
                .isInstanceOf(AgentBudgetExceededException.class).hasMessageContaining("SUBAGENT");
        assertThat(count(parent, "SUBAGENT")).isEqualTo(4);
        assertThat(count(parent, "BUDGET_ROOT")).isEqualTo(1);
    }

    @Test void continuousTokensCannotExtendTheAbsoluteActivityDeadline() throws Exception {
        properties.getAgent().setModelActivityBudgetSeconds(1);
        properties.getAgent().setModelIdleTimeoutSeconds(5);
        var request = request(AgentTaskType.CHAT); var cancelled = new CountDownLatch(1);
        var chunks = new AtomicInteger();
        Model nativeModel = new Model() {
            @Override public String getModelName() { return "continuous-native-fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.interval(Duration.ofMillis(25)).map(tick -> {
                    chunks.incrementAndGet();
                    return ChatResponse.builder().content(List.of(TextBlock.builder().text("x").build())).build();
                }).doOnCancel(cancelled::countDown);
            }
        };
        assertThatThrownBy(() -> new BudgetedAgentModel(nativeModel, request, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast(Duration.ofSeconds(5)))
                .isInstanceOf(AgentBudgetExceededException.class).hasMessageContaining("MODEL_ACTIVITY_MILLIS");
        assertThat(chunks.get()).isPositive(); assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='MODEL_CALL'",
                String.class, request.requestId())).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> new BudgetedAgentModel(nativeModel, request, service(), properties)
                .stream(List.of(message("again")), List.of(), null).blockLast()).hasMessageContaining("MODEL_ACTIVITY_MILLIS");
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
    }

    @Test void inFlightOutputRetainsItsImmutableCeilingWhenConfigurationChanges() {
        var request = request(AgentTaskType.CHAT);
        properties.getAgent().setMaxOutputBytes(5);
        var calls = new AtomicInteger();
        var nativeOptions = new AtomicReference<GenerateOptions>();
        Model delegate = new Model() {
            @Override public String getModelName() { return "immutable-envelope"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                nativeOptions.set(options); calls.incrementAndGet();
                properties.getAgent().setMaxOutputBytes(500);
                properties.getAgent().setMaxOutputTokens(50000);
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("漫画").build())).build());
            }
        };
        int originalTokens = properties.getAgent().getMaxOutputTokens();
        var stream = new BudgetedAgentModel(delegate, request, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null);
        assertThatThrownBy(stream::blockLast).isInstanceOf(AgentBudgetExceededException.class).hasMessageContaining("OUTPUT_BYTE");
        assertThat(nativeOptions.get().getMaxTokens()).isEqualTo(originalTokens);
        assertThat(calls).hasValue(1);
        var measurement = jdbc.queryForMap("SELECT amount,limit_value FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='OUTPUT_BYTE'",
                request.requestId());
        assertThat(((Number) measurement.get("amount")).longValue()).isEqualTo(6);
        assertThat(((Number) measurement.get("limit_value")).longValue()).isEqualTo(5);
    }

    @Test void smallerConfigurationRejectsAnAlreadyCreatedLargerEnvelopeBeforeDispatch() {
        var request = request(AgentTaskType.CHAT); var calls = new AtomicInteger();
        var stream = new BudgetedAgentModel(textModel(calls), request, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null);
        properties.getAgent().setMaxOutputTokens(10);
        assertThatThrownBy(stream::blockLast).hasMessageContaining("不能放大原预算");
        assertThat(calls).hasValue(0);
        assertThat(count(request, "MODEL_CALL")).isZero();
        assertThat(count(request, "BUDGET_ROOT")).isZero();
    }

    @Test void aToolBatchIsReservedAllOrNothingAndStableCallReplayDoesNotSpendAgain() {
        properties.getAgent().setStoryboardMaxToolCalls(1);
        var request = workflow(AgentTaskType.MANGA_STORYBOARD);
        var one = new ToolUseBlock("first", "fixture", Map.of(), "{}", Map.of());
        var two = new ToolUseBlock("second", "fixture", Map.of(), "{}", Map.of());
        assertThatThrownBy(() -> budget.reserveToolCalls(request, List.of(one, two))).isInstanceOf(AgentBudgetExceededException.class);
        assertThat(count(request, "TOOL_CALL")).isZero();
        assertThat(count(request, "BUDGET_ROOT")).isZero();
        assertThat(budget.reserveToolCalls(request, List.of(one))).isTrue();
        assertThat(service().reserveToolCalls(request, List.of(one))).isFalse();
        assertThatThrownBy(() -> budget.reserveToolCalls(request, List.of(one, two))).hasMessageContaining("已预占与新调用");
        assertThatThrownBy(() -> budget.reserveToolCalls(request, List.of(two))).isInstanceOf(AgentBudgetExceededException.class);
        assertThatThrownBy(() -> budget.reserveToolCalls(request, List.of(one, one))).hasMessageContaining("唯一调用标识");
        assertThat(count(request, "TOOL_CALL")).isEqualTo(1);
    }

    @Test void sourceAndModelBindingsCannotChangeAcrossReconstruction() {
        var request = request(AgentTaskType.CHAT); String hash = "a".repeat(64);
        budget.reserveModelCall(request, UUID.randomUUID(), hash);
        var other = new AgentRunRequest(request.userId(), 999L, request.chapterId(), request.taskType(), List.of(), Map.of(),
                request.modelSpec(), "", request.requestId(), request.conversationId());
        assertThatThrownBy(() -> service().reserveModelCall(other, UUID.randomUUID(), hash)).hasMessageContaining("身份或来源");
        var changed = new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(), List.of(), Map.of(),
                new AgentModelSpec("fixture", "https://different.example.test", "fixture", "hash"), "", request.requestId(), request.conversationId());
        assertThatThrownBy(() -> service().reserveModelCall(changed, UUID.randomUUID(), hash)).hasMessageContaining("模型配置或能力声明");
        assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
    }

    @Test void stoppedWorkflowAndExpiredLeaseCannotReserveOrReachTheModel() {
        var request = workflow(AgentTaskType.MANGA_CONVERSATION); var calls = new AtomicInteger();
        jdbc.update("UPDATE manga_agent_runs SET lease_until=clock_timestamp()-interval '1 second' WHERE request_id=?", request.requestId());
        var wrapped = new BudgetedAgentModel(textModel(calls), request, budget, properties);
        assertThatThrownBy(() -> wrapped.stream(List.of(message("fixture")), List.of(), null).blockLast()).hasMessageContaining("租约过期");
        assertThat(calls).hasValue(0);
        assertThat(count(request, "MODEL_CALL")).isZero();
    }

    @Test void capabilitiesAreForwardedAndNativeModelRequestsHaveSingleAttemptAndOutputLimits() {
        var calls = new AtomicInteger();
        Model delegate = new Model() {
            @Override public String getModelName() { return "native-fixture"; }
            @Override public int getContextWindowSize() { return 12345; }
            @Override public boolean supportsNativeStructuredOutput() { return true; }
            @Override public boolean supportsNativeStructuredOutputWithTools() { return false; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(options.getExecutionConfig().getMaxAttempts()).isEqualTo(1);
                assertThat(options.getMaxTokens()).isEqualTo(properties.getAgent().getMaxOutputTokens());
                calls.incrementAndGet(); return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("ready").build())).build());
            }
        };
        var model = new BudgetedAgentModel(delegate, request(AgentTaskType.CHAT), budget, properties);
        assertThat(model.getModelName()).isEqualTo("native-fixture");
        assertThat(model.getContextWindowSize()).isEqualTo(12345);
        assertThat(model.supportsNativeStructuredOutput()).isTrue();
        assertThat(model.supportsNativeStructuredOutputWithTools()).isFalse();
        model.stream(List.of(message("fixture")), List.of(), null).blockLast();
        assertThat(calls).hasValue(1);
    }

    @Test void realHarnessToolRoundTripAndCompactionUseTheSameSqlRoot() {
        var request = workflow(AgentTaskType.MANGA_CONVERSATION);
        var calls = new AtomicInteger(); var main = new AtomicInteger(); var compacted = new AtomicInteger(); var effects = new AtomicInteger();
        Model nativeModel = new Model() {
            @Override public String getModelName() { return "scripted-fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                ContentBlock output;
                if (tools == null || tools.isEmpty()) { compacted.incrementAndGet(); output = TextBlock.builder().text("A bounded fixture summary.").build(); }
                else if (main.getAndIncrement() == 0) output = new ToolUseBlock("read-1", "read_fixture", Map.of(), "{}", Map.of());
                else output = TextBlock.builder().text("Read result received.").build();
                return Flux.just(ChatResponse.builder().content(List.of(output)).finishReason(output instanceof ToolUseBlock ? "tool_calls" : "stop").build());
            }
        };
        var registry = mock(AgentToolConfigurationRegistry.class);
        doAnswer(invocation -> {
            Toolkit toolkit = invocation.getArgument(0);
            toolkit.registerAgentTool(new ToolBase(ToolBase.builder().name("read_fixture").description("Read fixture")
                    .inputSchema(Map.of("type", "object")).readOnly(true)) {
                @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                    effects.incrementAndGet(); return Mono.just(ToolResultBlock.text("Fixture read result."));
                }
            }); return null;
        }).when(registry).configure(any(), any());
        var runs = mock(MangaAgentRunService.class);
        ToolCapabilityFixtures.mockScopes(runs);
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().triggerMessages(2).keepMessages(1).keepTokens(0)
                .flushBeforeCompact(false).offloadBeforeCompact(false).build(), properties, mock(AgentWorkspaceService.class),
                new MangaAgentPromptProvider(), new StrictAgentStateStore(new InMemoryAgentStateStore()), mock(AgentTurnStateStores.class),
                registry, mock(ArtVerseSkillRegistry.class), mock(ArtVerseSkillRepository.class), runs, mock(PostgresAgentWorkspaceStore.class),
                new AgentToolBoundaryMiddleware(runs, mock(AutomationAgentTools.class), budget));
        var agent = factory.buildAgent(request, workspace, BusinessSkillSelection.empty(), nativeModel);
        try {
            RuntimeContext context = new AgentScopeRuntimeContextFactory().create(request);
            var result = agent.call(List.of(message("earlier context"), message("read the fixture")), context).block(Duration.ofSeconds(20));
            assertThat(result.getTextContent()).contains("Read result received");
        } finally { agent.close(); }
        assertThat(effects).hasValue(1);
        assertThat(main).hasValue(2);
        assertThat(compacted.get()).isGreaterThan(0);
        assertThat(count(request, "MODEL_CALL")).isEqualTo(calls.get());
        assertThat(count(request, "TOOL_CALL")).isEqualTo(1);
        assertThat(count(request, "BUDGET_ROOT")).isEqualTo(1);
        assertThat(toolMetadata(request).path("native_result_state").asText()).isEqualTo("success");
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='TOOL_CALL'",
                String.class, request.requestId())).isEqualTo("FINISHED");
        String inputHash = AgentBudgetService.hash(Map.of("name", "read_fixture", "input", Map.of()));
        budget.observeToolResult(request, "read-1", "read_fixture", inputHash, ToolResultState.ERROR);
        assertThat(toolMetadata(request).path("native_result_state").asText()).isEqualTo("success");
    }

    @Test void differentChildRootsShareTheFrozenAutomationModelLimit() {
        var first = workflow(AgentTaskType.MANGA_CONVERSATION); var second = workflow(AgentTaskType.MANGA_CONVERSATION);
        UUID plan = UUID.randomUUID(), parent = UUID.randomUUID();
        String definition = "{\"limits\":{\"maxModelCallsPerRun\":1}}";
        jdbc.update("""
                INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,idempotency_key,request_hash)
                VALUES (?,?,'Budget fixture','AGENT','MANGA_AGENT',?::jsonb,'Asia/Shanghai',?,'fixture')
                """, plan, user, definition, plan.toString());
        jdbc.update("""
                INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,
                    definition_snapshot,state,lease_owner,lease_until,fencing_token)
                VALUES (?,?,?,0,'MANUAL',clock_timestamp(),?,?::jsonb,'RUNNING','fixture',clock_timestamp()+interval '90 seconds',1)
                """, parent, plan, user, parent.toString(), definition);
        for (var request : List.of(first, second)) jdbc.update("""
                INSERT INTO automation_run_execution(run_id,target_key,execution_kind,agent_run_id,conversation_uuid,request_id)
                SELECT ?,?,'MANGA_AGENT',id,?,request_id FROM manga_agent_runs WHERE user_id=? AND request_id=?
                """, parent, request.requestId().toString(), request.conversationId(), user, request.requestId());
        var originalFirst = first;
        first = withParent(first, parent, "fixture", 1L);
        second = withParent(second, parent, "fixture", 1L);
        var capturedFirst = first; var capturedSecond = second;
        assertThatThrownBy(() -> budget.reserveModelCall(originalFirst, UUID.randomUUID(), "a".repeat(64)))
                .hasMessageContaining("后台原执行身份");
        var calls = new AtomicInteger();
        new BudgetedAgentModel(textModel(calls), first, budget, properties).stream(List.of(message("first")), List.of(), null).blockLast();
        // An edited configuration cannot restore or increase the frozen parent allowance.
        jdbc.update("UPDATE automation_run SET definition_snapshot='{" + "\"limits\":{\"maxModelCallsPerRun\":9}}'::jsonb WHERE id=?", parent);
        assertThatThrownBy(() -> new BudgetedAgentModel(textModel(calls), capturedSecond, service(), properties)
                .stream(List.of(message("second")), List.of(), null).blockLast()).isInstanceOf(AgentBudgetExceededException.class);
        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_usage_ledger WHERE automation_run_id=? AND usage_kind='MODEL_CALL'",
                Long.class, parent)).isEqualTo(1);
        jdbc.update("UPDATE automation_run SET lease_owner='replacement',fencing_token=2 WHERE id=?", parent);
        assertThatThrownBy(() -> budget.modelTimeAllowance(capturedFirst, UUID.randomUUID()))
                .hasMessageContaining("后台原执行身份");
        assertThatThrownBy(() -> budget.reserveModelCall(capturedFirst, UUID.randomUUID(), "b".repeat(64)))
                .hasMessageContaining("后台原执行身份");
        jdbc.update("UPDATE automation_run_execution SET conversation_uuid=? WHERE run_id=? AND request_id=?", UUID.randomUUID(), parent, second.requestId());
        assertThatThrownBy(() -> budget.reserveModelCall(capturedSecond, UUID.randomUUID(), "a".repeat(64))).hasMessageContaining("不能作为交互运行");
    }

    @Test void legacyUsageCountsConservativelyWithoutInventingAttemptEvidence() {
        var request = request(AgentTaskType.CHAT);
        properties.getAgent().setTextMaxModelCalls(1);
        jdbc.update("INSERT INTO agent_usage_ledger(user_id,request_id,usage_kind,amount) VALUES (?,?,'MODEL_CALL',1)", user, request.requestId());
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> new BudgetedAgentModel(textModel(calls), request, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast()).isInstanceOf(AgentBudgetExceededException.class);
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_usage_ledger WHERE user_id=? AND request_id=? AND attempt_id IS NULL",
                Long.class, user, request.requestId())).isEqualTo(1);
        assertThat(count(request, "BUDGET_ROOT")).isZero();
    }

    @Test void reservationInsertFailurePreventsDispatchAndRollsBackTheNewRoot() {
        var request = request(AgentTaskType.CHAT); var calls = new AtomicInteger();
        String constraint = "fixture_budget_" + user;
        jdbc.execute("ALTER TABLE agent_usage_ledger ADD CONSTRAINT " + constraint + " CHECK (user_id<>" + user + " OR usage_kind<>'MODEL_CALL')");
        try {
            assertThatThrownBy(() -> new BudgetedAgentModel(textModel(calls), request, budget, properties)
                    .stream(List.of(message("fixture")), List.of(), null).blockLast())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(calls).hasValue(0);
            assertThat(count(request, "BUDGET_ROOT")).isZero();
            assertThat(count(request, "MODEL_CALL")).isZero();
        } finally { jdbc.execute("ALTER TABLE agent_usage_ledger DROP CONSTRAINT " + constraint); }
    }

    @Test void upgradeFromV56PreservesOldLedgerWithoutManufacturingRootsOrAttempts() {
        String schema = "budget_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("56").load().migrate();
            jdbc.update("INSERT INTO " + schema + ".users SELECT * FROM public.users WHERE id=?", user);
            UUID root = UUID.randomUUID();
            jdbc.update("INSERT INTO " + schema + ".agent_usage_ledger(user_id,request_id,usage_kind,amount) VALUES (?,?,'MODEL_CALL',1)", user, root);
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            var old = jdbc.queryForMap("SELECT * FROM " + schema + ".agent_usage_ledger WHERE request_id=?", root);
            assertThat(old.get("root_request_id")).isNull(); assertThat(old.get("attempt_id")).isNull();
            assertThat(old.get("reservation_status")).isNull(); assertThat(((Number) old.get("amount")).longValue()).isEqualTo(1);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }

    @Test void nativeStructuredFallbackReservesTwoActualModelAttemptsInOneRoot() {
        var base = request(AgentTaskType.MANGA_ROUTER);
        var request = new AgentRunRequest(base.userId(),base.storyId(),base.chapterId(),base.taskType(),List.of(),Map.of(),
                new AgentModelSpec("fixture","https://provider.example.test/v1","fixture","hash",null,
                        new AgentModelCapabilities(true,true,false,"fixture-v1")),"",base.requestId(),base.conversationId(),BusinessSkillSelection.empty());
        var calls = new AtomicInteger();
        Model nativeModel = new Model() {
            @Override public String getModelName() { return "native-fallback-fixture"; }
            @Override public boolean supportsNativeStructuredOutput() { return true; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                if (options.getResponseFormat() != null) return Flux.error(new IllegalStateException("fixture native unavailable"));
                assertThat(tools).extracting(ToolSchema::getName).containsExactly(AgentStructuredOutput.NAME);
                var input = Map.<String,Object>of("response",Map.of("label","ready"));
                return Flux.just(ChatResponse.builder().content(List.of(new ToolUseBlock("fallback",AgentStructuredOutput.NAME,input,
                        "{\"response\":{\"label\":\"ready\"}}",Map.of()))).finishReason("tool_calls").build());
            }
        };
        var runs = mock(MangaAgentRunService.class);
        ToolCapabilityFixtures.mockScopes(runs);
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().build(),properties,mock(AgentWorkspaceService.class),
                new MangaAgentPromptProvider(),new StrictAgentStateStore(new InMemoryAgentStateStore()),mock(AgentTurnStateStores.class),
                mock(AgentToolConfigurationRegistry.class),mock(ArtVerseSkillRegistry.class),mock(ArtVerseSkillRepository.class),runs,
                mock(PostgresAgentWorkspaceStore.class),new AgentToolBoundaryMiddleware(runs,mock(AutomationAgentTools.class),budget));
        var agent = factory.buildAgent(request,workspace,BusinessSkillSelection.empty(),nativeModel);
        try {
            var context = new AgentScopeRuntimeContextFactory().create(request);
            context.put(AgentStructuredOutput.class,new AgentStructuredOutput(request,Label.class,2));
            var result = agent.call(List.of(message("Return the fixture label")),Label.class,context).block(Duration.ofSeconds(20));
            assertThat(result.getStructuredData(Label.class)).isEqualTo(new Label("ready"));
        } finally { agent.close(); }
        assertThat(calls).hasValue(2);
        assertThat(count(request,"MODEL_CALL")).isEqualTo(2);
        assertThat(count(request,"TOOL_CALL")).isZero();
        assertThat(count(request,"BUDGET_ROOT")).isEqualTo(1);
    }

    record Label(String label) { }

    @Test void invalidNativeOptionsNeverReserveOrDispatchARequest() {
        int cap = properties.getAgent().getMaxOutputTokens();
        var invalid = new ArrayList<>(List.of(
                GenerateOptions.builder().apiKey("fixture-override").build(),
                GenerateOptions.builder().baseUrl("https://override.example.test").build(),
                GenerateOptions.builder().modelName("override").build(),
                GenerateOptions.builder().endpointPath("/other").build(),
                GenerateOptions.builder().additionalHeader("Authorization", "fixture-override").build(),
                GenerateOptions.builder().additionalQueryParam("model", "override").build(),
                GenerateOptions.builder().maxTokens(0).build(),
                GenerateOptions.builder().maxTokens(cap + 1).build(),
                GenerateOptions.builder().maxCompletionTokens(-1).build(),
                GenerateOptions.builder().maxCompletionTokens(cap + 1).build(),
                GenerateOptions.builder().maxTokens(10).maxCompletionTokens(10).build(),
                GenerateOptions.builder().thinkingBudget(cap + 1).build(),
                GenerateOptions.builder().executionConfig(ExecutionConfig.builder().timeout(Duration.ZERO).build()).build()));
        for (String key : List.of("model", "messages", "tools", "response_format", "max_tokens", "max_completion_tokens", "unknown"))
            invalid.add(GenerateOptions.builder().additionalBodyParam(key, "fixture").build());
        var request = request(AgentTaskType.CHAT); var calls = new AtomicInteger();
        var model = new BudgetedAgentModel(textModel(calls), request, budget, properties);
        for (var options : invalid)
            assertThatThrownBy(() -> model.stream(List.of(message("fixture")), List.of(), options).blockLast())
                    .isInstanceOf(com.artverse.common.BusinessException.class);
        assertThat(calls).hasValue(0);
        assertThat(count(request, "BUDGET_ROOT")).isZero();
        assertThat(count(request, "MODEL_CALL")).isZero();
    }

    @Test void nativeHttpRequestUsesDetachedSchemaAndExactlyOneBoundedOutputField() throws Exception {
        var request = request(AgentTaskType.CHAT);
        var captured = new AtomicReference<String>();
        var httpCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            httpCalls.incrementAndGet();
            byte[] response = ("{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\"fixture\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ready\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":4,\"total_tokens\":104,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":40},\"completion_tokens_details\":{\"reasoning_tokens\":1}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            Model nativeModel = OpenAIChatModel.builder().apiKey("isolated-fixture")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").modelName("fixture").stream(false).build();
            var model = new BudgetedAgentModel(nativeModel, request, budget, properties);
            var nested = new HashMap<String, Object>(Map.of("type", "string", "description", "original"));
            var schema = new HashMap<String, Object>(Map.of("type", "object", "properties", Map.of("label", nested)));
            var tool = ToolSchema.builder().name("fixture_read").description("Fixture").parameters(schema).build();
            var format = ResponseFormat.jsonSchema(JsonSchema.builder().name("fixture_result").schema(schema).strict(true).build());
            var messages = new ArrayList<>(List.of(message("original fixture message")));
            var tools = new ArrayList<>(List.of(tool));
            var stream = model.stream(messages, tools, GenerateOptions.builder().stream(false)
                    .maxCompletionTokens(12).responseFormat(format).build());
            nested.put("description", "changed after stream creation");
            messages.clear(); tools.clear(); format.setType("text");
            assertThat(stream.blockLast(Duration.ofSeconds(20)).getContent()).isNotEmpty();
            JsonNode body = JsonUtils.getJsonCodec().fromJson(captured.get(), JsonNode.class);
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(12);
            assertThat(body.has("max_tokens")).isFalse();
            assertThat(body.path("messages").toString()).contains("original fixture message");
            assertThat(body.path("tools").toString()).contains("original").doesNotContain("changed after");
            assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
            assertThat(body.path("response_format").toString()).contains("original").doesNotContain("changed after");
            JsonNode usage = modelMetadata(request).path("provider_usage");
            assertThat(usage.path("status").asText()).isEqualTo("REPORTED");
            assertThat(usage.path("input_tokens").asInt()).isEqualTo(100);
            assertThat(usage.path("cached_tokens").asInt()).isEqualTo(40);
            assertThat(usage.path("reasoning_tokens").asInt()).isEqualTo(1);
            assertThat(usage.path("total_tokens").asInt()).isEqualTo(104);
            assertThat(httpCalls).hasValue(1);
            assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test void typedGenerationOptionsAndTighterTimeoutArePreservedAlongsideTheNativeInputHash() {
        var request = request(AgentTaskType.CHAT);
        var nestedMetadata = new HashMap<String, Object>(Map.of("value", "original"));
        var input = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("fixture").build())
                .metadata(Map.of("fixture", nestedMetadata)).build();
        var capturedPayload = new AtomicReference<String>();
        Model delegate = new Model() {
            @Override public String getModelName() { return "fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(options.getMaxTokens()).isEqualTo(17);
                assertThat(options.getMaxCompletionTokens()).isNull();
                assertThat(options.getTemperature()).isEqualTo(0.2);
                assertThat(options.getExecutionConfig().getTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(options.getExecutionConfig().getMaxAttempts()).isEqualTo(1);
                assertThat(messages.getFirst().getMetadata().get("fixture")).isEqualTo(Map.of("value", "original"));
                capturedPayload.set(JsonUtils.getJsonCodec().toJson(Map.of("messages", messages, "tools", tools, "options", options)));
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("ready").build())).build());
            }
        };
        var stream = new BudgetedAgentModel(delegate, request, budget, properties).stream(List.of(input), List.of(),
                GenerateOptions.builder().maxTokens(17).temperature(0.2)
                        .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(3)).maxAttempts(9).build()).build());
        nestedMetadata.put("value", "changed");
        stream.blockLast();
        assertThat(jdbc.queryForObject("SELECT attempt_hash FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='MODEL_CALL'",
                String.class, request.requestId())).isEqualTo(ToolIdempotencyService.sha256(capturedPayload.get()));
        JsonNode usage = modelMetadata(request).path("provider_usage");
        assertThat(usage.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(usage.has("input_tokens")).isFalse();
    }

    @Test void cumulativeUsageIsNotAddedTwiceAndErrorReportsRemainBoundToTheirAttempt() {
        ChatUsage larger = ChatUsage.builder().inputTokens(10).outputTokens(5).cachedTokens(3).reasoningTokens(2).build();
        ChatUsage smaller = ChatUsage.builder().inputTokens(10).outputTokens(2).build();
        for (boolean decreases : List.of(false, true)) {
            var request = request(AgentTaskType.CHAT);
            Model delegate = new Model() {
                @Override public String getModelName() { return "fixture"; }
                @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    return Flux.just(ChatResponse.builder().content(List.of()).usage(larger).build(),
                            ChatResponse.builder().content(List.of()).usage(decreases ? smaller : larger).build());
                }
            };
            new BudgetedAgentModel(delegate, request, budget, properties).stream(List.of(message("fixture")), List.of(), null).blockLast();
            JsonNode usage = modelMetadata(request).path("provider_usage");
            assertThat(usage.path("status").asText()).isEqualTo(decreases ? "INCONSISTENT" : "REPORTED");
            assertThat(usage.path("total_tokens").asInt()).isEqualTo(decreases ? 12 : 15);
        }
        var request = request(AgentTaskType.CHAT);
        var original = new IllegalStateException("fixture stream failed after reported usage");
        Model failing = new Model() {
            @Override public String getModelName() { return "fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.concat(Flux.just(ChatResponse.builder().content(List.of()).usage(larger).build()), Flux.error(original));
            }
        };
        assertThatThrownBy(() -> new BudgetedAgentModel(failing, request, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast()).isSameAs(original);
        assertThat(modelMetadata(request).path("provider_usage").path("total_tokens").asInt()).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='MODEL_CALL'",
                String.class, request.requestId())).isEqualTo("UNKNOWN");
    }

    @Test void observationSqlFailureDoesNotRefundTheAttemptOrMaskTheNativeFailure() {
        properties.getAgent().setTextMaxModelCalls(1);
        String constraint = "fixture_usage_" + user;
        jdbc.execute("ALTER TABLE agent_usage_ledger ADD CONSTRAINT " + constraint
                + " CHECK (user_id<>" + user + " OR usage_kind<>'MODEL_CALL' OR NOT(metadata ? 'provider_usage'))");
        try {
            var request = request(AgentTaskType.CHAT); var calls = new AtomicInteger();
            var wrapped = new BudgetedAgentModel(textModel(calls), request, budget, properties);
            assertThat(wrapped.stream(List.of(message("fixture")), List.of(), null).blockLast()).isNotNull();
            assertThat(count(request, "MODEL_CALL")).isEqualTo(1);
            assertThat(modelMetadata(request).has("provider_usage")).isFalse();
            assertThatThrownBy(() -> wrapped.stream(List.of(message("next")), List.of(), null).blockLast())
                    .isInstanceOf(AgentBudgetExceededException.class);
            assertThat(calls).hasValue(1);
            var original = new IllegalStateException("fixture original provider error");
            Model failing = new Model() {
                @Override public String getModelName() { return "fixture"; }
                @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    return Flux.error(original);
                }
            };
            assertThatThrownBy(() -> new BudgetedAgentModel(failing, request(AgentTaskType.CHAT), budget, properties)
                    .stream(List.of(message("fixture")), List.of(), null).blockLast()).isSameAs(original);
        } finally { jdbc.execute("ALTER TABLE agent_usage_ledger DROP CONSTRAINT " + constraint); }
    }

    @Test void storyChatCannotUseDirectorAllowanceAndNewStepsDoNotResetTheRoot() {
        var original = workflow(AgentTaskType.STORY_CHAT_READ);
        jdbc.update("UPDATE manga_agent_runs SET run_type='STORY_CHAT',route='CONVERSATION' WHERE request_id=?", original.requestId());
        properties.getAgent().setStoryChatReadMaxModelCalls(1);
        var calls = new AtomicInteger();
        new BudgetedAgentModel(textModel(calls), original, budget, properties).stream(List.of(message("fixture")), List.of(), null).blockLast();
        var changed = new AgentRunRequest(original.userId(), original.storyId(), original.chapterId(), original.taskType(), List.of(),
                Map.of("step_id", "changed", "fencing_token", 1L, "execution_owner", "fixture"), original.modelSpec(), "", original.requestId(), original.conversationId());
        assertThatThrownBy(() -> new BudgetedAgentModel(textModel(calls), changed, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast()).isInstanceOf(AgentBudgetExceededException.class);
        jdbc.update("UPDATE manga_agent_runs SET route='DIRECTOR' WHERE request_id=?", original.requestId());
        assertThatThrownBy(() -> new BudgetedAgentModel(textModel(calls), changed, budget, properties)
                .stream(List.of(message("fixture")), List.of(), null).blockLast()).hasMessageContaining("工作流范围不匹配");
        assertThat(calls).hasValue(1);
        assertThat(count(original, "MODEL_CALL")).isEqualTo(1);
    }

    @Test void nativeDeniedAndExternalResultsNeverPretendThatTheToolBodyRan() {
        for (boolean external : List.of(false, true)) {
            var request = workflow(AgentTaskType.MANGA_CONVERSATION);
            String name = external ? "ask_user" : "read_fixture";
            var effects = new AtomicInteger(); var calls = new AtomicInteger();
            Model model = oneToolModel(name, calls);
            var factory = fixtureFactory(name, external, effects);
            var agent = factory.buildAgent(request, workspace, BusinessSkillSelection.empty(), model);
            try {
                if (!external) {
                    agent.getDelegate().replacePermissionContext(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request),
                            io.agentscope.core.permission.PermissionContextState.builder()
                                    .mode(io.agentscope.core.permission.PermissionMode.DONT_ASK)
                                    .addDenyRule(name, new io.agentscope.core.permission.PermissionRule(name, null,
                                            io.agentscope.core.permission.PermissionBehavior.DENY, "fixture-denied")).build());
                }
                var result = agent.call(List.of(message("fixture")), new AgentScopeRuntimeContextFactory().create(request))
                        .block(Duration.ofSeconds(20));
                assertThat(result.getGenerateReason()).isEqualTo(external ? GenerateReason.TOOL_SUSPENDED : GenerateReason.ALL_TOOLS_DENIED);
            } finally { agent.close(); }
            assertThat(effects).hasValue(0);
            assertThat(calls).hasValue(1);
            assertThat(count(request, "TOOL_CALL")).isEqualTo(1);
            assertThat(toolMetadata(request).path("native_result_state").asText()).isEqualTo(external ? "running" : "denied");
            assertThat(jdbc.queryForObject("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='TOOL_CALL'",
                    String.class, request.requestId())).isEqualTo(external ? "RESERVED" : "FAILED");
        }
    }

    @Test void nativeToolResultObservationOutageRetainsBudgetWithoutReplayingItsEffect() {
        var request = workflow(AgentTaskType.MANGA_CONVERSATION);
        String constraint = "fixture_tool_result_" + user;
        jdbc.execute("ALTER TABLE agent_usage_ledger ADD CONSTRAINT " + constraint
                + " CHECK (user_id<>" + user + " OR usage_kind<>'TOOL_CALL' OR NOT(metadata ? 'native_result_state'))");
        try {
            var effects = new AtomicInteger(); var calls = new AtomicInteger();
            var agent = fixtureFactory("read_fixture", false, effects).buildAgent(request, workspace, BusinessSkillSelection.empty(),
                    oneToolModel("read_fixture", calls));
            try {
                var result = agent.call(List.of(message("fixture")), new AgentScopeRuntimeContextFactory().create(request))
                        .block(Duration.ofSeconds(20));
                assertThat(result.getTextContent()).contains("Fixture result received");
            } finally { agent.close(); }
            assertThat(effects).hasValue(1);
            assertThat(calls).hasValue(2);
            assertThat(toolMetadata(request).has("native_result_state")).isFalse();
            assertThat(jdbc.queryForObject("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='TOOL_CALL'",
                    String.class, request.requestId())).isEqualTo("RESERVED");
            assertThat(service().reserveToolCalls(request, List.of(new ToolUseBlock("fixture-call", "read_fixture", Map.of(), "{}", Map.of()))))
                    .isFalse();
            assertThat(count(request, "TOOL_CALL")).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE agent_usage_ledger DROP CONSTRAINT " + constraint); }
    }

    @Test void unicodePairsSplitAcrossTextThinkingAndToolDeltasAreMeasuredOnce() {
        properties.getAgent().setMaxOutputTokens(3);
        properties.getAgent().setMaxOutputBytes(12);
        var request = request(AgentTaskType.CHAT);
        String high = String.valueOf((char) 0xd83d), low = String.valueOf((char) 0xde00);
        Model model = new Model() {
            @Override public String getModelName() { return "fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.fromIterable(List.of(
                        TextBlock.builder().text(high).build(), TextBlock.builder().text(low).build(),
                        ThinkingBlock.builder().thinking(high).build(), ThinkingBlock.builder().thinking(low).build(),
                        new ToolUseBlock("unicode", "fixture", Map.of(), high, Map.of()),
                        new ToolUseBlock("unicode", "fixture", Map.of(), low, Map.of())))
                        .map(block -> ChatResponse.builder().content(List.of(block)).build());
            }
        };
        new BudgetedAgentModel(model, request, budget, properties).stream(List.of(message("fixture")), List.of(), null).blockLast();
        assertThat(jdbc.queryForObject("SELECT amount FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='OUTPUT_BYTE'",
                Long.class, request.requestId())).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT amount FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='OUTPUT_TOKEN'",
                Long.class, request.requestId())).isEqualTo(3);
    }

    @Test void aPartiallyDeniedNativeBatchKeepsItsSuccessfulReadAndDoesNotStopAsAllDenied() {
        var request = workflow(AgentTaskType.MANGA_CONVERSATION);
        var effects = new AtomicInteger(); var modelCalls = new AtomicInteger();
        Model model = new Model() {
            @Override public String getModelName() { return "fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (modelCalls.getAndIncrement() == 0) return Flux.just(ChatResponse.builder().content(List.of(
                        new ToolUseBlock("allowed", "read_fixture", Map.of(), "{}", Map.of()),
                        new ToolUseBlock("denied", "blocked_fixture", Map.of(), "{}", Map.of()))).build());
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("Only the allowed read completed").build())).build());
            }
        };
        var agent = fixtureFactory(Map.of("read_fixture", false, "blocked_fixture", false), effects)
                .buildAgent(request, workspace, BusinessSkillSelection.empty(), model);
        try {
            var permissions = io.agentscope.core.permission.PermissionContextState.builder()
                    .mode(io.agentscope.core.permission.PermissionMode.DONT_ASK)
                    .addAllowRule("read_fixture", new io.agentscope.core.permission.PermissionRule("read_fixture", null,
                            io.agentscope.core.permission.PermissionBehavior.ALLOW, "fixture-allowed"))
                    .addDenyRule("blocked_fixture", new io.agentscope.core.permission.PermissionRule("blocked_fixture", null,
                            io.agentscope.core.permission.PermissionBehavior.DENY, "fixture-denied")).build();
            agent.getDelegate().replacePermissionContext(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request), permissions);
            var result = agent.call(List.of(message("fixture")), new AgentScopeRuntimeContextFactory().create(request)).block(Duration.ofSeconds(20));
            assertThat(result.getGenerateReason()).isEqualTo(GenerateReason.MODEL_STOP);
            assertThat(result.getTextContent()).contains("Only the allowed read");
        } finally { agent.close(); }
        assertThat(effects).hasValue(1);
        assertThat(modelCalls).hasValue(2);
        assertThat(count(request, "TOOL_CALL")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT reservation_status FROM agent_usage_ledger WHERE root_request_id=? AND usage_kind='TOOL_CALL'",
                String.class, request.requestId())).containsExactlyInAnyOrder("FINISHED", "FAILED");
    }

    private Model oneToolModel(String name, AtomicInteger calls) {
        return new Model() {
            @Override public String getModelName() { return "fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ContentBlock output = calls.getAndIncrement() == 0
                        ? new ToolUseBlock("fixture-call", name, Map.of(), "{}", Map.of())
                        : TextBlock.builder().text("Fixture result received").build();
                return Flux.just(ChatResponse.builder().content(List.of(output)).build());
            }
        };
    }

    private AgentScopeAgentFactory fixtureFactory(String name, boolean external, AtomicInteger effects) {
        return fixtureFactory(Map.of(name, external), effects);
    }

    private AgentScopeAgentFactory fixtureFactory(Map<String, Boolean> definitions, AtomicInteger effects) {
        var registry = mock(AgentToolConfigurationRegistry.class);
        doAnswer(invocation -> {
            Toolkit toolkit = invocation.getArgument(0);
            definitions.forEach((name, external) -> toolkit.registerAgentTool(new ToolBase(ToolBase.builder().name(name).description("Fixture tool")
                        .inputSchema(Map.of("type", "object")).readOnly(!external).externalTool(external)) {
                    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        effects.incrementAndGet(); return Mono.just(ToolResultBlock.text("Fixture result"));
                    }
                }));
            return null;
        }).when(registry).configure(any(), any());
        var runs = mock(MangaAgentRunService.class);
        ToolCapabilityFixtures.mockScopes(runs);
        return new AgentScopeAgentFactory(CompactionConfig.builder().build(), properties, mock(AgentWorkspaceService.class),
                new MangaAgentPromptProvider(), new StrictAgentStateStore(new InMemoryAgentStateStore()), mock(AgentTurnStateStores.class),
                registry, mock(ArtVerseSkillRegistry.class), mock(ArtVerseSkillRepository.class), runs, mock(PostgresAgentWorkspaceStore.class),
                new AgentToolBoundaryMiddleware(runs, mock(AutomationAgentTools.class), budget));
    }

    private JsonNode toolMetadata(AgentRunRequest request) {
        return JsonUtils.getJsonCodec().fromJson(jdbc.queryForObject("""
                SELECT metadata::text FROM agent_usage_ledger
                WHERE user_id=? AND root_request_id=? AND usage_kind='TOOL_CALL' ORDER BY id DESC LIMIT 1
                """, String.class, user, request.requestId()), JsonNode.class);
    }

    private JsonNode modelMetadata(AgentRunRequest request) {
        return JsonUtils.getJsonCodec().fromJson(jdbc.queryForObject("""
                SELECT metadata::text FROM agent_usage_ledger
                WHERE user_id=? AND root_request_id=? AND usage_kind='MODEL_CALL' ORDER BY id DESC LIMIT 1
                """, String.class, user, request.requestId()), JsonNode.class);
    }

    private AgentBudgetService service() { return new AgentBudgetService(jdbc, new DataSourceTransactionManager(dataSource), properties); }
    private AgentRunRequest withExpert(AgentRunRequest parent, String name, String task) {
        String step = Objects.toString(parent.variables().get("step_id"));
        var identity = new AgentReviewExpert(name, task, parent.requestId(), step);
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var attributes = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(jdbc.queryForObject(
                    "SELECT coalesce(run_attributes_json,'{}') FROM manga_agent_runs WHERE user_id=? AND request_id=?",
                    String.class, user, parent.requestId()));
            attributes.withObject("review_expert_tasks").withObject(step).putObject(name)
                    .put("expert_id", name).put("task_id", task).put("parent_request_id", parent.requestId().toString())
                    .put("parent_step_id", step).put("profile", "local-tools-v1").put("spawn_call_id", "spawn-" + task);
            jdbc.update("UPDATE manga_agent_runs SET route='REVIEW',run_attributes_json=?::jsonb WHERE user_id=? AND request_id=?",
                    mapper.writeValueAsString(attributes), user, parent.requestId());
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        var values = new LinkedHashMap<>(parent.variables()); values.put("review_expert", identity);
        return new AgentRunRequest(parent.userId(), parent.storyId(), parent.chapterId(), parent.taskType(), List.of(),
                values, parent.modelSpec(), "", parent.requestId(), parent.conversationId(), BusinessSkillSelection.empty());
    }
    private AgentRunRequest withParent(AgentRunRequest request, UUID parent, String owner, long fence) {
        var values = new LinkedHashMap<>(request.variables());
        values.putAll(new AutomationExecutionClaim(parent, owner, fence).variables());
        var claim = new LinkedHashMap<>(new AutomationExecutionClaim(parent, owner, fence).variables());
        claim.put("child_owner", request.variables().get("execution_owner"));
        claim.put("child_fencing_token", request.variables().get("fencing_token"));
        String key = ToolIdempotencyService.sha256(claim.get("child_owner") + ":" + claim.get("child_fencing_token"));
        jdbc.update("UPDATE manga_agent_runs SET run_attributes_json=?::jsonb WHERE user_id=? AND request_id=?",
                JsonUtils.getJsonCodec().toJson(Map.of("automation_execution_claims", Map.of(key, claim))), user, request.requestId());
        return new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(), request.messages(),
                values, request.modelSpec(), "", request.requestId(), request.conversationId(), request.businessSkillSelection());
    }
    private AgentRunRequest request(AgentTaskType type) {
        return new AgentRunRequest(String.valueOf(user), 2L, 3L, type, List.of(), Map.of(), spec(), "",
                UUID.randomUUID(), UUID.randomUUID(), BusinessSkillSelection.empty());
    }
    private AgentModelSpec spec() {
        return new AgentModelSpec("fixture", "https://provider.example.test/v1", "fixture", "hash", null,
                new AgentModelCapabilities(true, false, false, "fixture-v1"));
    }
    private AgentRunRequest workflow(AgentTaskType type) {
        long story = jdbc.queryForObject("INSERT INTO stories(user_id,title) VALUES (?,'Budget fixture') RETURNING id", Long.class, user);
        long chapter = jdbc.queryForObject("INSERT INTO chapters(story_id,chapter_number) VALUES (?,1) RETURNING id", Long.class, story);
        UUID conversation = UUID.randomUUID(); UUID root = UUID.randomUUID();
        long conv = jdbc.queryForObject("""
                INSERT INTO ai_conversations(conversation_uuid,user_id,story_id,chapter_id,title,status,last_activity_at)
                VALUES (?,?,?,?,'Budget fixture','ACTIVE',clock_timestamp()) RETURNING id
                """, Long.class, conversation, user, story, chapter);
        jdbc.update("""
                INSERT INTO manga_agent_runs(user_id,story_id,chapter_id,conversation_id,request_id,status,input_message,
                    route,owner_instance_id,lease_until,fencing_token,last_progress_at)
                VALUES (?,?,?,?,?,'RUNNING','Budget fixture','STORYBOARD','fixture',clock_timestamp()+interval '90 seconds',1,clock_timestamp())
                """, user, story, chapter, conv, root);
        return new AgentRunRequest(String.valueOf(user), story, chapter, type, List.of(), Map.of("step_id", "fixture", "fencing_token", 1L, "execution_owner", "fixture"),
                spec(), "", root, conversation, BusinessSkillSelection.empty());
    }
    private long count(AgentRunRequest request, String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM agent_usage_ledger WHERE user_id=? AND root_request_id=? AND usage_kind=?",
                Long.class, user, request.requestId(), kind);
    }
    private static Msg message(String text) { return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build(); }
    private Model textModel(AtomicInteger calls) {
        return new Model() {
            @Override public String getModelName() { return "scripted-fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet(); return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("ready").build())).build());
            }
        };
    }
}
