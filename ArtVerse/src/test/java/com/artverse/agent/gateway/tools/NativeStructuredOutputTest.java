package com.artverse.agent.gateway.tools;

import com.artverse.agent.*;
import com.artverse.agent.gateway.*;
import com.artverse.application.*;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native structured paths cross the same real factory, guard and Gateway as product requests. */
class NativeStructuredOutputTest {
    @TempDir Path workspace;
    enum PathKind { NATIVE, SYNTHETIC, NATIVE_FAILURE_FALLBACK, BUDGET_EXHAUSTED, NATIVE_ONLY_FALLBACK_REJECTED }
    public record Detail(String label) { }
    public record Response(List<Detail> details) { }

    @ParameterizedTest @EnumSource(PathKind.class)
    void frameworkCapturesTheFixedOutputWithoutExposingBusinessTools(PathKind path) {
        var groups = mock(AgentToolGroupSupport.class);
        var registry = new AgentToolConfigurationRegistry(List.of(
                new MangaContextToolsStrategy(groups), new MangaConversationToolsStrategy(groups),
                new MangaDirectorToolsStrategy(groups), new MangaStoryboardToolsStrategy(groups),
                new StoryChatReadToolsStrategy(groups), new StoryChatWriteToolsStrategy(groups), new NoAgentToolsStrategy()));
        var budget = mock(AgentBudgetService.class);
        when(budget.reserveModelCall(any(), any(), anyString(), any())).thenReturn(true);
        when(budget.modelTimeAllowance(any(), any())).thenReturn(java.time.Duration.ofMinutes(3));
        if (path == PathKind.BUDGET_EXHAUSTED) when(budget.reserveModelCall(any(), any(), anyString(), any()))
                .thenThrow(new com.artverse.common.BusinessException(429, "scripted budget exhausted"));
        when(budget.measureOutput(anyString())).thenReturn(new AgentBudgetService.OutputUsage(1, 1));
        var store = new StrictAgentStateStore(new InMemoryAgentStateStore());
        var properties = new ArtVerseProperties();
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().build(), properties,
                mock(AgentWorkspaceService.class), new MangaAgentPromptProvider(), store,
                mock(AgentTurnStateStores.class), registry,
                mock(ArtVerseSkillRegistry.class), mock(ArtVerseSkillRepository.class), mock(MangaAgentRunService.class),
                mock(PostgresAgentWorkspaceStore.class), new AgentToolBoundaryMiddleware(mock(MangaAgentRunService.class),
                mock(AutomationAgentTools.class), budget));
        var capabilities = new AgentModelCapabilities(path != PathKind.NATIVE_ONLY_FALLBACK_REJECTED,
                path != PathKind.SYNTHETIC, false, "fixture-v1");
        var spec = new AgentModelSpec("fixture", "https://provider.example.test/v1", "fixture", "hash", 1L, capabilities);
        var request = new AgentRunRequest("1", 2L, 3L, AgentTaskType.MANGA_ROUTER,
                List.of(new AgentMessage("user", "Return a structured label")), Map.of(), spec, "",
                UUID.randomUUID(), UUID.randomUUID(), BusinessSkillSelection.empty());
        AtomicInteger calls = new AtomicInteger();
        Model model = new Model() {
            @Override public String getModelName() { return "scripted-structured-" + path; }
            @Override public boolean supportsNativeStructuredOutput() { return path != PathKind.SYNTHETIC; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    int attempt = calls.incrementAndGet();
                    if (options != null && options.getResponseFormat() != null) {
                        assertThat(tools).isEmpty();
                        assertThat(options.getResponseFormat().getJsonSchema().getSchema()).containsKey("properties");
                        if ((path == PathKind.NATIVE_FAILURE_FALLBACK || path == PathKind.NATIVE_ONLY_FALLBACK_REJECTED) && attempt == 1)
                            return Flux.error(new IllegalStateException("scripted native format unavailable"));
                        return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder()
                                .text("{\"details\":[{\"label\":\"ready\"}]}").build())).finishReason("stop").build());
                    }
                    assertThat(tools).extracting(ToolSchema::getName).containsExactly("generate_response");
                    var input = Map.<String, Object>of("response", Map.of("details", List.of(Map.of("label", "ready"))));
                    try {
                        return Flux.just(ChatResponse.builder().content(List.of(new ToolUseBlock("structured-call",
                                "generate_response", input, new ObjectMapper().writeValueAsString(input), Map.of())))
                                .finishReason("tool_calls").build());
                    } catch (Exception error) { return Flux.error(error); }
                });
            }
        };
        HarnessAgent agent = ReflectionTestUtils.invokeMethod(factory, "buildAgent", request, workspace,
                BusinessSkillSelection.empty(), model);
        var gatewayFactory = spy(factory);
        doReturn(agent).when(gatewayFactory).getOrCreate(eq(request), any(AgentSessionTurns.Lease.class));
        var turns = mock(AgentSessionTurns.class);
        when(turns.acquire(any(AgentRunRequest.class))).thenAnswer(ignored -> mock(AgentSessionTurns.Lease.class));
        var gateway = new AgentScopeHarnessAgentGateway(gatewayFactory, new AgentScopeRuntimeContextFactory(), properties,
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),
                budget, new AgentSessionHydrator(store), turns);
        gateway.init();
        try {
            assertThat(agent.getToolkit().getToolNames()).isEmpty();
            if (path == PathKind.BUDGET_EXHAUSTED) {
                assertThatThrownBy(() -> gateway.generateStructured(request, Response.class).block(Duration.ofSeconds(20)))
                        .hasMessageContaining("scripted budget exhausted");
                assertThat(calls.get()).isZero();
                verify(budget, times(2)).reserveModelCall(eq(request), any(), anyString(), any());
                return;
            }
            if (path == PathKind.NATIVE_ONLY_FALLBACK_REJECTED) {
                assertThatThrownBy(() -> gateway.generateStructured(request, Response.class).block(Duration.ofSeconds(20)))
                        .hasMessageContaining("synthetic");
                assertThat(calls).hasValue(1);
                verify(budget, times(1)).reserveModelCall(eq(request), any(), anyString(), any());
                return;
            }
            Response result = gateway.generateStructured(request, Response.class).block(Duration.ofSeconds(20));
            assertThat(result).isEqualTo(new Response(List.of(new Detail("ready"))));
            int expected = path == PathKind.NATIVE_FAILURE_FALLBACK ? 2 : 1;
            assertThat(calls.get()).isEqualTo(expected);
            verify(budget, times(expected)).reserveModelCall(eq(request), any(), anyString(), any());
            verify(budget, never()).reserveToolCalls(any(), anyList());
            assertThat(agent.getToolkit().getToolNames()).isEmpty();
            verifyNoInteractions(groups);
        } finally { agent.close(); }
    }
}
