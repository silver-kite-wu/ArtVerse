package com.artverse.agent.gateway;

import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.application.AgentBudgetService;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
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
    private static final String RETRY_NAME = "manga-agent-llm";

    private final AgentScopeMessageMapper messageMapper = new AgentScopeMessageMapper();
    private final AgentScopeAgentFactory agentFactory;
    private final AgentScopeRuntimeContextFactory runtimeContextFactory;
    private final ArtVerseProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final AgentBudgetService budgetService;
    private final AgentSessionHydrator sessionHydrator;
    @Autowired
    private com.artverse.application.tools.MangaHitlTools hitlTools;

    private CircuitBreakerConfig circuitBreakerConfig;
    private RetryConfig retryConfig;
    /** Active per-invocation AgentScope controls keyed by ArtVerse request id. */
    private final ConcurrentMap<UUID, AgentRun<?>> activeRuns = new ConcurrentHashMap<>();

    @Autowired
    public AgentScopeHarnessAgentGateway(
            AgentScopeAgentFactory agentFactory,
            AgentScopeRuntimeContextFactory runtimeContextFactory,
            ArtVerseProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            AgentBudgetService budgetService,
            AgentSessionHydrator sessionHydrator) {
        this.agentFactory = agentFactory;
        this.runtimeContextFactory = runtimeContextFactory;
        this.properties = properties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.budgetService = budgetService;
        this.sessionHydrator = sessionHydrator;
    }

    public AgentScopeHarnessAgentGateway(
            AgentScopeAgentFactory agentFactory,
            AgentScopeRuntimeContextFactory runtimeContextFactory,
            ArtVerseProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {
        this(agentFactory, runtimeContextFactory, properties, circuitBreakerRegistry, retryRegistry, null, null);
    }

    @PostConstruct
    public void init() {
        ArtVerseProperties.Agent agentProps = properties.getAgent();

        // Shared predicate: only record / retry on transient failures,
        // not on business-logic rejections or CB-internal exceptions.
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

        RetryConfig retryConfig = RetryConfig.<Throwable>custom()
                .maxAttempts(agentProps.getMaxRetries() + 1) // +1 for the initial attempt
                .waitDuration(Duration.ofMillis(agentProps.getRetryMinBackoffMs()))
                .intervalBiFunction((attempt, either) -> {
                    long base = agentProps.getRetryMinBackoffMs();
                    long delay = (long) (base * Math.pow(agentProps.getRetryMultiplier(), attempt));
                    return Math.min(delay, agentProps.getRetryMaxBackoffMs());
                })
                .retryOnException(isTransient)
                .build();
        this.retryConfig = retryConfig;

        log.info("Agent gateway initialized: cb={} retry={} maxRetries={}",
                CB_NAME, RETRY_NAME, agentProps.getMaxRetries());
    }

    public Flux<String> streamChat(AgentRunRequest request) {
        return streamEvents(request)
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta)
                .filter(delta -> delta != null && !delta.isEmpty());
    }

    public Flux<AgentEvent> streamEvents(AgentRunRequest request) {
        return Flux.defer(() -> {
                    List<com.artverse.agent.AgentMessage> effectiveMessages = messagesFor(request);
                    validateInputBudget(request, effectiveMessages);
                    HarnessAgent agent = agentFactory.getOrCreate(request);
                    AgentScopeCallMessages callMessages = messageMapper.map(effectiveMessages);
                    RuntimeContext ctx = runtimeContextFactory.create(request, callMessages.systemContent());
                    List<Msg> messages = callMessages.inputMessages();
                    AtomicLong outputTokens = new AtomicLong();
                    AtomicLong outputBytes = new AtomicLong();
                    AgentRun<AgentEvent> run = agent.prepareRun(messages, ctx);
                    registerActive(request, run);
                    return run.stream()
                            .doOnNext(event -> {
                                if (event instanceof RequireExternalExecutionEvent external) {
                                    for (var call : external.getToolCalls()) {
                                        if (!"ask_user".equals(call.getName()) || hitlTools == null)
                                            throw new BusinessException(400,"当前执行器不支持此外部工具，请检查任务配置");
                                        hitlTools.captureExternalInput(call.getInput(), ctx);
                                    }
                                }
                                if (event instanceof RequireUserConfirmEvent)
                                    throw new BusinessException(403,"工具需要额外执行授权，任务未完成，请检查工具权限配置");
                                // A stream includes parent and Harness subagent model calls.
                                // Counting the typed events keeps the distributed budget
                                // authoritative even when subagents bypass this gateway API.
                                if (event instanceof ModelCallStartEvent) {
                                    consumeModelBudget(request);
                                }
                                if (event instanceof TextBlockDeltaEvent deltaEvent
                                        && deltaEvent.getDelta() != null) {
                                    AgentBudgetService.OutputUsage delta =
                                            budgetService == null
                                                    ? new AgentBudgetService.OutputUsage(0, 0)
                                                    : budgetService.measureOutput(deltaEvent.getDelta());
                                    AgentBudgetService.OutputUsage total =
                                            new AgentBudgetService.OutputUsage(
                                                    outputTokens.addAndGet(delta.estimatedTokens()),
                                                    outputBytes.addAndGet(delta.bytes()));
                                    if (budgetService != null) {
                                        budgetService.requireOutputWithinLimit(total);
                                    }
                                }
                            })
                            .doFinally(signal -> {
                                unregisterActive(request, run);
                                recordOutputBudget(request, new AgentBudgetService.OutputUsage(
                                        outputTokens.get(), outputBytes.get()));
                                agent.close();
                            });
                })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(request, "executor")))
                .doOnError(e -> logGatewayError(e, "stream", request.requestId()));
    }

    public Mono<String> generateText(AgentRunRequest request) {
        return Mono.defer(() -> {
                    List<com.artverse.agent.AgentMessage> effectiveMessages = messagesFor(request);
                    validateInputBudget(request, effectiveMessages);
                    consumeModelBudget(request);
                    HarnessAgent agent = agentFactory.getOrCreate(request);
                    AgentScopeCallMessages callMessages = messageMapper.map(effectiveMessages);
                    RuntimeContext ctx = runtimeContextFactory.create(request, callMessages.systemContent());
                    List<Msg> messages = callMessages.inputMessages();
                    AgentRun<Msg> run = agent.prepareCall(messages, ctx);
                    registerActive(request, run);
                    return run.stream().singleOrEmpty()
                            .map(message -> enforceAndRecordOutput(request, message.getTextContent()))
                            .doFinally(signal -> {
                                unregisterActive(request, run);
                                agent.close();
                            });
                })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(request, "executor")))
                .doOnError(e -> logGatewayError(e, "generate", request.requestId()));
    }

    public <T> Mono<T> generateStructured(AgentRunRequest request, Class<T> outputType) {
        return Mono.defer(() -> {
                    List<com.artverse.agent.AgentMessage> effectiveMessages = messagesFor(request);
                    validateInputBudget(request, effectiveMessages);
                    consumeModelBudget(request);
                    HarnessAgent agent = agentFactory.getOrCreate(request);
                    AgentScopeCallMessages callMessages = messageMapper.map(effectiveMessages);
                    RuntimeContext ctx = runtimeContextFactory.create(request, callMessages.systemContent());
                    List<Msg> messages = callMessages.inputMessages();
                    AgentRun<Msg> run = AgentRun.create(
                            request.requestId() == null ? UUID.randomUUID().toString() : request.requestId().toString(),
                            () -> agent.call(messages, outputType, ctx).flux());
                    registerActive(request, run);
                    return run.stream().singleOrEmpty().map(message -> {
                        enforceAndRecordOutput(request, message.getTextContent());
                        T result = message.getStructuredData(outputType);
                        if (result == null) {
                            throw new BusinessException(502, "Agent returned empty structured output");
                        }
                        return result;
                    }).doFinally(signal -> {
                        unregisterActive(request, run);
                        agent.close();
                    });
                })
                .transformDeferred(RetryOperator.of(retry(request, "router")))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(request, "router")))
                .doOnError(e -> logGatewayError(e, "structured", request.requestId()));
    }

    private void consumeModelBudget(AgentRunRequest request) {
        if (budgetService != null) {
            budgetService.consumeModelCall(request);
        }
    }

    /**
     * Stops the currently executing AgentScope run, including its framework
     * managed subagents. The database terminal transition remains owned by
     * ArtVerse and is performed by the caller before invoking this method.
     */
    public boolean interrupt(UUID requestId) {
        if (requestId == null) {
            return false;
        }
        AgentRun<?> run = activeRuns.get(requestId);
        if (run == null) {
            return false;
        }
        return run.interrupt();
    }

    private void registerActive(AgentRunRequest request, AgentRun<?> run) {
        if (request.requestId() != null) {
            activeRuns.put(request.requestId(), run);
        }
    }

    private void unregisterActive(AgentRunRequest request, AgentRun<?> run) {
        if (request.requestId() != null) {
            activeRuns.remove(request.requestId(), run);
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
        budgetService.recordOutput(request, usage);
        budgetService.requireOutputWithinLimit(usage);
        return text;
    }

    private void recordOutputBudget(AgentRunRequest request, AgentBudgetService.OutputUsage usage) {
        if (budgetService == null) return;
        try {
            budgetService.recordOutput(request, usage);
        } catch (Exception error) {
            log.warn("Failed to persist output usage for requestId={}", request.requestId(), error);
        }
    }

    private CircuitBreaker circuitBreaker(AgentRunRequest request, String role) {
        return circuitBreakerRegistry.circuitBreaker(resilienceName(CB_NAME, request, role), circuitBreakerConfig);
    }

    private Retry retry(AgentRunRequest request, String role) {
        return retryRegistry.retry(resilienceName(RETRY_NAME, request, role), retryConfig);
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
