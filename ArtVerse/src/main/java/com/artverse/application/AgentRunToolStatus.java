package com.artverse.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tracks tool execution outcomes and pending user inputs per agent run.
 *
 * <p>Uses a local cache for hot-path performance with Redis as the shared
 * backing store for multi-instance deployments. TTL is 10 minutes — long
 * enough for user response but bounded to prevent leaks.</p>
 */
@Service
public class AgentRunToolStatus {

    private static final Set<String> MUTATING_TOOLS = Set.of(
            "generate_storyboard",
            "save_storyboard",
            "save_structured_storyboard",
            "commit_storyboard",
            "commit_novel_content"
    );

    private static final String KEY_PREFIX = "artverse:tool_status:";
    private static final String STATE_KEY_PREFIX = KEY_PREFIX + "state:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;
    private final MangaAgentRunService mangaAgentRunService;
    private final ConcurrentMap<ScopeKey, RunState> localCache = new ConcurrentHashMap<>();

    public AgentRunToolStatus(RedisTemplate<String, Object> redisTemplate) {
        this(redisTemplate, null);
    }

    @Autowired
    public AgentRunToolStatus(RedisTemplate<String, Object> redisTemplate,
                              MangaAgentRunService mangaAgentRunService) {
        this.redisTemplate = redisTemplate;
        this.mangaAgentRunService = mangaAgentRunService;
    }

    private static String stateKey(Long userId, Long chapterId, UUID requestId) {
        return STATE_KEY_PREFIX + userId + ":" + chapterId + ":" + requestId;
    }

    public RunScope start(Long userId, Long chapterId, UUID requestId) {
        return start(userId, chapterId, requestId, null);
    }

    public RunScope start(Long userId, Long chapterId, UUID requestId, Consumer<ToolEvent> listener) {
        ScopeKey key = new ScopeKey(userId, chapterId, requestId);
        RunState state = new RunState(userId, chapterId, requestId, listener);
        localCache.put(key, state);
        persistState(state);
        return new RunScope(key, state);
    }

    public void recordSucceeded(String toolName, Long userId, Long chapterId, long durationMs,
                                Map<String, Object> result) {
        record(new ToolEvent(null, null, toolName, "SUCCEEDED", true, durationMs,
                        resultHash(result), null, auditId(result),
                        result == null ? Map.of() : new LinkedHashMap<>(result), OffsetDateTime.now()),
                userId, chapterId);
    }

    public void recordSucceeded(String toolName, Long userId, Long chapterId, UUID requestId, long durationMs,
                                Map<String, Object> result) {
        record(new ToolEvent(requestId, null, toolName, "SUCCEEDED", true, durationMs,
                        resultHash(result), null, auditId(result),
                        result == null ? Map.of() : new LinkedHashMap<>(result), OffsetDateTime.now()),
                new ScopeKey(userId, chapterId, requestId));
    }

    public void recordSucceeded(String toolName, Long userId, Long chapterId, UUID requestId, String stepId,
                                String auditId, String resultHash, long durationMs,
                                Map<String, Object> result) {
        record(new ToolEvent(requestId, stepId, toolName, "SUCCEEDED", true, durationMs,
                        resultHash, null, auditId,
                        result == null ? Map.of() : new LinkedHashMap<>(result), OffsetDateTime.now()),
                new ScopeKey(userId, chapterId, requestId));
    }

    public void recordFailed(String toolName, Long userId, Long chapterId, long durationMs, String error) {
        record(new ToolEvent(null, null, toolName, "FAILED", false, durationMs,
                null, error, null, Map.of(), OffsetDateTime.now()), userId, chapterId);
    }

    public void recordFailed(String toolName, Long userId, Long chapterId, UUID requestId, long durationMs, String error) {
        record(new ToolEvent(requestId, null, toolName, "FAILED", false, durationMs,
                null, error, null, Map.of(), OffsetDateTime.now()), new ScopeKey(userId, chapterId, requestId));
    }

    public void recordFailed(String toolName, Long userId, Long chapterId, UUID requestId, String stepId,
                             String auditId, long durationMs, String error) {
        record(new ToolEvent(requestId, stepId, toolName, "FAILED", false, durationMs,
                null, error, auditId, Map.of(), OffsetDateTime.now()), new ScopeKey(userId, chapterId, requestId));
    }

    public void requestUserInput(Long userId, Long chapterId, UUID requestId, AgentUserInputRequest request) {
        String inputKey = KEY_PREFIX + "input:" + userId + ":" + chapterId + ":" + requestId;
        redisTemplate.opsForValue().set(inputKey, request, CACHE_TTL);

        RunState state = localCache.get(new ScopeKey(userId, chapterId, requestId));
        if (state != null) {
            state.setUserInputRequest(request);
            persistState(state);
        }
    }

    public UUID requestUserInputForActiveRun(Long userId, Long chapterId, AgentUserInputRequest request) {
        RunState state = singleActiveState(userId, chapterId);
        if (state == null) {
            return null;
        }
        requestUserInput(userId, chapterId, state.requestId(), request);
        return state.requestId();
    }

    public AgentUserInputRequest waitingInput(Long userId, Long chapterId, UUID requestId) {
        RunState state = localCache.get(new ScopeKey(userId, chapterId, requestId));
        if (state != null && state.userInputRequest() != null) {
            return state.userInputRequest();
        }
        String inputKey = KEY_PREFIX + "input:" + userId + ":" + chapterId + ":" + requestId;
        Object cached = redisTemplate.opsForValue().get(inputKey);
        if (cached instanceof AgentUserInputRequest request) {
            return request;
        }
        return null;
    }

