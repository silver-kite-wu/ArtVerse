package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.application.*;
import com.artverse.application.automation.AutomationAgentTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.tool.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolBoundaryMiddlewareTest {
    final AgentBudgetService budget = mock(AgentBudgetService.class);
    final AgentToolBoundaryMiddleware boundary = new AgentToolBoundaryMiddleware(
            mock(MangaAgentRunService.class), mock(AutomationAgentTools.class), budget);
    final AgentRunRequest request = new AgentRunRequest("1",2L,3L,AgentTaskType.CHAT,List.of(),Map.of(),null,"");

    @Test void harnessRuntimeSkillLoaderKeepsItsNativeSource() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new io.agentscope.harness.agent.skill.runtime.SkillLoadTool());
        boundary.verifyRegistration(toolkit, Map.of(), true);
    }

    @Test void changedSchemaIsRejectedAgainstDetachedSnapshotBeforeProviderCall() {
        Toolkit toolkit = new Toolkit();
        var schema = new HashMap<String,Object>(Map.of("type","object","properties",new HashMap<>()));
        toolkit.registerAgentTool(new Fixture("read_fixture", schema));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        schema.put("additionalProperties", true);
        AtomicBoolean called = new AtomicBoolean();
        assertThatThrownBy(() -> guard.onModelCall(null, RuntimeContext.empty(),
                new ModelCallInput(List.of(), toolkit.getToolSchemas(), null, null),
                input -> { called.set(true); return Flux.empty(); }).blockLast())
                .hasMessageContaining("TOOL_NOT_ALLOWED");
        assertThat(called).isFalse();
        verifyNoInteractions(budget);
    }

    @Test void wholeBatchIsCheckedBeforeBudgetOrExecutionAndNoSkillBypassExists() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type","object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        AtomicBoolean called = new AtomicBoolean();
        assertThatThrownBy(() -> guard.onActing(null, RuntimeContext.empty(), new ActingInput(List.of(
                new ToolUseBlock("read", "read_fixture", Map.of(), "{}", Map.of()),
                new ToolUseBlock("skill", "load_skill_through_path", Map.of()))),
                input -> { called.set(true); return Flux.empty(); }).blockLast())
                .hasMessageContaining("TOOL_NOT_ALLOWED");
        assertThat(called).isFalse();
        verifyNoInteractions(budget);
    }

    @Test void sameNameReplacementCannotBorrowTheOriginalContract() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type","object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type","object")));
        assertThatThrownBy(() -> guard.onActing(null, RuntimeContext.empty(),
                new ActingInput(List.of(new ToolUseBlock("call", "read_fixture", Map.of()))),
                input -> Flux.empty()).blockLast()).hasMessageContaining("TOOL_NOT_ALLOWED");
        verifyNoInteractions(budget);
    }

    @Test void unsealedBoundaryCannotExecute() {
        var guard = boundary.forRun(request, new Toolkit());
        assertThatThrownBy(() -> guard.onActing(null, RuntimeContext.empty(), new ActingInput(List.of()),
                input -> Flux.empty()).blockLast()).hasMessageContaining("sealed");
    }

    @Test void schemaChangesDuringBuildCannotBecomeTheAcceptedBaseline() {
        Toolkit toolkit = new Toolkit();
        var schema = new HashMap<String,Object>(Map.of("type", "object"));
        toolkit.registerAgentTool(new Fixture("read_fixture", schema));
        var guard = boundary.forRun(request, toolkit);
        schema.put("additionalProperties", true);
        assertThatThrownBy(() -> guard.seal(toolkit, false)).hasMessageContaining("during Harness build");
    }

    @Test void mismatchedOrAmbiguousInputIsRejectedBeforeAnySideEffect() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type", "object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        for (String raw : List.of("{\"chapter_id\":3}", "{\"chapter_id\":3,\"chapter_id\":9}",
                "{\"chapter_id\":9} {\"chapter_id\":3}")) {
            var call = new ToolUseBlock("call", "read_fixture", Map.of("chapter_id", 9), raw, Map.of());
            assertThatThrownBy(() -> guard.onActing(null, RuntimeContext.empty(), new ActingInput(List.of(call)),
                    input -> { throw new AssertionError("No execution allowed"); }).blockLast())
                    .hasMessageContaining("TOOL_NOT_ALLOWED");
        }
        verifyNoInteractions(budget);
    }

    @Test void ordinaryRunsCannotBorrowTheFrameworkOutputCaptureName() {
        Toolkit toolkit = new Toolkit();
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        var schema = io.agentscope.core.model.ToolSchema.builder().name(AgentStructuredOutput.NAME).description("fixture")
                .parameters(Map.of("type", "object")).build();
        assertThatThrownBy(() -> guard.onModelCall(null, RuntimeContext.empty(),
                new ModelCallInput(List.of(), List.of(schema), null, null), input -> Flux.empty()).blockLast())
                .hasMessageContaining("TOOL_NOT_ALLOWED");
        toolkit.registerAgentTool(new Fixture(AgentStructuredOutput.NAME, Map.of("type", "object")));
        assertThatThrownBy(() -> boundary.forRun(request, toolkit)).hasMessageContaining("reserved");
        verifyNoInteractions(budget);
    }

    @Test void equalJsonIntegersKeepTheirMeaningAcrossJavaLongAndParsedInteger() {
        var toolkit = new Toolkit(); toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type", "object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        var call = new ToolUseBlock("long-id", "read_fixture", Map.of("revision_id", 7L), "{\"revision_id\":7}", Map.of());
        when(budget.reserveToolCalls(request, List.of(call))).thenReturn(true);
        var invoked = new AtomicBoolean();
        guard.onActing(null, RuntimeContext.empty(), new ActingInput(List.of(call)),
                input -> { invoked.set(true); return Flux.empty(); }).blockLast();
        assertThat(invoked).isTrue();
    }

    @Test void outputTypeAndTheActualSchemaMustMatchBeforeAnyProviderRequest() {
        Toolkit toolkit = new Toolkit();
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(AgentStructuredOutput.class, new AgentStructuredOutput(request, Output.class, 2));
        var wrong = io.agentscope.core.model.ToolSchema.builder().name(AgentStructuredOutput.NAME).description("fixture")
                .parameters(Map.of("type", "object")).build();
        assertThatThrownBy(() -> guard.onModelCall(null, ctx,
                new ModelCallInput(List.of(), List.of(wrong), null, null), input -> Flux.empty()).blockLast())
                .hasMessageContaining("TOOL_NOT_ALLOWED");
        verifyNoInteractions(budget);
    }

    @Test void invalidSecondSchemaPreventsTheWholeBatchFromBeingReservedOrDispatched() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type", "object", "properties",
                Map.of("limit", Map.of("type", "integer", "minimum", 1)), "required", List.of("limit"))));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        var valid = new ToolUseBlock("first", "read_fixture", Map.of("limit", 1), "{\"limit\":1}", Map.of());
        var invalid = new ToolUseBlock("second", "read_fixture", Map.of("limit", 0), "{\"limit\":0}", Map.of());
        assertThatThrownBy(() -> guard.onActing(null, RuntimeContext.empty(), new ActingInput(List.of(valid, invalid)),
                input -> { throw new AssertionError("Native execution must not start"); }).blockLast())
                .hasMessageContaining("native schema validation");
        verifyNoInteractions(budget);
    }

    @Test void aValidBatchUsesOneReservationBoundaryAndCannotReplayAPriorReservation() {
        Toolkit toolkit = new Toolkit(); toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type", "object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        var call = new ToolUseBlock("first", "read_fixture", Map.of(), "{}", Map.of());
        when(budget.reserveToolCalls(request, List.of(call))).thenReturn(true, false);
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        guard.onActing(null, RuntimeContext.empty(), new ActingInput(List.of(call)),
                input -> { effects.incrementAndGet(); return Flux.empty(); }).blockLast();
        assertThatThrownBy(() -> guard.onActing(null, RuntimeContext.empty(), new ActingInput(List.of(call)),
                input -> { effects.incrementAndGet(); return Flux.empty(); }).blockLast()).hasMessageContaining("已预占");
        assertThat(effects).hasValue(1);
        verify(budget, times(2)).reserveToolCalls(request, List.of(call));
    }

    @Test void aForeignChildResultCannotCompleteOrStopTheParentAttempt() {
        Toolkit toolkit = new Toolkit(); toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type", "object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        var call = new ToolUseBlock("parent-call", "read_fixture", Map.of(), "{}", Map.of());
        when(budget.reserveToolCalls(request, List.of(call))).thenReturn(true);
        var foreign = new io.agentscope.core.event.ToolResultEndEvent("child-reply", "child-call", "read_fixture",
                io.agentscope.core.message.ToolResultState.DENIED);
        var events = guard.onActing(null, RuntimeContext.empty(), new ActingInput(List.of(call)), input -> Flux.just(foreign))
                .collectList().block();
        assertThat(events).containsExactly(foreign);
        verify(budget).observeToolResult(eq(request), eq("parent-call"), eq("read_fixture"), anyString(), isNull());
        verify(budget, never()).observeToolResult(any(), eq("child-call"), anyString(), anyString(), any());
    }

    @Test void requestLevelSchemaOverridesCannotBorrowASealedBusinessToolName() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new Fixture("read_fixture", Map.of("type", "object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        var replacement = new SchemaOnlyTool("read_fixture", "fixture", Map.of("type", "object"));
        for (var config : List.of(
                new ToolRequestConfig(Map.of("read_fixture", replacement), ToolMergeMode.MERGE_EXTERNAL_PRIORITY),
                new ToolRequestConfig(Map.of(), ToolMergeMode.EXTERNAL_ONLY))) {
            RuntimeContext ctx = RuntimeContext.builder().toolRequestConfig(config).build();
            assertThatThrownBy(() -> guard.onModelCall(null, ctx,
                    new ModelCallInput(List.of(), toolkit.getToolSchemas(), null, null), input -> Flux.empty()).blockLast())
                    .hasMessageContaining("request-level tool overrides");
            assertThatThrownBy(() -> guard.onActing(null, ctx,
                    new ActingInput(List.of(new ToolUseBlock("call", "read_fixture", Map.of(), "{}", Map.of()))),
                    input -> Flux.empty()).blockLast()).hasMessageContaining("request-level tool overrides");
        }
        // The ordinary native Toolkit contract remains available, including registered external ask_user.
        var called = new AtomicBoolean();
        RuntimeContext ordinary = RuntimeContext.builder().toolRequestConfig(ToolRequestConfig.NONE).build();
        guard.onModelCall(null, ordinary, new ModelCallInput(List.of(), toolkit.getToolSchemas(), null, null),
                input -> { called.set(true); return Flux.empty(); }).blockLast();
        assertThat(called).isTrue();
        verifyNoInteractions(budget);
    }

    @Test void onlyACompleteSuccessfulNativeChapterResultRegistersItsActualReadPayload() {
        var receipts = mock(StoryboardArtifactService.class);
        boundary.setStoryboardArtifacts(receipts);
        var execution = new MangaAgentRuntimeContext(1L, 2L, 3L, UUID.randomUUID(), UUID.randomUUID(), null);
        RuntimeContext ctx = RuntimeContext.empty(); ctx.put(MangaAgentRuntimeContext.class, execution);
        Toolkit toolkit = new Toolkit(); toolkit.registerAgentTool(new Fixture("get_chapter_context", Map.of("type", "object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        var call = new ToolUseBlock("read", "get_chapter_context", Map.of(), "{}", Map.of());
        when(budget.reserveToolCalls(request, List.of(call))).thenReturn(true);
        var start = new io.agentscope.core.event.ToolResultStartEvent("reply", "read", "get_chapter_context");
        var first = new io.agentscope.core.event.ToolResultTextDeltaEvent("reply", "read", "get_chapter_context", "{\"read_ref\":\"ref\",");
        var foreign = new io.agentscope.core.event.ToolResultTextDeltaEvent("other", "child", "get_chapter_context", "foreign");
        var last = new io.agentscope.core.event.ToolResultTextDeltaEvent("reply", "read", "get_chapter_context", "\"source\":\"漫画\"}");
        var end = new io.agentscope.core.event.ToolResultEndEvent("reply", "read", "get_chapter_context",
                io.agentscope.core.message.ToolResultState.SUCCESS);
        var events = guard.onActing(null, ctx, new ActingInput(List.of(call)), input -> Flux.just(start, first, foreign, last)
                .doOnComplete(() -> verifyNoInteractions(receipts)).concatWith(Flux.just(end))).collectList().block();
        assertThat(events).containsExactly(start, first, foreign, last, end);
        verify(receipts).recordReadResult(execution, "read", Map.of("read_ref", "ref", "source", "漫画"));
    }

    @Test void deniedOrFailedOrTruncatedChapterResultsCannotCreateReadEvidence() {
        var receipts = mock(StoryboardArtifactService.class);
        boundary.setStoryboardArtifacts(receipts);
        var execution = new MangaAgentRuntimeContext(1L, 2L, 3L, UUID.randomUUID(), UUID.randomUUID(), null);
        RuntimeContext ctx = RuntimeContext.empty(); ctx.put(MangaAgentRuntimeContext.class, execution);
        Toolkit toolkit = new Toolkit(); toolkit.registerAgentTool(new Fixture("get_chapter_context", Map.of("type", "object")));
        var guard = boundary.forRun(request, toolkit); guard.seal(toolkit, false);
        var call = new ToolUseBlock("read", "get_chapter_context", Map.of(), "{}", Map.of());
        when(budget.reserveToolCalls(request, List.of(call))).thenReturn(true);
        for (var state : List.of(io.agentscope.core.message.ToolResultState.ERROR, io.agentscope.core.message.ToolResultState.DENIED,
                io.agentscope.core.message.ToolResultState.RUNNING)) {
            guard.onActing(null, ctx, new ActingInput(List.of(call)), input -> Flux.just(
                    new io.agentscope.core.event.ToolResultEndEvent("reply", "read", "get_chapter_context", state))).blockLast();
        }
        for (var result : List.of(
                Flux.<io.agentscope.core.event.AgentEvent>just(new io.agentscope.core.event.ToolResultStartEvent("reply", "read", "get_chapter_context"),
                        new io.agentscope.core.event.ToolResultTextDeltaEvent("reply", "read", "get_chapter_context", "{")),
                Flux.<io.agentscope.core.event.AgentEvent>just(new io.agentscope.core.event.ToolResultStartEvent("reply", "read", "get_chapter_context"),
                        new io.agentscope.core.event.ToolResultTextDeltaEvent("other", "read", "get_chapter_context", "{}")),
                Flux.<io.agentscope.core.event.AgentEvent>just(new io.agentscope.core.event.ToolResultStartEvent("reply", "read", "get_chapter_context"),
                        new io.agentscope.core.event.ToolResultTextDeltaEvent("reply", "read", "get_chapter_context", "x".repeat(256001))),
                Flux.<io.agentscope.core.event.AgentEvent>empty())) {
            assertThatThrownBy(() -> guard.onActing(null, ctx, new ActingInput(List.of(call)), input -> result.concatWith(
                    Flux.just(new io.agentscope.core.event.ToolResultEndEvent("reply", "read", "get_chapter_context",
                            io.agentscope.core.message.ToolResultState.SUCCESS)))).blockLast()).hasMessageContaining("READ_RECEIPT_UNAVAILABLE");
        }
        verifyNoInteractions(receipts);
    }

    record Output(String label) { }

    static final class Fixture extends ToolBase {
        Fixture(String name, Map<String,Object> schema) {
            super(ToolBase.builder().name(name).description("fixture").inputSchema(schema).readOnly(true));
        }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.error(new AssertionError("Guard tests must not execute tools"));
        }
    }
}
