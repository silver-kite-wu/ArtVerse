package com.artverse.agent.gateway;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.*;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import io.agentscope.extensions.redis.state.lettuce.LettuceClientAdapter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.artverse.common.BusinessException;
import com.artverse.agent.*;
import com.artverse.application.*;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.agent.gateway.tools.AgentToolConfigurationRegistry;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Native state/CAS and the real Redis turn-gate scripts; no paid provider requests. */
@Testcontainers
class AgentSessionStateIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379).withImagePullPolicy(ignored -> false);
    @TempDir Path workspace;
    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;
    static RedisClient client;
    static RedisClientAdapter nativeClient;
    static RedisAgentStateStore nativeStore;

    @BeforeAll static void connect() {
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        nativeClient = LettuceClientAdapter.of(client);
        nativeStore = RedisAgentStateStore.builder().clientAdapter(nativeClient)
                .keyPrefix(AgentTurnStateStores.KEY_PREFIX).build();
    }

    @AfterAll static void disconnect() {
        if (nativeStore != null) nativeStore.close();
        else if (client != null) client.shutdown();
        if (connections != null) connections.destroy();
    }

    @Test void overlappingNativeHarnessCallsCannotOverwriteAfterCasConflict() throws Exception {
        var strict = new StrictAgentStateStore(nativeStore);
        String session = "cas-" + UUID.randomUUID();
        var context = RuntimeContext.builder().userId("actor").sessionId(session).build();
        var bothLoaded = new CountDownLatch(2);
        var releaseFirst = new CountDownLatch(1);
        var releaseStale = new CountDownLatch(1);
        var first = agent(strict, controlled("first", bothLoaded, releaseFirst));
        var stale = agent(strict, controlled("stale", bothLoaded, releaseStale));
        var messages = List.of(Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("test").build()).build());
        try {
            var firstCall = first.call(messages, context).toFuture();
            var staleCall = stale.call(messages, RuntimeContext.builder().userId("actor").sessionId(session).build()).toFuture();
            assertThat(bothLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            assertThat(firstCall.get(10, TimeUnit.SECONDS).getTextContent()).isEqualTo("first");
            releaseStale.countDown();
            assertThatThrownBy(() -> staleCall.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ConcurrentSessionModificationException.class);
        } finally {
            releaseFirst.countDown();
            releaseStale.countDown();
            first.close();
            stale.close();
        }
        String saved = nativeStore.get("actor", session, "agent_state", AgentState.class).orElseThrow()
                .getContext().stream().map(Msg::getTextContent).reduce("", String::concat);
        assertThat(saved).contains("first").doesNotContain("stale");
        // Closing a per-run view must not close the application's shared Redis connection.
        assertThat(strict.getVersioned("actor", session, "agent_state", AgentState.class).version()).isEqualTo(1);
    }

    @Test void strictStoreRejectsAnUnconditionalOrStaleNativeCheckpoint() {
        var strict = new StrictAgentStateStore(nativeStore);
        String session = "strict-" + UUID.randomUUID();
        var state = AgentState.builder().userId("actor").sessionId(session).build();
        assertThat(strict.saveIfVersion("actor", session, "agent_state", state, 0)).isPositive();
        assertThatThrownBy(() -> strict.saveIfVersion("actor", session, "agent_state", state, 0))
                .isInstanceOf(ConcurrentSessionModificationException.class);
        assertThatThrownBy(() -> strict.saveIfVersion("actor", session, "agent_state", state, AgentStateStore.UNVERSIONED))
                .isInstanceOf(ConcurrentSessionModificationException.class);
        assertThatThrownBy(() -> strict.save("actor", session, "agent_state", state))
                .isInstanceOf(ConcurrentSessionModificationException.class);
    }

    @Test void differentWorkersUseTheSameRedisTurnAndReleaseOnlyTheirOwnLease() throws Exception {
        var firstWorker = new AgentSessionTurns(nativeClient);
        var nextWorker = new AgentSessionTurns(nativeClient);
        String key = "actor:turn-" + UUID.randomUUID();
        var original = firstWorker.acquire(key);
        var lost = new AtomicInteger();
        original.onLost(lost::incrementAndGet);
        assertThatThrownBy(() -> nextWorker.acquire(key)).isInstanceOf(TurnBusyException.class);
        original.requireOwned();
        firstWorker.renewOwnedTurns();
        original.requireOwned();
        // Simulate lease expiration without waiting or changing global Redis state.
        String redisKey = org.springframework.test.util.ReflectionTestUtils.invokeMethod(firstWorker, "redisKey", key);
        redis.delete(redisKey);
        try (var replacement = nextWorker.acquire(key)) {
            firstWorker.renewOwnedTurns();
            assertThat(lost).hasValue(1);
            assertThatThrownBy(original::requireOwned).hasMessageContaining("执行权已失效");
            original.close();
            original.close();
            replacement.requireOwned();
            assertThatThrownBy(() -> firstWorker.acquire(key)).isInstanceOf(TurnBusyException.class);
        } finally { original.close(); }
        assertThat(firstWorker.isRunning(key)).isFalse();
        try (var later = firstWorker.acquire(key)) { later.requireOwned(); }
    }

    @Test void actorsWithTheSameSessionSuffixRemainIsolated() throws Exception {
        var gate = new AgentSessionTurns(nativeClient);
        String suffix = UUID.randomUUID().toString();
        try (var first = gate.acquire("actor-a:" + suffix); var second = gate.acquire("actor-b:" + suffix)) {
            first.requireOwned();
            second.requireOwned();
        }
    }

    @Test void atomicOwnershipGuardRejectsLostTurnBeforeAnyReplacementCheckpoint() throws Exception {
        var gate = new AgentSessionTurns(nativeClient);
        var stores = new AgentTurnStateStores(nativeClient, false);
        String session = "lost-before-save-" + UUID.randomUUID(), key = "actor:" + session;
        var original = gate.acquire(key);
        var oldView = stores.forTurn("actor", session, original);
        var originalLost = new AtomicInteger();
        original.onLost(originalLost::incrementAndGet);
        original.requireOwned();
        // The ordinary pre-check succeeded. Ownership changes before native EVAL.
        redis.delete(org.springframework.test.util.ReflectionTestUtils.<String>invokeMethod(gate, "redisKey", key));
        try (var replacement = gate.acquire(key); var newView = stores.forTurn("actor", session, replacement)) {
            var oldState = state("actor", session, "old");
            assertThatThrownBy(() -> oldView.saveIfVersion("actor", session, "agent_state", oldState, 0))
                    .hasRootCauseInstanceOf(BusinessException.class);
            assertThat(nativeStore.getVersioned("actor", session, "agent_state", AgentState.class).version()).isZero();
            assertThat(originalLost).hasValue(1);
            assertThat(newView.saveIfVersion("actor", session, "agent_state", state("actor", session, "new"), 0)).isEqualTo(1);
            original.close();
            oldView.close();
            assertThatThrownBy(() -> oldView.saveIfVersion("actor", session, "agent_state", oldState, 1))
                    .isInstanceOf(BusinessException.class);
            replacement.requireOwned();
            assertThat(nativeStore.getVersioned("actor", session, "agent_state", AgentState.class).value()
                    .getContext().getFirst().getTextContent()).isEqualTo("new");
        } finally { oldView.close(); original.close(); }
    }

    @Test void stableReadsDoNotReturnOldPayloadWithTheNewVersion() {
        String session = "torn-read-" + UUID.randomUUID();
        assertThat(nativeStore.saveIfVersion("actor", session, "agent_state", state("actor", session, "old"), 0)).isEqualTo(1);
        var observed = spy(nativeClient);
        var changed = new AtomicBoolean();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String payload = nativeClient.get(key);
            if (key.endsWith(":agent_state") && changed.compareAndSet(false, true)) {
                assertThat(nativeStore.saveIfVersion("actor", session, "agent_state", state("actor", session, "new"), 1)).isEqualTo(2);
            }
            return payload;
        }).when(observed).get(anyString());
        var observedNative = RedisAgentStateStore.builder().keyPrefix(AgentTurnStateStores.KEY_PREFIX)
                .clientAdapter(observed).build();
        var strict = new StrictAgentStateStore(observedNative);
        var snapshot = strict.getVersioned("actor", session, "agent_state", AgentState.class);
        assertThat(changed).isTrue();
        assertThat(snapshot.version()).isEqualTo(2);
        assertThat(snapshot.value().getContext().getFirst().getTextContent()).isEqualTo("new");
        // This instrumentation borrows the shared client and must not close it.
    }

    @Test void unstableReadsStopAfterTheFiniteRetryLimit() {
        String session = "unstable-" + UUID.randomUUID();
        var observed = spy(nativeClient);
        var reads = new AtomicInteger();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String payload = nativeClient.get(key);
            if (key.endsWith(":agent_state")) {
                int previous = reads.getAndIncrement();
                nativeStore.saveIfVersion("actor", session, "agent_state", state("actor", session, "changed"), previous);
            }
            return payload;
        }).when(observed).get(anyString());
        var strict = new StrictAgentStateStore(RedisAgentStateStore.builder().keyPrefix(AgentTurnStateStores.KEY_PREFIX)
                .clientAdapter(observed).build());
        assertThatThrownBy(() -> strict.getVersioned("actor", session, "agent_state", AgentState.class))
                .isInstanceOf(ConcurrentSessionModificationException.class);
        assertThat(reads).hasValue(6);
    }

    @Test void scopedViewsRejectOtherActorsAndSessionsAndSharedViewRejectsWrites() throws Exception {
        String session = "scope-" + UUID.randomUUID();
        var gate = new AgentSessionTurns(nativeClient);
        try (var lease = gate.acquire("actor:" + session);
             var view = new AgentTurnStateStores(nativeClient, false).forTurn("actor", session, lease)) {
            assertThatThrownBy(() -> view.getVersioned("other", session, "agent_state", AgentState.class))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> view.saveIfVersion("actor", session + "other", "agent_state", state("actor", session, "x"), 0))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> view.delete("actor", session)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> new AgentTurnStateStores(nativeClient, true).forTurn("actor", session, lease))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> StrictAgentStateStore.readOnly(nativeStore)
                    .saveIfVersion("actor", session, "agent_state", state("actor", session, "x"), 0))
                    .isInstanceOf(BusinessException.class);
            assertThat(view.saveIfVersion("actor", session, "agent_state", state("actor", session, "owned"), 0)).isEqualTo(1);
        }
        // Neither the per-turn native store nor the shared read view owns the client.
        assertThat(nativeStore.getVersioned("actor", session, "agent_state", AgentState.class).version()).isEqualTo(1);
    }

    @Test void aLateLossHandlerIsNotifiedExactlyOnce() throws Exception {
        var gate = new AgentSessionTurns(nativeClient);
        String key = "actor:late-loss-" + UUID.randomUUID();
        try (var lease = gate.acquire(key)) {
            redis.delete(org.springframework.test.util.ReflectionTestUtils.<String>invokeMethod(gate, "redisKey", key));
            assertThatThrownBy(lease::requireOwned).isInstanceOf(BusinessException.class);
            var count = new AtomicInteger();
            lease.onLost(count::incrementAndGet);
            gate.renewOwnedTurns();
            assertThatThrownBy(lease::requireOwned).isInstanceOf(BusinessException.class);
            assertThat(count).hasValue(1);
        }
    }

    @Test void failedNativeCallCannotPersistAfterAnotherWorkerTakesOwnership() throws Exception {
        var gate = new AgentSessionTurns(nativeClient);
        var stores = new AgentTurnStateStores(nativeClient, false);
        String session = "failed-owner-" + UUID.randomUUID(), key = "actor:" + session;
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failing = new Model() {
            @Override public String getModelName() { return "scripted-failure"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                entered.countDown();
                return reactor.core.publisher.Mono.<ChatResponse>fromCallable(() -> {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Failure barrier timed out");
                    throw new IllegalStateException("scripted model failure");
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).flux();
            }
        };
        try (var original = gate.acquire(key); var oldView = stores.forTurn("actor", session, original)) {
            var agent = agent(oldView, failing);
            try {
                var call = agent.call(List.of(Msg.builder().role(MsgRole.USER)
                        .content(TextBlock.builder().text("old-input").build()).build()),
                        RuntimeContext.builder().userId("actor").sessionId(session).build()).toFuture();
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                redis.delete(org.springframework.test.util.ReflectionTestUtils.<String>invokeMethod(gate, "redisKey", key));
                try (var replacement = gate.acquire(key); var newView = stores.forTurn("actor", session, replacement)) {
                    assertThat(newView.saveIfVersion("actor", session, "agent_state", state("actor", session, "replacement"), 0)).isEqualTo(1);
                    release.countDown();
                    assertThatThrownBy(() -> call.get(10, TimeUnit.SECONDS)).hasRootCauseMessage("scripted model failure");
                    agent.close();
                    replacement.requireOwned();
                    var saved = nativeStore.getVersioned("actor", session, "agent_state", AgentState.class);
                    assertThat(saved.version()).isEqualTo(1);
                    assertThat(saved.value().getContext()).extracting(Msg::getTextContent).containsExactly("replacement");
                }
            } finally { release.countDown(); agent.close(); }
        }
    }

    @Test void productionFactoryInstallsTheOwnedViewInsteadOfTheSharedReadView() {
        var gate = new AgentSessionTurns(nativeClient);
        var request = new AgentRunRequest("1", 2L, 3L, AgentTaskType.CHAT,
                List.of(new AgentMessage("user", "fixture")), Map.of(),
                new AgentModelSpec("fixture", "https://provider.example.test/v1", "fixture", "hash"),
                "fixture-not-a-real-key", UUID.randomUUID(), UUID.randomUUID());
        var workspaces = mock(AgentWorkspaceService.class);
        when(workspaces.workspaceFor(request)).thenReturn(workspace);
        var skills = mock(ArtVerseSkillRegistry.class);
        when(skills.resolveSelection(request)).thenReturn(BusinessSkillSelection.empty());
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().build(), new ArtVerseProperties(),
                workspaces, new MangaAgentPromptProvider(), StrictAgentStateStore.readOnly(nativeStore),
                new AgentTurnStateStores(nativeClient, false), mock(AgentToolConfigurationRegistry.class), skills,
                mock(ArtVerseSkillRepository.class), mock(MangaAgentRunService.class), mock(PostgresAgentWorkspaceStore.class),
                new AgentToolBoundaryMiddleware(mock(MangaAgentRunService.class), mock(AutomationAgentTools.class), mock(AgentBudgetService.class)));
        try (var lease = gate.acquire(request)) {
            var harness = factory.getOrCreate(request, lease);
            var view = harness.getStateStore();
            String session = AgentScopeRuntimeContextFactory.createSessionId(request);
            try {
                // replacePermissionContext during real Factory assembly saved through the owner guard.
                assertThat(view.getVersioned("1", session, "agent_state", AgentState.class).version()).isPositive();
                assertThatThrownBy(() -> view.get("2", session, "agent_state", AgentState.class))
                        .isInstanceOf(BusinessException.class);
            } finally { harness.close(); view.close(); }
            assertThatThrownBy(() -> view.get("1", session, "agent_state", AgentState.class))
                    .isInstanceOf(BusinessException.class);
            lease.requireOwned();
        }
    }

    @Test void realGatewayGateRejectsConcurrentExecutionBeforeFactoryAccess() {
        var gate = new AgentSessionTurns(nativeClient);
        var factory = mock(AgentScopeAgentFactory.class);
        var request = new AgentRunRequest("1", 2L, 3L, AgentTaskType.CHAT,
                List.of(new AgentMessage("user", "fixture")), Map.of(), null, "",
                UUID.randomUUID(), UUID.randomUUID());
        var gateway = new AgentScopeHarnessAgentGateway(factory, new AgentScopeRuntimeContextFactory(),
                new ArtVerseProperties(), CircuitBreakerRegistry.ofDefaults(), null, null, gate);
        gateway.init();
        try (var lease = gate.acquire(request)) {
            assertThatThrownBy(() -> gateway.generateText(request).block(Duration.ofSeconds(10)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("会话正在执行");
            verifyNoInteractions(factory);
            lease.requireOwned();
        }
    }

    private static AgentState state(String actor, String session, String text) {
        return AgentState.builder().userId(actor).sessionId(session).context(List.of(
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build())).build();
    }

    private static Model controlled(String answer, CountDownLatch entered, CountDownLatch release) {
        return new Model() {
            @Override public String getModelName() { return "scripted-overlap"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                entered.countDown();
                return reactor.core.publisher.Mono.fromCallable(() -> {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Model barrier timed out");
                    return ChatResponse.builder().content(List.of(TextBlock.builder().text(answer).build()))
                            .finishReason("stop").build();
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).flux();
            }
        };
    }

    private HarnessAgent agent(AgentStateStore store, String answer) {
        return agent(store, new Model() {
            @Override public String getModelName() { return "scripted-cas"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(answer).build()))
                        .finishReason("stop").build());
            }
        });
    }

    private HarnessAgent agent(AgentStateStore store, Model model) {
        return HarnessAgent.builder().name("cas-test").model(model).workspace(workspace)
                .stateStore(store).disableCompaction().disableTranscript()
                .disableShellTool().disableFilesystemTools().disableWebTools()
                .disableMemoryTools().disableMemoryHooks().disableSubagents().disableDynamicSubagents()
                .disableDynamicSkills().disableDefaultWorkspaceSkills().disableWorkspaceContext()
                .disableAtPathExpansion().disableToolsConfig().build();
    }
}
