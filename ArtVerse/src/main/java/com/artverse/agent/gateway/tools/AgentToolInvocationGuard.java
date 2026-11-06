package com.artverse.agent.gateway.tools;

import com.artverse.common.BusinessException;
import com.artverse.agent.AgentToolAttempt;
import io.agentscope.core.agent.RuntimeContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** Checks the final native executor input, then delegates to the original native tool. */
public final class AgentToolInvocationGuard extends ToolBase {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final AgentTool delegate;

    public AgentToolInvocationGuard(AgentTool delegate) {
        super(metadata(delegate));
        this.delegate = delegate;
    }

    private static Builder metadata(AgentTool delegate) {
        Builder builder = ToolBase.builder().name(delegate.getName()).description(delegate.getDescription())
                .inputSchema(delegate.getParameters()).readOnly(delegate.isReadOnly()).returnDirect(delegate.isReturnDirect());
        if (delegate instanceof ToolBase base) {
            builder.concurrencySafe(base.isConcurrencySafe()).externalTool(base.isExternalTool())
                    .stateInjected(base.isStateInjected());
            if (base.isMcp()) builder.mcp(base.getMcpName());
        }
        return builder;
    }

    @Override public Boolean getStrict() { return delegate.getStrict(); }
    @Override public Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }

    @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return delegate instanceof ToolBase base ? base.checkPermissions(input, context) : super.checkPermissions(input, context);
    }

    @Override public boolean matchRule(String ruleContent, Map<String, Object> input) {
        return delegate instanceof ToolBase base ? base.matchRule(ruleContent, input) : super.matchRule(ruleContent, input);
    }

    @Override public List<PermissionRule> generateSuggestions(Map<String, Object> input) {
        return delegate instanceof ToolBase base ? base.generateSuggestions(input) : super.generateSuggestions(input);
    }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(() -> {
            ToolUseBlock call = param.getToolUseBlock();
            try {
                if (call == null || !getName().equals(call.getName()) || call.getContent() == null)
                    throw denied();
                JsonNode raw = JSON.readTree(call.getContent());
                // Native Toolkit merges ToolCallParam.input and registered presets after Schema validation.
                // The method must receive exactly the input that was checked, including nested values.
                if (raw == null || !raw.isObject()
                        || !raw.equals(JSON.readTree(JSON.writeValueAsString(call.getInput()))))
                    throw denied();
                if (!raw.equals(JSON.readTree(JSON.writeValueAsString(param.getInput())))) throw denied();

                // Detach before invoking the native reflection tool; caller-owned nested Maps cannot change it later.
                Map<String, Object> detached = JSON.convertValue(raw, new TypeReference<>() {});
                ToolUseBlock detachedCall = new ToolUseBlock(call.getId(), call.getName(), detached,
                        call.getContent(), call.getMetadata(), call.getState());
                RuntimeContext original = param.getRuntimeContext() == null ? RuntimeContext.empty() : param.getRuntimeContext();
                RuntimeContext local = RuntimeContext.builder(original)
                        .put(AgentToolAttempt.class, AgentToolAttempt.capture(detachedCall, original)).build();
                return delegate.callAsync(ToolCallParam.builder(param).toolUseBlock(detachedCall).input(detached)
                        .runtimeContext(local).build());
            } catch (java.io.IOException error) {
                return Mono.error(denied());
            }
        });
    }

    private static BusinessException denied() {
        return new BusinessException(403, "TOOL_NOT_ALLOWED: native executor input differs from the validated call");
    }
}