    public void markCancelled(Long userId, Long chapterId, UUID requestId) {
        RunState state = localCache.get(new ScopeKey(userId, chapterId, requestId));
        if (state != null) {
            state.markCancelled();
        }
    }

    public void clearWaitingInput(Long userId, Long chapterId, UUID requestId) {
        localCache.remove(new ScopeKey(userId, chapterId, requestId));
        String inputKey = KEY_PREFIX + "input:" + userId + ":" + chapterId + ":" + requestId;
        redisTemplate.delete(inputKey);
        redisTemplate.delete(stateKey(userId, chapterId, requestId));
    }

    private void record(ToolEvent event, Long userId, Long chapterId) {
        RunState state = singleActiveState(userId, chapterId);
        if (state == null) {
            return;
        }
        state.add(event);
        persistState(state);
        persistAuditEvent(new ScopeKey(userId, chapterId, state.requestId()), event);
    }

    private void record(ToolEvent event, ScopeKey key) {
        RunState state = localCache.get(key);
        if (state != null) {
            state.add(event);
            persistState(state);
        }
        persistAuditEvent(key, event);
    }

    private void persistAuditEvent(ScopeKey key, ToolEvent event) {
        if (mangaAgentRunService == null || key == null || key.requestId() == null || event == null) {
            return;
        }
        mangaAgentRunService.appendToolAuditEvent(key.userId(), key.chapterId(), key.requestId(), event);
    }

    private void persistState(RunState state) {
        redisTemplate.opsForValue().set(
                stateKey(state.userId(), state.chapterId(), state.requestId()),
                new RunStateSnapshot(
                        state.userId(),
                        state.chapterId(),
                        state.requestId(),
                        List.copyOf(state.events()),
                        state.userInputRequest()
                ),
                CACHE_TTL
        );
    }

    private RunState singleActiveState(Long userId, Long chapterId) {
        List<RunState> matches = localCache.entrySet().stream()
                .filter(entry -> entry.getKey().matches(userId, chapterId))
                .map(Map.Entry::getValue)
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public final class RunScope implements AutoCloseable {
        private final ScopeKey key;
        private final RunState state;
        private boolean closed;

        private RunScope(ScopeKey key, RunState state) {
            this.key = key;
            this.state = state;
        }

        public RunState state() {
            return state;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            localCache.remove(key, state);
            redisTemplate.delete(stateKey(state.userId(), state.chapterId(), state.requestId()));
        }
    }

    public static final class RunState {
        private final Long userId;
        private final Long chapterId;
        private final UUID requestId;
        private final Consumer<ToolEvent> listener;
        private final CopyOnWriteArrayList<ToolEvent> events = new CopyOnWriteArrayList<>();
        private volatile AgentUserInputRequest userInputRequest;
        private volatile boolean cancelled;

        private RunState(Long userId, Long chapterId, UUID requestId, Consumer<ToolEvent> listener) {
            this.userId = userId;
            this.chapterId = chapterId;
            this.requestId = requestId;
            this.listener = listener;
        }

        private void add(ToolEvent event) {
            events.add(event);
            if (listener != null) {
                listener.accept(event);
            }
        }

        public Long userId() {
            return userId;
        }

        public Long chapterId() {
            return chapterId;
        }

        public UUID requestId() {
            return requestId;
        }

        public List<ToolEvent> events() {
            return List.copyOf(events);
        }

        public List<ToolEvent> eventsForStep(String stepId) {
            String expected = normalizeStepId(stepId);
            return events.stream()
                    .filter(event -> normalizeStepId(event.stepId()).equals(expected))
                    .toList();
        }

        public List<ToolEvent> successfulMutatingEvents() {
            return events.stream()
                    .filter(event -> event.succeeded() && MUTATING_TOOLS.contains(event.toolName()))
                    .toList();
        }

        public boolean hasSuccessfulMutatingTool() {
            return !successfulMutatingEvents().isEmpty();
        }

        public ToolEvent lastSuccessfulMutatingEvent() {
            List<ToolEvent> successful = successfulMutatingEvents();
            return successful.isEmpty() ? null : successful.get(successful.size() - 1);
        }

        private void setUserInputRequest(AgentUserInputRequest userInputRequest) {
            this.userInputRequest = userInputRequest;
        }

        public AgentUserInputRequest userInputRequest() {
            return userInputRequest;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        private void markCancelled() {
            this.cancelled = true;
        }
    }

    private record RunStateSnapshot(Long userId,
                                    Long chapterId,
                                    UUID requestId,
                                    List<ToolEvent> events,
                                    AgentUserInputRequest userInputRequest) {
    }

    public record ToolEvent(UUID requestId,
                            String stepId,
                            String toolName,
                            String status,
                            boolean succeeded,
                            long durationMs,
                            String resultHash,
                            String error,
                            String auditId,
                            Map<String, Object> result,
                            OffsetDateTime createdAt) {
    }

    private record ScopeKey(Long userId, Long chapterId, UUID requestId) {
        private boolean matches(Long userId, Long chapterId) {
            return java.util.Objects.equals(this.userId, userId)
                    && java.util.Objects.equals(this.chapterId, chapterId);
        }
    }
    private static String normalizeStepId(String stepId) {
        return stepId == null || stepId.isBlank() ? "unknown" : stepId;
    }

    private static String auditId(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        Object value = result.get("auditId");
        return value == null ? null : String.valueOf(value);
    }

    private static String resultHash(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        Object value = result.get("resultHash");
        return value == null ? null : String.valueOf(value);
    }
}
