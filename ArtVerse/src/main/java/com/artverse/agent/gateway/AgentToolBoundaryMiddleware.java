package com.artverse.agent.gateway;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.gateway.tools.AgentToolCapability;
import com.artverse.application.AgentBudgetService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.*;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Domain boundaries around native execution, without replacing its schema validator or executor. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentToolBoundaryMiddleware {
    private static final String SKILL_LOADER = "load_skill_through_path";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final MangaAgentRunService runs;
    private final AutomationAgentTools automations;
    private final AgentBudgetService budget;
    private com.artverse.application.StoryboardArtifactService storyboardArtifacts;
    private com.artverse.application.NovelModelReadService novelReads;

    @org.springframework.beans.factory.annotation.Autowired
    public void setNovelReads(com.artverse.application.NovelModelReadService service) {
        this.novelReads = Objects.requireNonNull(service);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setStoryboardArtifacts(com.artverse.application.StoryboardArtifactService service) {
        this.storyboardArtifacts = Objects.requireNonNull(service);
    }

    io.agentscope.core.model.Model budgetedModel(io.agentscope.core.model.Model model, AgentRunRequest request,
                                               com.artverse.config.ArtVerseProperties properties) {
        return new BudgetedAgentModel(model, request, budget, properties, storyboardArtifacts, novelReads);
    }

    public void verifyRegistration(Toolkit toolkit, Map<String, AgentTool> businessTools, boolean hasSkills) {
        var allowed = new HashSet<>(businessTools.keySet());
        if (hasSkills) allowed.add(SKILL_LOADER);
        if (!allowed.equals(toolkit.getToolNames())) {
            throw new IllegalStateException("Unexpected Harness tool exposure: " + toolkit.getToolNames());
        }
        businessTools.forEach((name, tool) -> {
            if (toolkit.getTool(name) != tool) throw new IllegalStateException("Business tool replaced: " + name);
        });
        if (hasSkills && !isFrameworkSkillLoader(toolkit.getTool(SKILL_LOADER))) {
            throw new IllegalStateException("Unexpected skill loader source");
        }
    }

    private static boolean isFrameworkSkillLoader(AgentTool tool) {
        String type = tool.getClass().getName();
        return io.agentscope.harness.agent.skill.runtime.SkillLoadTool.class.getName().equals(type)
                || type.startsWith("io.agentscope.core.skill.SkillToolFactory$");
    }

    public RunBoundary forRun(AgentRunRequest request, Toolkit toolkit) {
        return new RunBoundary(request, toolkit);
    }

    private static AgentRunRequest withSkills(AgentRunRequest request, com.artverse.agent.BusinessSkillSelection selection) {
        if (java.util.Objects.equals(request.businessSkillSelection(), selection)
                || (request.businessSkillSelection() == null && selection.isEmpty())) return request;
        return new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                request.messages(), request.variables(), request.modelSpec(), request.llmApiKey(), request.requestId(),
                request.conversationId(), selection);
    }

    public RunBoundary forRun(AgentRunRequest request, Toolkit toolkit, com.artverse.agent.BusinessSkillSelection selection) {
        var boundary = new RunBoundary(request, toolkit);
        boundary.budgetRequest = withSkills(request, selection);
        return boundary;
    }

    /** Construct before build, seal against the actual copied Toolkit before any run starts. */
    public final class RunBoundary implements MiddlewareBase {
        private final AgentRunRequest request;
        private AgentRunRequest budgetRequest;
        private final Map<String, AgentTool> expected;
        private final Map<String, Contract> published;
        private volatile Map<String, Contract> contracts;
        private volatile Toolkit actualToolkit;
        private volatile AgentToolCapability capability;

        private RunBoundary(AgentRunRequest request, Toolkit toolkit) {
            this.request = request;
            this.budgetRequest = request;
            if (toolkit.getToolNames().contains(AgentStructuredOutput.NAME)) {
                throw new IllegalStateException("Framework structured-output name is reserved");
            }
            expected = Map.copyOf(toolkit.getToolNames().stream().collect(
                    Collectors.toMap(Function.identity(), toolkit::getTool)));
            published = Map.copyOf(expected.entrySet().stream().collect(
                    Collectors.toMap(Map.Entry::getKey, entry -> Contract.capture(entry.getValue()))));
        }

        public synchronized void seal(Toolkit actual, boolean hasSkills) {
            if (contracts != null) throw new IllegalStateException("Tool boundary is already sealed");
            verifyRegistration(actual, expected, hasSkills);
            published.forEach((name, contract) -> {
                if (!contract.equals(Contract.capture(actual.getTool(name)))) {
                    throw new IllegalStateException("Business tool contract changed during Harness build: " + name);
                }
            });
            var snapshot = new HashMap<String, Contract>();
            actual.getToolNames().forEach(name -> snapshot.put(name, Contract.capture(actual.getTool(name))));
            actualToolkit = actual;
            var digests = new HashMap<String, String>();
            snapshot.forEach((name, contract) -> digests.put(name, AgentBudgetService.hash(java.util.Arrays.asList(
                    contract.name(), contract.description(), contract.parameters(), contract.output(), contract.strict(),
                    contract.readOnly(), contract.returnDirect(), contract.external(), contract.concurrencySafe()))));
            capability = new AgentToolCapability(snapshot.keySet(),
                    Boolean.TRUE.equals(request.variables().get("review_completed")),
                    AgentToolCapability.requestsAutomationManagement(request), null,
                    new HashSet<>(actual.getActiveGroups()), digests);
            contracts = Map.copyOf(snapshot);
        }

        @Override public int order() { return Integer.MAX_VALUE; }

        @Override public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                                     Function<ModelCallInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> {
                validateRun(ctx);
                if (request.modelSpec() != null && !request.modelSpec().capabilities().toolCalling()
                        && !input.tools().isEmpty())
                    return Flux.error(new BusinessException(400, "当前模型未声明工具调用能力，不能进入 synthetic 工具输出路径"));
                AgentStructuredOutput structured = ctx.get(AgentStructuredOutput.class);
                if (structured != null) {
                    structured.requireRequest(request);
                    if (!actualToolkit.getToolNames().isEmpty()) return Flux.error(denied("structured call has business tools"));
                    structured.requireModelInput(input);
                    return next.apply(input);
                }
                var names = new HashSet<String>();
                for (ToolSchema schema : input.tools()) {
                    Contract expectedSchema = contracts.get(schema.getName());
                    if (!names.add(schema.getName()) || expectedSchema == null || !expectedSchema.matches(schema)) {
                        return Flux.error(denied("unexpected model tool schema"));
                    }
                    verifyTool(schema.getName());
                }
                return next.apply(input);
            });
        }

        @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                                   Function<ActingInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> {
                validateRun(ctx);
                AgentStructuredOutput structured = ctx.get(AgentStructuredOutput.class);
                if (structured != null) {
                    structured.requireRequest(request);
                    if (!actualToolkit.getToolNames().isEmpty() || input.toolCalls().size() != 1
                            || !AgentStructuredOutput.NAME.equals(input.toolCalls().getFirst().getName())) {
                        return Flux.error(denied("unexpected structured output call"));
                    }
                    verifyInput(input.toolCalls().getFirst());
                    structured.capture();
                    return next.apply(input);
                }
                MangaAgentRuntimeContext execution = ctx.get(MangaAgentRuntimeContext.class);
                long external = input.toolCalls().stream().filter(call -> {
                    var contract = contracts.get(call.getName());
                    return contract != null && contract.external();
                }).count();
                if (external > 0 && input.toolCalls().size() != 1) {
                    return Flux.error(denied("external question must be the only call in this batch"));
                }
                // Validate the entire batch before charging or executing any member.
                for (var call : input.toolCalls()) {
                    verifyTool(call.getName());
                    verifyInput(call);
                    String invalid = ToolValidator.validateInput(call.getContent(), actualToolkit.getTool(call.getName()).getParameters());
                    if (invalid != null) return Flux.error(denied("tool parameters fail native schema validation"));
                    if (Set.of("create_automation", "update_automation").contains(call.getName())) {
                        if (!capability.automationManagement()) return Flux.error(denied("plan management unavailable"));
                        automations.requireInteractive(execution);
                    }
                }
                if (!budget.reserveToolCalls(budgetRequest, input.toolCalls()))
                    return Flux.error(new BusinessException(409, "工具调用已预占，请核对原结果后继续"));
                ctx.put(AgentToolCapability.class, capability);
                Map<String, ObservedCall> calls = input.toolCalls().stream().collect(Collectors.toUnmodifiableMap(
                        call -> call.getId(), call -> new ObservedCall(call.getId(), call.getName(),
                                AgentBudgetService.hash(Map.of("name", call.getName(), "input", call.getInput())))));
                Set<String> seenResults = java.util.concurrent.ConcurrentHashMap.newKeySet();
                Set<String> deniedResults = java.util.concurrent.ConcurrentHashMap.newKeySet();
                Map<String, ReadResult> readResults = new HashMap<>();
                calls.values().stream().filter(call -> Set.of("get_chapter_context", "get_novel_context").contains(call.name()))
                        .forEach(call -> readResults.put(call.id(), new ReadResult()));
                var stopRequested = new java.util.concurrent.atomic.AtomicBoolean();
                return Flux.defer(() -> next.apply(input)).concatMap(event -> {
                    if (event instanceof ToolResultStartEvent start) {
                        ReadResult read = readResult(calls, readResults, start.getToolCallId(), start.getToolCallName());
                        if (read != null) read.start(start.getReplyId());
                    } else if (event instanceof ToolResultTextDeltaEvent delta) {
                        ReadResult read = readResult(calls, readResults, delta.getToolCallId(), delta.getToolCallName());
                        if (read != null) read.append(delta.getReplyId(), delta.getDelta());
                    } else if (event instanceof ToolResultDataDeltaEvent data) {
                        ReadResult read = readResult(calls, readResults, data.getToolCallId(), data.getToolCallName());
                        if (read != null) read.invalid = true;
                    } else if (event instanceof ToolResultEndEvent result) {
                        ObservedCall call = calls.get(result.getToolCallId());
                        if (call != null && call.name().equals(result.getToolCallName())) {
                            seenResults.add(call.id());
                            if (result.getState() == ToolResultState.DENIED) deniedResults.add(call.id());
                            observe(call, result.getState());
                            if (result.getState() == ToolResultState.SUCCESS && readResults.containsKey(call.id())) {
                                if (execution == null)
                                    throw new BusinessException(409, "READ_RECEIPT_UNAVAILABLE: 缺少读取回执登记边界");
                                var complete = readResults.get(call.id()).complete(result.getReplyId());
                                if ("get_chapter_context".equals(call.name())) {
                                    if (storyboardArtifacts == null) throw new BusinessException(409, "READ_RECEIPT_UNAVAILABLE: 缺少分镜读取回执服务");
                                    storyboardArtifacts.recordReadResult(execution, call.id(), complete);
                                } else {
                                    if (novelReads == null) throw new BusinessException(409, "READ_RECEIPT_UNAVAILABLE: 缺少正文读取回执服务");
                                    novelReads.recordReadResult(execution, call.id(), complete);
                                }
                            }
                        }
                    } else if (event instanceof AllToolsDeniedEvent denied) {
                        for (var tool : denied.getDeniedToolCalls()) {
                            ObservedCall call = calls.get(tool.getId());
                            if (call != null && call.name().equals(tool.getName())) {
                                seenResults.add(call.id());
                                deniedResults.add(call.id());
                                observe(call, ToolResultState.DENIED);
                            }
                        }
                        if (!calls.isEmpty() && deniedResults.size() == calls.size()) {
                            stopRequested.set(true);
                            return Flux.just(event, new RequestStopEvent("本轮工具均被原生权限规则拒绝", GenerateReason.ALL_TOOLS_DENIED));
                        }
                    }
                    return Flux.just(event);
                }).concatWith(Flux.defer(() -> !calls.isEmpty() && deniedResults.size() == calls.size()
                        && stopRequested.compareAndSet(false, true)
                        ? Flux.just(new RequestStopEvent("本轮工具均被原生权限规则拒绝", GenerateReason.ALL_TOOLS_DENIED)) : Flux.empty()))
                        .doOnComplete(() -> calls.values().stream().filter(call -> !seenResults.contains(call.id()))
                        .forEach(call -> observe(call, null)))
                        .doOnError(error -> calls.values().forEach(call -> observe(call, null)))
                        .doOnCancel(() -> calls.values().forEach(call -> observe(call, null)));
            });
        }

        private void observe(ObservedCall call, ToolResultState state) {
            try { budget.observeToolResult(budgetRequest, call.id(), call.name(), call.hash(), state); }
            catch (RuntimeException ignored) {
                // A result-observation outage cannot undo an effect, refund an attempt or hide the original failure.
                log.warn("Agent tool observation persistence incomplete: requestId={}, callId={}", request.requestId(), call.id());
            }
        }

        private void verifyInput(io.agentscope.core.message.ToolUseBlock call) {
            try {
                if (call.getContent() == null || call.getContent().isBlank()) throw denied("missing raw tool input");
                JsonNode raw = JSON.readTree(call.getContent());
                if (raw == null || !raw.isObject() || !raw.equals(JSON.readTree(JSON.writeValueAsString(call.getInput())))) {
                    throw denied("raw and executed tool inputs differ");
                }
            } catch (java.io.IOException error) {
                throw denied("invalid or ambiguous raw tool input");
            }
        }

        private void verifyTool(String name) {
            Contract contract = contracts.get(name);
            AgentTool actual = actualToolkit.getTool(name);
            if (contract == null || actual == null || !contract.equals(Contract.capture(actual))) {
                throw denied("tool unavailable or changed");
            }
        }

        private void validateRun(RuntimeContext ctx) {
            if (contracts == null) throw new IllegalStateException("Tool boundary must be sealed before execution");
            // Native external tools registered in this Toolkit (ask_user) remain supported. Per-request
            // replacement tools would bypass the sealed implementations and their final input checks.
            var overrides = ctx.getToolRequestConfig();
            if (overrides != null && !io.agentscope.core.tool.ToolRequestConfig.NONE.equals(overrides))
                throw denied("request-level tool overrides are unavailable");
            var turn = ctx.get(AgentSessionTurns.Lease.class);
            if (turn != null) turn.requireOwned();
            if (!request.taskType().isMangaExecutionTask()) return;
            MangaAgentRuntimeContext execution = ctx.get(MangaAgentRuntimeContext.class);
            if (execution == null || !Objects.equals(execution.userId(), Long.valueOf(request.userId()))
                    || !Objects.equals(execution.storyId(), request.storyId())
                    || !Objects.equals(execution.chapterId(), request.chapterId())
                    || !Objects.equals(execution.requestId(), request.requestId())
                    || !Objects.equals(execution.conversationId(), request.conversationId())
                    || execution.taskType() != request.taskType()) {
                throw new BusinessException(403, "SCOPE_DENIED");
            }
            var current = runs.captureToolExecutionScope(request);
            if (current == null) throw denied("persisted execution scope unavailable");
            synchronized (this) {
                if (capability.execution() == null) capability = capability.withExecution(current);
                else if (!capability.execution().equals(current)) throw denied("persisted execution scope changed");
            }
            if (!Objects.equals(current.sessionId(), ctx.getSessionId())
                    || !Objects.equals(String.valueOf(current.actorId()), ctx.getUserId())
                    || !Objects.equals(current.owner(), execution.executionOwner())
                    || !Objects.equals(current.fencingToken(), execution.fencingToken())
                    || !Objects.equals(current.stepId(), execution.stepId())
                    || !Objects.equals(current.automation(), execution.automationClaim()))
                throw denied("runtime differs from persisted execution scope");
            if (current.source() == AgentToolCapability.Source.EXPERT && !capability.toolNames().equals(Set.of("get_chapter_context")))
                throw denied("expert has extra tools");
            if (capability.summary() && capability.toolNames().stream().anyMatch(name ->
                    !Set.of("get_novel_context", "get_artifact", "list_artifacts").contains(name)))
                throw denied("summary has non-receipt tools");
        }
    }

    private record ObservedCall(String id, String name, String hash) { }

    private static ReadResult readResult(Map<String, ObservedCall> calls, Map<String, ReadResult> reads,
                                         String id, String name) {
        ObservedCall call = calls.get(id);
        return call != null && call.name().equals(name) ? reads.get(id) : null;
    }

    /** Bounded reconstruction of the exact native text result, never model-supplied receipt claims. */
    private static final class ReadResult {
        private static final int MAX_CHARS = 256_000;
        private final StringBuilder text = new StringBuilder();
        private String replyId;
        private boolean started;
        private boolean invalid;
        void start(String reply) {
            if (started || reply == null || reply.isBlank()) invalid = true;
            started = true;
            replyId = reply;
        }
        void append(String reply, String delta) {
            if (!started || !Objects.equals(replyId, reply) || delta == null || delta.length() > MAX_CHARS - text.length()) {
                invalid = true;
                return;
            }
            if (!invalid) text.append(delta);
        }
        Map<String, Object> complete(String reply) {
            if (!started || invalid || !Objects.equals(replyId, reply)) throw incomplete();
            try {
                JsonNode tree = JSON.readTree(text.toString());
                if (tree == null || !tree.isObject()) throw incomplete();
                return JSON.convertValue(tree, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (java.io.IOException failure) { throw incomplete(); }
        }
        private static BusinessException incomplete() {
            return new BusinessException(409, "READ_RECEIPT_UNAVAILABLE: 原生读取结果不完整或不匹配，不能作为草稿基准");
        }
    }

    private static BusinessException denied(String detail) {
        return new BusinessException(403, "TOOL_NOT_ALLOWED: " + detail);
    }

    /** Detached trees are private and never mutated or exposed after capture. */
    private record Contract(AgentTool source, String name, String description, JsonNode parameters,
                            JsonNode output, Boolean strict, boolean readOnly, boolean returnDirect,
                            boolean external, boolean concurrencySafe) {
        static Contract capture(AgentTool tool) {
            return new Contract(tool, tool.getName(), tool.getDescription(),
                    JSON.valueToTree(tool.getParameters()), JSON.valueToTree(tool.getOutputSchema()),
                    tool.getStrict(), tool.isReadOnly(), tool.isReturnDirect(),
                    tool instanceof ToolBase base && base.isExternalTool(),
                    tool instanceof ToolBase base && base.isConcurrencySafe());
        }
        boolean matches(ToolSchema schema) {
            return name.equals(schema.getName()) && Objects.equals(description, schema.getDescription())
                    && Objects.equals(strict, schema.getStrict())
                    && parameters.equals(JSON.valueToTree(schema.getParameters()));
        }
    }
}
