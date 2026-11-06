package com.artverse.agent.gateway;

import com.artverse.agent.AgentRunRequest;
import com.artverse.application.AgentBudgetService;
import com.artverse.application.AgentBudgetExceededException;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.*;
import io.agentscope.core.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One reservation per native Model subscription, including harness compaction. */
@Slf4j
final class BudgetedAgentModel implements Model {
    private final Model delegate;
    private final AgentRunRequest request;
    private final AgentBudgetService budget;
    private final AgentBudgetService.ModelLimits limits;
    private final com.artverse.application.StoryboardArtifactService readArtifacts;
    private final com.artverse.application.NovelModelReadService novelReads;

    BudgetedAgentModel(Model delegate, AgentRunRequest request, AgentBudgetService budget, ArtVerseProperties properties) {
        this(delegate, request, budget, properties, null);
    }

    BudgetedAgentModel(Model delegate, AgentRunRequest request, AgentBudgetService budget, ArtVerseProperties properties,
                       com.artverse.application.StoryboardArtifactService readArtifacts) {
        this(delegate, request, budget, properties, readArtifacts, null);
    }

    BudgetedAgentModel(Model delegate, AgentRunRequest request, AgentBudgetService budget, ArtVerseProperties properties,
                       com.artverse.application.StoryboardArtifactService readArtifacts,
                       com.artverse.application.NovelModelReadService novelReads) {
        this.delegate = Objects.requireNonNull(delegate);
        this.request = request;
        this.budget = budget;
        this.limits = AgentBudgetService.ModelLimits.from(properties);
        this.readArtifacts = readArtifacts;
        this.novelReads = novelReads;
    }

    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        final NativeRequest frozen;
        try {
            // Detach nested message metadata, schemas and response format before returning a cold stream.
            // The checked/hashed objects are also the objects passed to the native delegate.
            var codec = JsonUtils.getJsonCodec();
            List<Msg> detachedMessages = messages == null ? List.of() : messages.stream()
                    .map(message -> codec.fromJson(codec.toJson(message), Msg.class)).toList();
            List<ToolSchema> detachedTools = tools == null ? List.of() : tools.stream().map(tool -> ToolSchema.builder()
                    .name(tool.getName()).description(tool.getDescription()).strict(tool.getStrict())
                    .parameters(copyMap(tool.getParameters())).outputSchema(copyMap(tool.getOutputSchema())).build()).toList();
            GenerateOptions effective = boundedOptions(options);
            String payload = codec.toJson(Map.of("messages", detachedMessages, "tools", detachedTools, "options", effective));
            frozen = new NativeRequest(detachedMessages, detachedTools, effective, payload);
        } catch (RuntimeException error) {
            return Flux.error(error);
        }
        var subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true))
                return Flux.error(new BusinessException(409, "同一次模型请求不能重复订阅，请核对原尝试结果"));
            var execution = (readArtifacts == null && novelReads == null) || !request.taskType().isMangaExecutionTask() ? null
                    : new AgentScopeRuntimeContextFactory().create(request).get(com.artverse.agent.MangaAgentRuntimeContext.class);
            var reads = execution == null || readArtifacts == null ? java.util.List.<com.artverse.application.StoryboardArtifactService.ModelReadInput>of()
                    : AgentModelReadInputs.collect(frozen.messages());
            boolean reviewSummary = Boolean.TRUE.equals(request.variables().get("review_completed"));
            var novelInputs = execution == null || novelReads == null || reviewSummary
                    ? java.util.List.<com.artverse.application.StoryboardArtifactService.ModelReadInput>of()
                    : AgentModelReadInputs.collect(frozen.messages(), "get_novel_context");
            if (execution != null && readArtifacts != null) {
                if (request.taskType() == com.artverse.agent.AgentTaskType.MANGA_STORYBOARD
                        && frozen.tools().stream().anyMatch(tool -> "draft_structured_storyboard".equals(tool.getName()))) {
                    var plan = readArtifacts.previewModelReadPlan(execution, reads);
                    budget.requireMinimumRemaining(request, plan.minimumModelCalls(), plan.minimumToolCalls());
                } else if (!reads.isEmpty()) readArtifacts.validateModelReadInput(execution, reads);
            }
            if (execution != null && !novelInputs.isEmpty()) novelReads.validateModelReadInput(execution, novelInputs);
            var input = budget.measureOutput(frozen.payload());
            if (input.bytes() > limits.maxInputBytes())
                return Flux.error(new AgentBudgetExceededException("INPUT_BYTE", limits.maxInputBytes(), input.bytes()));
            if (input.estimatedTokens() > limits.maxInputTokens())
                return Flux.error(new AgentBudgetExceededException("INPUT_TOKEN_ESTIMATE", limits.maxInputTokens(), input.estimatedTokens()));
            UUID attempt = UUID.randomUUID();
            String hash = com.artverse.application.ToolIdempotencyService.sha256(frozen.payload());
            if (!budget.reserveModelCall(request, attempt, hash, limits))
                return Flux.error(new BusinessException(409, "模型尝试已预占，请核对原结果"));
            var outBytes = new AtomicLong();
            var outEstimate = new AtomicLong();
            var trailingHighSurrogates = new HashSet<String>();
            var reportedUsage = new AtomicReference<ChatUsage>();
            var consistentUsage = new AtomicBoolean(true);
            var observed = new AtomicBoolean();
            java.util.function.Consumer<SignalType> finish = signal -> {
                if (!observed.compareAndSet(false, true)) return;
                // Observation failure never refunds an attempt or masks the original model error.
                try {
                    budget.recordModelMeasurement(request, attempt, hash, "OUTPUT_BYTE", outBytes.get(), limits.maxOutputBytes());
                    budget.recordModelMeasurement(request, attempt, hash, "OUTPUT_TOKEN", outEstimate.get(), limits.maxOutputTokens());
                    budget.finishModelCall(request, attempt, hash, signal == SignalType.ON_COMPLETE ? "FINISHED" : "UNKNOWN",
                            reportedUsage.get(), consistentUsage.get());
                } catch (RuntimeException ignored) {
                    log.warn("Agent model observation persistence incomplete: requestId={}, attemptId={}", request.requestId(), attempt);
                }
            };
            return Flux.defer(() -> {
                budget.recordModelMeasurement(request, attempt, hash, "INPUT_BYTE", input.bytes(), limits.maxInputBytes());
                budget.recordModelMeasurement(request, attempt, hash, "INPUT_TOKEN", input.estimatedTokens(), limits.maxInputTokens());
                Duration remaining = budget.modelTimeAllowance(request, attempt);
                if (remaining == null || remaining.isNegative() || remaining.isZero())
                    return Flux.error(new BusinessException(409, "模型尝试缺少有效活动时长预算"));
                Flux<ChatResponse> accepted = Objects.requireNonNull(delegate.stream(frozen.messages(), frozen.tools(), frozen.options()));
                if (execution != null && !reads.isEmpty()) readArtifacts.recordModelReadInput(execution, attempt, hash, reads);
                if (execution != null && !novelInputs.isEmpty()) novelReads.recordModelReadInput(execution, attempt, hash, novelInputs);
                // The adapter and read-evidence transaction may take time. Recheck the original SQL clock
                // before subscription, so neither operation restarts the reserved attempt's deadline.
                long checkedAt = System.nanoTime();
                Duration dispatchRemaining = budget.modelTimeAllowance(request, attempt);
                if (dispatchRemaining == null || dispatchRemaining.isNegative() || dispatchRemaining.isZero())
                    return Flux.error(new BusinessException(409, "模型尝试缺少有效活动时长预算"));
                dispatchRemaining = dispatchRemaining.minusNanos(Math.max(0, System.nanoTime() - checkedAt));
                if (dispatchRemaining.isNegative() || dispatchRemaining.isZero())
                    return Flux.error(new AgentBudgetExceededException("MODEL_ACTIVITY_MILLIS", 0, 1));
                Duration configured = frozen.options().getExecutionConfig().getTimeout();
                Duration cutoff = dispatchRemaining.compareTo(configured) < 0 ? dispatchRemaining : configured;
                var deadlineReached = new AtomicBoolean();
                // An absolute deadline, independent of idle timeout: receiving another token cannot reset it.
                // takeUntilOther cancels its source on a value; its error path does not cancel an active source.
                return accepted
                        .takeUntilOther(reactor.core.publisher.Mono.delay(cutoff)
                                .doOnNext(ignored -> deadlineReached.set(true)))
                        .concatWith(Flux.defer(() -> deadlineReached.get()
                                ? Flux.error(new AgentBudgetExceededException(
                                        "MODEL_ACTIVITY_MILLIS", cutoff.toMillis(), cutoff.toMillis() + 1))
                                : Flux.empty()));
            }).doOnNext(response -> {
                if (response.getUsage() != null) {
                    ChatUsage current = response.getUsage();
                    ChatUsage prior = reportedUsage.getAndSet(current);
                    // The configured OpenAI-compatible native parser reports cumulative usage, usually in the final chunk.
                    // Repeated reports replace, rather than add to, the previous report.
                    if (!validUsage(current) || (prior != null && !nondecreasingUsage(prior, current)))
                        consistentUsage.set(false);
                }
                for (ContentBlock block : response.getContent()) {
                    // Native streaming blocks carry deltas. Do not charge a JSON wrapper per text token.
                    String delta = switch (block) {
                        case TextBlock text -> text.getText();
                        case ThinkingBlock thought -> thought.getThinking();
                        case ToolUseBlock tool -> tool.getContent() != null ? tool.getContent()
                                : JsonUtils.getJsonCodec().toJson(tool.getInput());
                        default -> JsonUtils.getJsonCodec().toJson(block);
                    };
                    String channel = switch (block) {
                        case TextBlock ignored -> "text";
                        case ThinkingBlock ignored -> "thinking";
                        case ToolUseBlock tool -> "tool:" + tool.getId();
                        default -> block.getClass().getName();
                    };
                    boolean pairAcrossChunks = delta != null && !delta.isEmpty()
                            && trailingHighSurrogates.contains(channel) && Character.isLowSurrogate(delta.charAt(0));
                    if (delta != null && !delta.isEmpty()) {
                        if (Character.isHighSurrogate(delta.charAt(delta.length() - 1))) trailingHighSurrogates.add(channel);
                        else trailingHighSurrogates.remove(channel);
                    }
                    var measured = budget.measureOutput(delta);
                    // Java encodes an isolated surrogate as one replacement byte. A pair split between
                    // deltas instead contributes four UTF-8 bytes and one code point across the two chunks.
                    long tokens = outEstimate.addAndGet(measured.estimatedTokens() - (pairAcrossChunks ? 1 : 0));
                    long bytes = outBytes.addAndGet(measured.bytes() + (pairAcrossChunks ? 2 : 0));
                    if (tokens > limits.maxOutputTokens())
                        throw new AgentBudgetExceededException("OUTPUT_TOKEN_ESTIMATE", limits.maxOutputTokens(), tokens);
                    if (bytes > limits.maxOutputBytes())
                        throw new AgentBudgetExceededException("OUTPUT_BYTE", limits.maxOutputBytes(), bytes);
                }
            }).doOnComplete(() -> finish.accept(SignalType.ON_COMPLETE))
                    .doOnError(error -> finish.accept(SignalType.ON_ERROR))
                    .doOnCancel(() -> finish.accept(SignalType.CANCEL));
        });
    }

    private GenerateOptions boundedOptions(GenerateOptions supplied) {
        GenerateOptions options = supplied == null ? GenerateOptions.builder().build() : supplied;
        if (options.getApiKey() != null || options.getBaseUrl() != null || options.getModelName() != null
                || options.getEndpointPath() != null || !options.getAdditionalHeaders().isEmpty()
                || !options.getAdditionalQueryParams().isEmpty() || !options.getAdditionalBodyParams().isEmpty())
            throw new BusinessException(403, "模型调用不能覆盖已冻结的配置身份或扩展请求参数");
        if (options.getMaxTokens() != null && options.getMaxCompletionTokens() != null)
            throw new BusinessException(400, "同一次模型调用只能选择一种输出 token 上限字段");
        Integer maxTokens = checkedOutputLimit(options.getMaxTokens());
        Integer maxCompletion = checkedOutputLimit(options.getMaxCompletionTokens());
        if (maxTokens == null && maxCompletion == null) maxTokens = limits.maxOutputTokens();
        if (options.getThinkingBudget() != null && (options.getThinkingBudget() < 0
                || options.getThinkingBudget() > (maxTokens != null ? maxTokens : maxCompletion)))
            throw new BusinessException(400, "推理 token 预算不能超过本次输出上限");
        Duration timeout = Duration.ofSeconds(limits.modelTimeoutSeconds());
        Duration requested = options.getExecutionConfig() == null ? null : options.getExecutionConfig().getTimeout();
        if (requested != null) {
            if (requested.isNegative() || requested.isZero()) throw new BusinessException(400, "模型调用超时必须为正值");
            if (requested.compareTo(timeout) < 0) timeout = requested;
        }
        ResponseFormat format = options.getResponseFormat() == null ? null : JsonUtils.getJsonCodec().fromJson(
                JsonUtils.getJsonCodec().toJson(options.getResponseFormat()), ResponseFormat.class);
        // No extended maps are accepted. Preserve the native typed generation and structured-output options.
        return GenerateOptions.builder().stream(options.getStream()).temperature(options.getTemperature()).topP(options.getTopP())
                .maxTokens(maxTokens).maxCompletionTokens(maxCompletion).frequencyPenalty(options.getFrequencyPenalty())
                .presencePenalty(options.getPresencePenalty()).thinkingBudget(options.getThinkingBudget())
                .reasoningEffort(options.getReasoningEffort()).toolChoice(options.getToolChoice()).topK(options.getTopK())
                .seed(options.getSeed()).cacheControl(options.getCacheControl()).parallelToolCalls(options.getParallelToolCalls())
                .responseFormat(format).executionConfig(ExecutionConfig.builder().maxAttempts(1).timeout(timeout).build()).build();
    }

    private Integer checkedOutputLimit(Integer value) {
        if (value != null && (value <= 0 || value > limits.maxOutputTokens()))
            throw new BusinessException(400, "模型输出 token 上限超出本次允许范围");
        return value;
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? null : JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(value),
                new TypeReference<Map<String, Object>>() {});
    }

    private static boolean validUsage(ChatUsage usage) {
        return usage.getInputTokens() >= 0 && usage.getOutputTokens() >= 0 && usage.getCachedTokens() >= 0
                && usage.getCacheCreationTokens() >= 0 && usage.getReasoningTokens() >= 0 && usage.getToolUsePromptTokens() >= 0
                && usage.getCachedTokens() <= usage.getInputTokens() && usage.getCacheCreationTokens() <= usage.getInputTokens()
                && usage.getToolUsePromptTokens() <= usage.getInputTokens() && usage.getReasoningTokens() <= usage.getOutputTokens();
    }

    private static boolean nondecreasingUsage(ChatUsage before, ChatUsage after) {
        return after.getInputTokens() >= before.getInputTokens() && after.getOutputTokens() >= before.getOutputTokens()
                && after.getCachedTokens() >= before.getCachedTokens() && after.getCacheCreationTokens() >= before.getCacheCreationTokens()
                && after.getReasoningTokens() >= before.getReasoningTokens() && after.getToolUsePromptTokens() >= before.getToolUsePromptTokens();
    }

    private record NativeRequest(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, String payload) { }

    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
}
