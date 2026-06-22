package com.artverse.application;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
public class AgentRunToolStatus {

    private static final Set<String> MUTATING_TOOLS = Set.of(
            "generate_storyboard",
            "save_storyboard",
            "save_structured_storyboard"
    );

    private final ConcurrentMap<ScopeKey, RunState> activeRuns = new ConcurrentHashMap<>();
    private final ConcurrentMap<RunKey, AgentUserInputRequest> waitingInputs = new ConcurrentHashMap<>();

    public RunScope start(Long userId, Long chapterId, UUID requestId) {
        return start(userId, chapterId, requestId, null);
    }

    public RunScope start(Long userId, Long chapterId, UUID requestId, Consumer<ToolEvent> listener) {
        ScopeKey key = new ScopeKey(userId, chapterId, requestId);
        RunState state = new RunState(userId, chapterId, requestId, listener);
        activeRuns.put(key, state);
        return new RunScope(key, state);
    }

    public void recordSucceeded(String toolName, Long userId, Long chapterId, long durationMs,
                                Map<String, Object> result) {
        record(new ToolEvent(toolName, true, durationMs, null, result == null ? Map.of() : new LinkedHashMap<>(result)),
                userId, chapterId);
    }

    public void recordSucceeded(String toolName, Long userId, Long chapterId, UUID requestId, long durationMs,
                                Map<String, Object> result) {
        record(new ToolEvent(toolName, true, durationMs, null, result == null ? Map.of() : new LinkedHashMap<>(result)),
                new ScopeKey(userId, chapterId, requestId));
    }

    public void recordFailed(String toolName, Long userId, Long chapterId, long durationMs, String error) {
        record(new ToolEvent(toolName, false, durationMs, error, Map.of()), userId, chapterId);
    }

    public void recordFailed(String toolName, Long userId, Long chapterId, UUID requestId, long durationMs, String error) {
        record(new ToolEvent(toolName, false, durationMs, error, Map.of()), new ScopeKey(userId, chapterId, requestId));
    }

    public void requestUserInput(Long userId, Long chapterId, UUID requestId, AgentUserInputRequest request) {
        waitingInputs.put(new RunKey(userId, chapterId, requestId), request);
        RunState state = activeRuns.get(new ScopeKey(userId, chapterId, requestId));
        if (state != null) {
            state.setUserInputRequest(request);
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
        return waitingInputs.get(new RunKey(userId, chapterId, requestId));
    }

    public void clearWaitingInput(Long userId, Long chapterId, UUID requestId) {
        waitingInputs.remove(new RunKey(userId, chapterId, requestId));
    }

    private void record(ToolEvent event, Long userId, Long chapterId) {
        RunState state = singleActiveState(userId, chapterId);
        if (state == null) {
            return;
        }
        state.add(event);
    }

    private void record(ToolEvent event, ScopeKey key) {
        RunState state = activeRuns.get(key);
        if (state != null) {
            state.add(event);
        }
    }

    private RunState singleActiveState(Long userId, Long chapterId) {
        List<RunState> matches = activeRuns.entrySet().stream()
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
            activeRuns.remove(key, state);
        }
    }

    public static final class RunState {
        private final Long userId;
        private final Long chapterId;
        private final UUID requestId;
        private final Consumer<ToolEvent> listener;
        private final CopyOnWriteArrayList<ToolEvent> events = new CopyOnWriteArrayList<>();
        private volatile AgentUserInputRequest userInputRequest;

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
    }

    public record ToolEvent(String toolName,
                            boolean succeeded,
                            long durationMs,
                            String error,
                            Map<String, Object> result) {
    }

    private record ScopeKey(Long userId, Long chapterId, UUID requestId) {
        private boolean matches(Long userId, Long chapterId) {
            return java.util.Objects.equals(this.userId, userId)
                    && java.util.Objects.equals(this.chapterId, chapterId);
        }
    }

    private record RunKey(Long userId, Long chapterId, UUID requestId) {
    }
}
