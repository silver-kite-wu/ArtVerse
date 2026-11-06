package com.artverse.agent.gateway;

import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentAnswerDelivery;
import com.artverse.application.AgentBudgetService;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@Primary
public class AgentScopeHarnessAgentGateway {

    private static final String CB_NAME = "manga-agent-llm";

    private final AgentScopeMessageMapper messageMapper = new AgentScopeMessageMapper();
    private final AgentScopeAgentFactory agentFactory;
    private final AgentScopeRuntimeContextFactory runtimeContextFactory;
    private final ArtVerseProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final AgentBudgetService budgetService;
    private final AgentSessionHydrator sessionHydrator;
    private final AgentSessionTurns sessionTurns;
    @Autowired
    private com.artverse.application.tools.MangaHitlTools hitlTools;
    @Autowired
    private com.artverse.application.MangaAgentRunService runService;

    private CircuitBreakerConfig circuitBreakerConfig;
    private final ConcurrentMap<SessionKey, Invocation> activeRuns = new ConcurrentHashMap<>();

    @Autowired
    public AgentScopeHarnessAgentGateway(
            AgentScopeAgentFactory agentFactory,
            AgentScopeRuntimeContextFactory runtimeContextFactory,
            ArtVerseProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            AgentBudgetService budgetService,
            AgentSessionHydrator sessionHydrator,
            AgentSessionTurns sessionTurns) {
        this.agentFactory = agentFactory;
        this.runtimeContextFactory = runtimeContextFactory;
        this.properties = properties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.budgetService = budgetService;
        this.sessionHydrator = sessionHydrator;
        this.sessionTurns = sessionTurns;
    }

    @PostConstruct
    public void init() {
        ArtVerseProperties.Agent agentProps = properties.getAgent();

        // Record transient failures without treating business rejections as provider outages.
        Predicate<Throwable> isTransient = throwable ->
                (throwable instanceof IOException)
                        || (throwable instanceof RuntimeException
                        && !(throwable instanceof BusinessException)
                        && !(throwable instanceof CallNotPermittedException));

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(agentProps.getCircuitBreakerSlidingWindowSize())
                .minimumNumberOfCalls(agentProps.getCircuitBreakerFailureThreshold())
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(agentProps.getCircuitBreakerWaitSeconds()))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slowCallDurationThreshold(Duration.ofMillis(agentProps.getCircuitBreakerSlowCallThresholdMs()))
                .slowCallRateThreshold(50)
                .recordException(isTransient)
                .build();
        this.circuitBreakerConfig = cbConfig;

