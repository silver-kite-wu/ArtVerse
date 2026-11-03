package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.AgentUserInputRequest;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * HITL tool implemented as an AgentScope v2 external tool.
 *
 * <p>When the agent calls this tool, the framework automatically emits
 * {@link io.agentscope.core.event.RequireExternalExecutionEvent} and pauses.
 * The calling code (node layer) detects the pending user input via
 * {@link com.artverse.application.AgentRunToolStatus}, waits for user response,
 * then resumes the agent by feeding the user's choice back as a message.</p>
 *
 * <p>This replaces the previous {@code @Tool} + {@code ToolSuspendException}
 * pattern with the official v2 external tool mechanism.</p>
 */
@Component
public class MangaHitlTools extends ToolBase {

    private final AgentToolAuditService agentToolAuditService;
    private final MangaToolSupport support;

    public MangaHitlTools(AgentToolAuditService agentToolAuditService, MangaToolSupport support) {
        super(ToolBase.builder()
                .name("ask_user")
                .description("Pause the manga agent and ask the user to choose between options before continuing. Use this when a creative or workflow decision cannot be made safely.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "question", Map.of("type", "string", "description", "Question to show to the user"),
                                "options", Map.of("type", "array", "description", "Options as a list of strings or objects with label/description/recommended"),
                                "allow_free_text", Map.of("type", "boolean", "description", "Whether the user may type a custom answer"),
                                "reason", Map.of("type", "string", "description", "Short reason why user input is needed")
                        ),
                        "required", List.of("question", "options")
                ))
                .readOnly(true)
                .concurrencySafe(false)
                .externalTool(true));
        this.agentToolAuditService = agentToolAuditService;
        this.support = support;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String question = (String) input.get("question");
        Object rawOptions = input.get("options");
        Boolean allowFreeText = (Boolean) input.get("allow_free_text");
        String reason = (String) input.get("reason");

        var runtimeContext = param.getRuntimeContext();
        MangaAgentRuntimeContext ctx = support.resolveContext(runtimeContext);

        return Mono.fromCallable(() -> {
            AgentUserInputRequest request = buildUserInputRequest(question, rawOptions, allowFreeText, reason);
            support.requestUserInput(ctx, request);
            agentToolAuditService.around(getName(), ctx.userId(), ctx.chapterId(), runtimeContext, () -> Map.of());
            String toolId = param.getToolUseBlock().getId();
            return ToolResultBlock.builder()
                    .id(toolId)
                    .name(getName())
                    .output(List.of(TextBlock.builder().text("Waiting for user input").build()))
                    .state(ToolResultState.RUNNING)
                    .build();
        });
    }

    /** External tools pause before callAsync; bridge that event to the product's durable waiting state. */
    public void captureExternalInput(Map<String,Object> input, io.agentscope.core.agent.RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext ctx=support.resolveContext(runtimeContext);
        AgentUserInputRequest request=buildUserInputRequest(
                (String)input.get("question"),input.get("options"),
                Boolean.TRUE.equals(input.get("allow_free_text")),(String)input.get("reason"));
        support.requestUserInput(ctx,request);
        agentToolAuditService.around(getName(),ctx.userId(),ctx.chapterId(),runtimeContext,()->Map.of());
    }

    private AgentUserInputRequest buildUserInputRequest(String question, Object rawOptions,
                                                        Boolean allowFreeText, String reason) {
        List<AgentUserInputRequest.Option> options = support.normalizeOptions(rawOptions);
        if (options.isEmpty()) {
            options = List.of(
                    new AgentUserInputRequest.Option("a", "Continue with default", "Let the agent decide based on context", true),
                    new AgentUserInputRequest.Option("b", "Give suggestion first", "Agent explains recommendation before acting", false)
            );
        }
        return new AgentUserInputRequest(
                question == null || question.isBlank() ? "Please confirm how to proceed." : question.trim(),
                options,
                Boolean.TRUE.equals(allowFreeText),
                reason == null ? "" : reason.trim(),
                inferPurpose(reason)
        );
    }

    private String inferPurpose(String reason) {
        if (reason != null && reason.toLowerCase().contains("artifact")) {
            return "BUSINESS_CONFIRMATION";
        }
        return null;
    }
}
