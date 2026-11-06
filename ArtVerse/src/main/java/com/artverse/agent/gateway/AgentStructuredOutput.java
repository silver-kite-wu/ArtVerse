package com.artverse.agent.gateway;

import com.artverse.agent.AgentRunRequest;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonSchemaUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-call metadata for the framework's output capture; never an executor or registered tool. */
final class AgentStructuredOutput {
    static final String NAME = ReActAgent.STRUCTURED_OUTPUT_TOOL_NAME;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AgentRunRequest request;
    private final JsonNode resultSchema;
    private final JsonNode syntheticSchema;
    private final int limit;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger captures = new AtomicInteger();

    AgentStructuredOutput(AgentRunRequest request, Class<?> outputType, int limit) {
        this.request = request;
        this.limit = limit;
        resultSchema = JSON.valueToTree(JsonSchemaUtils.generateSchemaFromClass(outputType));
        // AgentScope 2.0.4 wraps the same generated type in response and hoists root definitions.
        ObjectNode inner = resultSchema.deepCopy();
        ObjectNode definitions = JSON.createObjectNode();
        for (String key : List.of("$defs", "definitions")) {
            JsonNode value = inner.remove(key);
            if (value instanceof ObjectNode object) definitions.setAll(object);
        }
        ObjectNode wrapped = JSON.createObjectNode().put("type", "object");
        wrapped.putObject("properties").set("response", inner);
        wrapped.putArray("required").add("response");
        if (!definitions.isEmpty()) wrapped.set("$defs", definitions);
        syntheticSchema = wrapped;
    }

    void requireRequest(AgentRunRequest current) {
        if (!request.equals(current)) throw denied();
    }

    void requireModelInput(ModelCallInput input) {
        if (input.tools().isEmpty()) {
            var format = input.options() == null ? null : input.options().getResponseFormat();
            if (format == null || !"json_schema".equals(format.getType()) || format.getJsonSchema() == null
                    || !NAME.equals(format.getJsonSchema().getName())
                    || !Boolean.TRUE.equals(format.getJsonSchema().getStrict())
                    || !resultSchema.equals(JSON.valueToTree(format.getJsonSchema().getSchema()))) throw denied();
        } else if (input.tools().size() != 1 || !matches(input.tools().getFirst())) {
            throw denied();
        }
        if (modelCalls.incrementAndGet() > limit) throw new BusinessException(429, "结构化模型请求额度已用尽");
    }

    private boolean matches(ToolSchema schema) {
        return NAME.equals(schema.getName()) && schema.getStrict() == null && schema.getOutputSchema() == null
                && syntheticSchema.equals(JSON.valueToTree(schema.getParameters()));
    }

    void capture() {
        if (captures.incrementAndGet() > limit) throw new BusinessException(429, "结构化输出尝试额度已用尽");
    }

    private static BusinessException denied() {
        return new BusinessException(403, "TOOL_NOT_ALLOWED: unexpected structured output contract");
    }
}
