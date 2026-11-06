package com.artverse.agent.gateway.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Exercises the real native schema -> param/preset merge -> local invocation boundary. */
class AgentToolInvocationGuardTest {
    final ObjectMapper json = new ObjectMapper();

    @Test void nativeParamInputCannotOverrideTheValidatedRawArgument() {
        var commands = new CommandFixture();
        Toolkit toolkit = published(commands);
        ToolUseBlock call = call("guard_fixture", Map.of("value", "approved"));
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call)
                .input(Map.of("value", "changed")).runtimeContext(RuntimeContext.empty()).build()).block();
        assertThat(result.getOutput().toString()).contains("TOOL_NOT_ALLOWED");
        assertThat(commands.effects).hasValue(0);
        var legal = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call)
                .input(call.getInput()).runtimeContext(RuntimeContext.empty()).build()).block();
        assertThat(legal.getOutput().toString()).contains("approved");
        assertThat(commands.effects).hasValue(1);
    }

    @Test void nativePresetChangesAreBlockedAfterPublicationAndInCopiedToolkit() {
        var commands = new CommandFixture();
        Toolkit toolkit = published(commands);
        ToolUseBlock call = call("guard_fixture", Map.of("value", "approved"));
        toolkit.updateToolPresetParameters("guard_fixture", Map.of("value", "changed"));
        for (Toolkit actual : List.of(toolkit, toolkit.copy())) {
            var result = actual.callTool(ToolCallParam.builder().toolUseBlock(call)
                    .runtimeContext(RuntimeContext.empty()).build()).block();
            assertThat(result.getOutput().toString()).contains("TOOL_NOT_ALLOWED");
        }
        assertThat(commands.effects).hasValue(0);
        toolkit.updateToolPresetParameters("guard_fixture", Map.of());
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call)
                .runtimeContext(RuntimeContext.empty()).build()).block();
        assertThat(result.getOutput().toString()).contains("approved");
        assertThat(commands.effects).hasValue(1);
    }

    @Test void nativePresetRegistrationOnAGuardedInstanceCannotInjectBusinessInput() {
        var commands = new CommandFixture();
        Toolkit published = published(commands);
        Toolkit toolkit = new Toolkit();
        toolkit.registration().agentTool(published.getTool("guard_fixture"))
                .presetParameters(Map.of("guard_fixture", Map.of("value", "changed"))).apply();
        var result = toolkit.callTool(ToolCallParam.builder()
                .toolUseBlock(call("guard_fixture", Map.of("value", "approved"))).build()).block();
        assertThat(result.getOutput().toString()).contains("TOOL_NOT_ALLOWED");
        assertThat(commands.effects).hasValue(0);
    }

    @Test void originalBlockInputMustMatchRawEvenWhenParamInputMatchesRaw() {
        var commands = new CommandFixture();
        Toolkit toolkit = published(commands);
        ToolUseBlock call = new ToolUseBlock("call", "guard_fixture", Map.of("value", "hidden"),
                "{\"value\":\"approved\"}", Map.of());
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call)
                .input(Map.of("value", "approved")).build()).block();
        assertThat(result.getOutput().toString()).contains("TOOL_NOT_ALLOWED");
        assertThat(commands.effects).hasValue(0);
    }

    @Test void duplicateRawKeysAreRejectedAtFinalInvocation() {
        var commands = new CommandFixture();
        Toolkit toolkit = published(commands);
        ToolUseBlock call = new ToolUseBlock("call", "guard_fixture", Map.of("value", "approved"),
                "{\"value\":\"hidden\",\"value\":\"approved\"}", Map.of());
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).build()).block();
        assertThat(result.getOutput().toString()).contains("TOOL_NOT_ALLOWED");
        assertThat(commands.effects).hasValue(0);
    }

    @Test void aSecondRawJsonValueCannotBeHiddenAfterAValidArgumentObject() {
        var commands = new CommandFixture();
        Toolkit toolkit = published(commands);
        ToolUseBlock call = new ToolUseBlock("call", "guard_fixture", Map.of("value", "approved"),
                "{\"value\":\"approved\"} {\"value\":\"hidden\"}", Map.of());
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).build()).block();
        assertThat(result.getOutput().toString()).containsAnyOf("TOOL_NOT_ALLOWED", "validation");
        assertThat(commands.effects).hasValue(0);
    }

    @Test void nestedArgumentsAreDetachedBeforeNativeDelegateContinuesAsynchronously() throws Exception {
        var proceed = Sinks.<String>one();
        var actual = new AtomicReference<ToolCallParam>();
        ToolBase original = new ToolBase(ToolBase.builder().name("nested_fixture").description("fixture")
                .inputSchema(Map.of("type", "object", "properties", Map.of("payload", Map.of("type", "object"))))) {
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                actual.set(param);
                return proceed.asMono().map(ignored -> ToolResultBlock.text(param.getInput().toString()));
            }
        };
        Toolkit toolkit = published(original);
        var nested = new LinkedHashMap<String, Object>(Map.of("value", "approved"));
        var input = Map.<String, Object>of("payload", nested);
        ToolUseBlock call = call("nested_fixture", input);
        var future = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).input(input).build()).toFuture();
        assertThat(actual.get()).isNotNull();
        nested.put("value", "changed");
        assertThat(proceed.tryEmitValue("go")).isEqualTo(Sinks.EmitResult.OK);
        assertThat(future.get().getOutput().toString()).contains("approved").doesNotContain("changed");
        assertThat(actual.get().getToolUseBlock().getInput().toString()).contains("approved");
    }

    @Test void immutableSchemasAndNativeFlagsPermissionsAndExternalSuspensionArePreserved() {
        var permissionChecks = new AtomicInteger();
        ToolBase original = new ToolBase(ToolBase.builder().name("native_fixture").description("native description")
                .inputSchema(Map.of("type", "object")).readOnly(true).returnDirect(true)
                .concurrencySafe(false).stateInjected(true)) {
            @Override public Boolean getStrict() { return true; }
            @Override public Map<String, Object> getOutputSchema() { return Map.of("type", "string"); }
            @Override public boolean matchRule(String rule, Map<String, Object> input) { return "specific".equals(rule); }
            @Override public List<PermissionRule> generateSuggestions(Map<String, Object> input) { return List.of(); }
            @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
                permissionChecks.incrementAndGet();
                return Mono.just(PermissionDecision.passthrough("native_fixture"));
            }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) { return Mono.just(ToolResultBlock.text("native")); }
        };
        Toolkit toolkit = published(original);
        ToolBase guarded = (ToolBase) toolkit.getTool("native_fixture");
        assertThat(guarded.getParameters()).isEqualTo(original.getParameters());
        assertThat(guarded.getDescription()).isEqualTo(original.getDescription());
        assertThat(guarded.getStrict()).isTrue();
        assertThat(guarded.getOutputSchema()).isEqualTo(original.getOutputSchema());
        assertThat(guarded.isReadOnly()).isTrue();
        assertThat(guarded.isReturnDirect()).isTrue();
        assertThat(guarded.isConcurrencySafe()).isFalse();
        assertThat(guarded.isStateInjected()).isTrue();
        assertThat(guarded.matchRule("specific", Map.of())).isTrue();
        assertThat(guarded.generateSuggestions(Map.of())).isEmpty();
        guarded.checkPermissions(Map.of(), null).block();
        assertThat(permissionChecks).hasValue(1);

        ToolBase external = new ToolBase(ToolBase.builder().name("external_fixture").description("external")
                .inputSchema(Map.of("type", "object")).externalTool(true)) {
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) { throw new AssertionError("External body must not run"); }
        };
        Toolkit externalToolkit = published(external);
        assertThat(externalToolkit.isExternalTool("external_fixture")).isTrue();
        var result = externalToolkit.callTool(ToolCallParam.builder()
                .toolUseBlock(call("external_fixture", Map.of())).runtimeContext(RuntimeContext.empty()).build()).block();
        assertThat(result.getState()).isEqualTo(ToolResultState.RUNNING);
    }

    private Toolkit published(Object tool) {
        Toolkit toolkit = new Toolkit();
        AgentToolGroupSupport.group(toolkit, "fixture-group", tool);
        return toolkit;
    }

    private ToolUseBlock call(String name, Map<String, Object> input) {
        try { return new ToolUseBlock("call", name, input, json.writeValueAsString(input), Map.of()); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    public static final class CommandFixture {
        final AtomicInteger effects = new AtomicInteger();
        @Tool(name = "guard_fixture", description = "fixture", concurrencySafe = false)
        public String invoke(@ToolParam(name = "value", description = "approved value") String value) {
            effects.incrementAndGet();
            return value;
        }
    }
}
