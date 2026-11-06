package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentToolAttempt;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.MangaAgentRunService;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Native external tool declaration. AgentScope generates the schema and pauses before invocation;
 * only the verified external event bridge persists the product's pending question.
 */
@Component
@RequiredArgsConstructor
public class MangaHitlTools {

    private final AgentToolAuditService agentToolAuditService;
    private final MangaToolSupport support;
    private final MangaAgentRunService runs;

    @Tool(name = "ask_user", externalTool = true, readOnly = false, concurrencySafe = false,
            description = "Pause and ask the author a question. An answer does not itself authorize changes; use the product's review decision for drafts.")
    public ToolResultBlock askUser(
            @ToolParam(name = "question", description = "Question for the author, 1 to 4000 characters") String question,
            @ToolParam(name = "options", description = "One to ten answer choices, each 1 to 1000 characters") List<String> options,
            @ToolParam(name = "allow_free_text", required = false, description = "Allow a custom answer") Boolean allowFreeText,
            @ToolParam(name = "reason", required = false, description = "Context for the question; never an authorization") String reason) {
        throw new IllegalStateException("External questions must be handled through AgentScope's pending call event");
    }

    /** External tools pause before callAsync; bridge that event to the product's durable waiting state. */
    public void captureExternalInput(ToolUseBlock call, io.agentscope.core.agent.RuntimeContext runtimeContext) {
        Map<String, Object> input = call.getInput();
        MangaAgentRuntimeContext ctx=support.resolveContext(runtimeContext);
        AgentUserInputRequest request=buildUserInputRequest(
                (String)input.get("question"),input.get("options"),
                Boolean.TRUE.equals(input.get("allow_free_text")),(String)input.get("reason"));
        var registered = runs.recordExternalQuestion(ctx, call.getId(), request);
        support.requestUserInput(ctx,registered.request());
        if (registered.created()) {
            var auditContext = io.agentscope.core.agent.RuntimeContext.builder(runtimeContext)
                    .put(AgentToolAttempt.class, AgentToolAttempt.capture(call, runtimeContext)).build();
            agentToolAuditService.around("ask_user",ctx.userId(),ctx.chapterId(),auditContext,()->Map.of());
        }
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
                "USER_QUESTION" // A model-supplied reason can never confer business authorization.
        );
    }
}
