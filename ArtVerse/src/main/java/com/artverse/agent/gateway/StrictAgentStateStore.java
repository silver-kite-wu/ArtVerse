package com.artverse.agent.gateway;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConcurrentSessionModificationException;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import com.artverse.common.BusinessException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rejects native CAS conflicts before 2.0.4's implicit OVERWRITE fallback can run. */
public final class StrictAgentStateStore implements AgentStateStore, AutoCloseable {
    private static final String AGENT_STATE = "agent_state";
    private final AgentStateStore delegate;
    private final String actor;
    private final String session;
    private final boolean writable;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    public StrictAgentStateStore(AgentStateStore delegate) {
        this(delegate, null, null, true, () -> { });
    }

    static StrictAgentStateStore forTurn(AgentStateStore delegate, String actor, String session) {
        return new StrictAgentStateStore(delegate, Objects.requireNonNull(actor), Objects.requireNonNull(session),
                true, delegate::close);
    }

    public static StrictAgentStateStore readOnly(AgentStateStore delegate) {
        return new StrictAgentStateStore(delegate, null, null, false, () -> { });
    }

    private StrictAgentStateStore(AgentStateStore delegate, String actor, String session,
                                  boolean writable, Runnable release) {
        this.delegate = Objects.requireNonNull(delegate);
        this.actor = actor;
        this.session = session;
        this.writable = writable;
        this.release = release;
        if (!delegate.supportsVersioning()) {
            throw new IllegalArgumentException("Recoverable agents require a versioned state store");
        }
    }

    @Override public boolean supportsVersioning() { return true; }

    @Override public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        requireScope(userId, sessionId);
        for (int pair = 0; pair < 3; pair++) {
            var first = delegate.getVersioned(userId, sessionId, key, type);
            var second = delegate.getVersioned(userId, sessionId, key, type);
            if (first.version() < 0 || second.version() < 0)
                throw conflict(userId, sessionId, key, second.version());
            if (first.version() == second.version()) return second;
        }
        throw conflict(userId, sessionId, key, UNVERSIONED);
    }

    @Override public long saveIfVersion(String userId, String sessionId, String key, State value, long expected) {
        requireWrite(userId, sessionId);
        if (AGENT_STATE.equals(key) && expected < 0) throw conflict(userId, sessionId, key, expected);
        long version = delegate.saveIfVersion(userId, sessionId, key, value, expected);
        if (AGENT_STATE.equals(key) && version < 0) throw conflict(userId, sessionId, key, expected);
        return version;
    }

    @Override public void save(String userId, String sessionId, String key, State value) {
        requireWrite(userId, sessionId);
        rejectUnconditional(userId, sessionId, key);
        delegate.save(userId, sessionId, key, value);
    }

    @Override public void save(String userId, String sessionId, String key, List<? extends State> values) {
        requireWrite(userId, sessionId);
        rejectUnconditional(userId, sessionId, key);
        delegate.save(userId, sessionId, key, values);
    }

    @Override public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        requireScope(userId, sessionId);
        if (AGENT_STATE.equals(key)) return Optional.ofNullable(getVersioned(userId, sessionId, key, type).value());
        return delegate.get(userId, sessionId, key, type);
    }

    @Override public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        requireScope(userId, sessionId);
        return delegate.getList(userId, sessionId, key, type);
    }

    @Override public boolean exists(String userId, String sessionId) {
        requireScope(userId, sessionId); return delegate.exists(userId, sessionId);
    }
    @Override public Set<String> listSessionIds(String userId) {
        if (actor != null) throw new BusinessException(403, "本轮不能枚举其他会话");
        return delegate.listSessionIds(userId);
    }
    @Override public void delete(String userId, String sessionId) {
        throw new BusinessException(403, "运行状态不能通过执行视图删除");
    }
    @Override public void delete(String userId, String sessionId, String key) {
        throw new BusinessException(403, "运行状态不能通过执行视图删除");
    }

    // Only a scoped view owns its delegate. Shared read views retain the Spring-owned client.
    @Override public void close() {
        if (actor != null && closed.compareAndSet(false, true)) release.run();
    }

    private void requireScope(String userId, String sessionId) {
        if (closed.get()) throw new BusinessException(409, "本轮会话状态视图已关闭");
        if (actor != null && (!actor.equals(userId) || !session.equals(sessionId)))
            throw new BusinessException(403, "会话状态不属于本轮执行");
    }

    private void requireWrite(String userId, String sessionId) {
        requireScope(userId, sessionId);
        if (!writable) throw new BusinessException(403, "共享会话状态视图只允许读取");
    }

    private static void rejectUnconditional(String userId, String sessionId, String key) {
        if (AGENT_STATE.equals(key)) throw conflict(userId, sessionId, key, UNVERSIONED);
    }

    private static ConcurrentSessionModificationException conflict(String userId, String sessionId, String key, long expected) {
        return new ConcurrentSessionModificationException(userId, sessionId, key, expected);
    }
}