        log.info("Agent gateway initialized: cb={}, native execution attempts=1", CB_NAME);
    }

    public Flux<String> streamChat(AgentRunRequest request) {
        return streamEvents(request)
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta)
                .filter(delta -> delta != null && !delta.isEmpty());
    }

    public Flux<AgentEvent> streamEvents(AgentRunRequest request) {
        return streamEvents(request, List.of());
    }

    /** Resume an external HITL pause with its real tool result, preserving the Harness session. */
    public Flux<AgentEvent> streamEvents(AgentRunRequest request, List<ToolResultBlock> externalResults) {
        return streamEvents(request, externalResults, null);
    }

    /** Acquired after answer acceptance and before the SQL delivery claim. */
    public AgentSessionTurns.Lease acquireSessionTurn(AgentRunRequest identity) {
        return sessionTurns.acquire(identity);
    }

    public Flux<AgentEvent> streamEvents(AgentRunRequest request, List<ToolResultBlock> externalResults,
                                        AgentSessionTurns.Lease ownedTurn) {
        return Flux.using(() -> {
                    var lease = ownedTurn == null ? sessionTurns.acquire(request) : ownedTurn;
                    try { lease.requireRequest(request); return new Invocation(request, lease); }
                    catch (RuntimeException error) { lease.close(); throw error; }
                }, invocation -> Flux.defer(() -> {
                    invocation.lease.requireOwned();
                    AgentAnswerDelivery delivery = request.variables().get("answer_delivery") instanceof AgentAnswerDelivery value ? value : null;
                    List<ToolResultBlock> supplied = externalResults;
                    boolean nativeAlreadyRecorded = false;
                    if (delivery != null) {
                        if (runService == null || sessionHydrator == null) throw new BusinessException(503, "答复投递存储未配置");
                        runService.requireAnswerDelivery(request, delivery);
                        var answerState = sessionHydrator.answerState(request, delivery);
                        if (!answerState.pending()) {
                            recordNativeAnswer(request, delivery, answerState.version());
                            nativeAlreadyRecorded = true;
                            var nextPending = sessionHydrator.pendingCalls(request);
                            if (!nextPending.isEmpty()) {
                                RuntimeContext repaired = runtimeContextFactory.create(request);
                                repaired.put(AgentSessionTurns.Lease.class, invocation.lease);
                                for (var call : nextPending) {
                                    if (!"ask_user".equals(call.getName()) || hitlTools == null)
                                        throw new BusinessException(409, "后续原生待办不能自动恢复");
                                    hitlTools.captureExternalInput(call, repaired);
                                }
                                return Flux.empty();
                            }
                            if (answerState.finalResponse() != null) {
                                Msg finalResponse = answerState.finalResponse();
                                runService.requireNativeCompletion(request, delivery, answerState.version(),
                                        finalResponse.getId(), finalResponse.getTextContent());
                                return Flux.just(new TextBlockDeltaEvent(finalResponse.getId(), "recovered-response", finalResponse.getTextContent()),
                                        new AgentResultEvent(finalResponse));
                            }
                            supplied = List.of();
                        } else {
                            if (externalResults.size() != 1 || !externalResults.getFirst().getId().equals(delivery.callId()))
                                throw new BusinessException(409, "本次答复与投递记录不一致");
                            supplied = List.of(delivery.toolResult());
                        }
                    }
                    if (!supplied.isEmpty()) {
                        if (sessionHydrator == null) throw new BusinessException(409, "原生恢复存储未配置");
                        sessionHydrator.requirePendingResults(request, supplied);
                    } else if (sessionHydrator != null) {
                        sessionHydrator.requireNoPending(request);
                    }
                    List<com.artverse.agent.AgentMessage> effectiveMessages = messagesFor(request);
                    validateInputBudget(request, effectiveMessages);
                    HarnessAgent agent = invocation.createAgent();
                    AgentScopeCallMessages callMessages = messageMapper.map(effectiveMessages);
                    RuntimeContext ctx = runtimeContextFactory.create(request, callMessages.systemContent());
                    ctx.put(AgentSessionTurns.Lease.class, invocation.lease);
                    List<Msg> messages = new java.util.ArrayList<>();
                    if (!supplied.isEmpty()) {
                        for (ToolResultBlock result : supplied) {
                            messages.add(Msg.builder().role(MsgRole.TOOL).content(result).build());
                        }
                    }
                    if (!nativeAlreadyRecorded) messages.addAll(callMessages.inputMessages());
                    List<ToolUseBlock> suspended = new java.util.ArrayList<>();
                    var nativeResult = new java.util.concurrent.atomic.AtomicReference<Msg>();
                    AgentRun<AgentEvent> run = agent.prepareRun(messages, ctx);
                    invocation.register(run);
                    return run.stream()
                            .doOnNext(event -> {
                                if (event instanceof AgentResultEvent result) nativeResult.set(result.getResult());
                                if (event instanceof RequireExternalExecutionEvent external) {
                                    for (var call : external.getToolCalls()) {
                                        if (!"ask_user".equals(call.getName()) || hitlTools == null)
                                            throw new BusinessException(400,"当前执行器不支持此外部工具，请检查任务配置");
                                        suspended.add(call);
                                    }
                                }
                                if (event instanceof RequireUserConfirmEvent)
                                    throw new BusinessException(403,"工具需要额外执行授权，任务未完成，请检查工具权限配置");
                                if (event instanceof TextBlockDeltaEvent deltaEvent
                                        && deltaEvent.getDelta() != null) {
                                    AgentBudgetService.OutputUsage delta =
                                            budgetService == null
                                                    ? new AgentBudgetService.OutputUsage(0, 0)
                                                    : budgetService.measureOutput(deltaEvent.getDelta());
                                    AgentBudgetService.OutputUsage total =
                                            new AgentBudgetService.OutputUsage(
                                                    invocation.outputTokens.addAndGet(delta.estimatedTokens()),
                                                    invocation.outputBytes.addAndGet(delta.bytes()));
                                    if (budgetService != null) {
                                        budgetService.requireOutputWithinLimit(total);
                                    }
                                }
                            })
                            .concatWith(Flux.defer(() -> {
                                invocation.lease.requireOwned();
                                if (delivery != null) verifyNativeAnswer(request, delivery);
                                if (delivery != null && nativeResult.get() != null && suspended.isEmpty()) {
                                    var saved = sessionHydrator.answerState(request, delivery);
                                    Msg result = nativeResult.get();
                                    if (saved.finalResponse() == null
                                            || !java.util.Objects.equals(saved.finalResponse().getId(), result.getId())
                                            || !java.util.Objects.equals(saved.finalResponse().getTextContent(), result.getTextContent()))
                                        throw new BusinessException(409, "原生最终回复与保存检查点不一致，已有产物保留");
                                    runService.recordNativeCompletion(request, delivery, saved.version(), result.getId(), result.getTextContent());
                                }
                                // Native lifecycle has now saved its pending context. Only then
                                // publish a product question that can safely accept a response.
                                for (var call : suspended) {
                                    if (sessionHydrator == null || sessionHydrator.pendingCalls(request).stream()
                                            .noneMatch(pending -> pending.getId().equals(call.getId())
                                                    && pending.getName().equals(call.getName()))) {
                                        throw new BusinessException(409, "原生提问状态未保存，已有产物保留");
                                    }
                                    hitlTools.captureExternalInput(call, ctx);
                                }
                                return Flux.empty();
                            }))
                            .doOnError(error -> {
                                if (delivery != null) {
                                    try { invocation.lease.requireOwned(); verifyNativeAnswer(request, delivery); }
                                    catch (RuntimeException verificationFailure) {
                                        // Preserve the original execution error and the immutable accepted answer.
                                        if (verificationFailure != error) error.addSuppressed(verificationFailure);
                                    }
                                }
                            });
                }), Invocation::close, true)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(request, "executor")))
                .doOnError(e -> logGatewayError(e, "stream", request.requestId()));
    }

    private void verifyNativeAnswer(AgentRunRequest request, AgentAnswerDelivery delivery) {
        var saved = sessionHydrator.answerState(request, delivery);
        if (saved.pending()) throw new BusinessException(409, "原生答复尚未保存，请保留原答复后重试投递");
        recordNativeAnswer(request, delivery, saved.version());
    }

    private void recordNativeAnswer(AgentRunRequest request, AgentAnswerDelivery delivery, long version) {
        runService.recordNativeAnswer(request, delivery, version, AgentScopeRuntimeContextFactory.createSessionId(request));
    }

    public Mono<String> generateText(AgentRunRequest request) {
        return streamEvents(request).ofType(AgentResultEvent.class)
                .map(event -> event.getResult().getTextContent()).last("");
    }

    public <T> Mono<T> generateStructured(AgentRunRequest request, Class<T> outputType) {
        return Mono.using(() -> new Invocation(request, sessionTurns.acquire(request)), invocation -> Mono.defer(() -> {
                    invocation.lease.requireOwned();
                    if (request.taskType() != com.artverse.agent.AgentTaskType.MANGA_ROUTER
                            && request.taskType() != com.artverse.agent.AgentTaskType.KNOWLEDGE_EXTRACTION) {
                        throw new BusinessException(403, "当前任务不支持无业务工具结构化入口");
                    }
                    if (sessionHydrator != null) sessionHydrator.requireNoPending(request);
                    List<com.artverse.agent.AgentMessage> effectiveMessages = messagesFor(request);
                    validateInputBudget(request, effectiveMessages);
                    HarnessAgent agent = invocation.createAgent();
                    if (!agent.getToolkit().getToolNames().isEmpty()) {
                        throw new BusinessException(403, "结构化入口不能携带业务工具或技能");
                    }
                    AgentScopeCallMessages callMessages = messageMapper.map(effectiveMessages);
                    RuntimeContext ctx = runtimeContextFactory.create(request, callMessages.systemContent());
                    ctx.put(AgentSessionTurns.Lease.class, invocation.lease);
                    ctx.put(AgentStructuredOutput.class, new AgentStructuredOutput(request, outputType,
                            Math.min(2, properties.getAgent().getRouterMaxModelCalls())));
                    List<Msg> messages = callMessages.inputMessages();
                    AgentRun<Msg> run = AgentRun.create(
                            request.requestId() == null ? UUID.randomUUID().toString() : request.requestId().toString(),
                            () -> agent.call(messages, outputType, ctx).flux());
                    invocation.register(run);
                    return run.stream().singleOrEmpty().map(message -> {
                        invocation.lease.requireOwned();
                        enforceAndRecordOutput(request, message.getTextContent());
                        T result = message.getStructuredData(outputType);
                        if (result == null) {
                            throw new BusinessException(502, "Agent returned empty structured output");
                        }
                        return result;
                    });
                }), Invocation::close, true)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(request, "router")))
                .doOnError(e -> logGatewayError(e, "structured", request.requestId()));
    }

    /**
     * Stops the currently executing AgentScope run, including its framework
     * managed subagents. The database terminal transition remains owned by
     * ArtVerse and is performed by the caller before invoking this method.
     */
    public boolean interrupt(String userId, UUID conversationId, UUID requestId) {
        if (userId == null || requestId == null) return false;
        boolean interrupted = false;
        for (Invocation invocation : activeRuns.values()) {
            if (userId.equals(invocation.request.userId())
                    && java.util.Objects.equals(conversationId, invocation.request.conversationId())
                    && requestId.equals(invocation.request.requestId())) {
                interrupted |= invocation.run.interrupt();
            }
        }
        return interrupted;
    }

    private record SessionKey(String userId, String sessionId) { }

    /** Owns resources from before Factory/state loading through native persistence and bridge completion. */
    private final class Invocation implements AutoCloseable {
        private final AgentRunRequest request;
        private final AgentSessionTurns.Lease lease;
        private final SessionKey key;
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong outputBytes = new AtomicLong();
        private HarnessAgent agent;
        private AgentRun<?> run;

        private Invocation(AgentRunRequest request, AgentSessionTurns.Lease lease) {
            this.request = request;
            this.lease = lease;
            this.key = new SessionKey(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request));
        }

        private HarnessAgent createAgent() {
            agent = agentFactory.getOrCreate(request, lease);
            return agent;
        }

        private void register(AgentRun<?> nativeRun) {
            lease.requireOwned();
            run = nativeRun;
            if (activeRuns.putIfAbsent(key, this) != null) {
                throw new BusinessException(409, "会话正在执行，请查看原任务进度后重试");
            }
            lease.onLost(nativeRun::interrupt);
            lease.requireOwned();
        }

        @Override public void close() {
            try {
                if (agent != null) {
                    try { agentFactory.closeAgent(agent); }
                    finally { if (agent.getStateStore() != null) agent.getStateStore().close(); }
                }
            } finally {
                try {
                    activeRuns.remove(key, this);
                } finally { lease.close(); }
            }
        }
    }

    private List<com.artverse.agent.AgentMessage> messagesFor(AgentRunRequest request) {
        return sessionHydrator == null ? request.messages() : sessionHydrator.messagesFor(request);
    }

    private void validateInputBudget(AgentRunRequest request,
                                     List<com.artverse.agent.AgentMessage> effectiveMessages) {
        if (budgetService != null) {
            budgetService.validateAndRecordInput(request, effectiveMessages);
        }
    }

    private String enforceAndRecordOutput(AgentRunRequest request, String text) {
        if (budgetService == null) return text;
        AgentBudgetService.OutputUsage usage = budgetService.measureOutput(text);
        budgetService.requireOutputWithinLimit(usage);
        return text;
    }

    private CircuitBreaker circuitBreaker(AgentRunRequest request, String role) {
        return circuitBreakerRegistry.circuitBreaker(resilienceName(CB_NAME, request, role), circuitBreakerConfig);
    }

    private String resilienceName(String prefix, AgentRunRequest request, String role) {
        String provider = request.modelSpec() == null ? "default" : request.modelSpec().provider();
        String baseUrl = request.modelSpec() == null ? "default" : request.modelSpec().baseUrl();
        return prefix + "-" + role + "-" + provider + "-" + AgentModelSpecFactory.shortHash(baseUrl);
    }

    private void logGatewayError(Throwable e, String method, UUID requestId) {
        if (e instanceof CallNotPermittedException) {
            log.warn("Circuit breaker open for agent LLM, fast-failing {} request={}", method, requestId);
        } else {
            log.error("Agent {} failed: requestId={}, error={}", method, requestId, e.getMessage());
        }
    }

}
