package com.artverse.agent.gateway;

import com.artverse.agent.AgentRunRequest;
import com.artverse.common.BusinessException;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import io.agentscope.extensions.redis.state.RedisStateVersionSupport;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-invocation views of the native store; no application message format or CAS implementation. */
public final class AgentTurnStateStores {
    public static final String KEY_PREFIX = "artverse:agent:";
    private final RedisClientAdapter client;
    private final boolean cluster;

    public AgentTurnStateStores(RedisClientAdapter client, boolean cluster) {
        this.client = Objects.requireNonNull(client);
        this.cluster = cluster;
    }

    public StrictAgentStateStore forTurn(AgentRunRequest request, AgentSessionTurns.Lease lease) {
        return forTurn(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request), lease);
    }

    StrictAgentStateStore forTurn(String actor, String session, AgentSessionTurns.Lease lease) {
        if (cluster) throw new BusinessException(503, "当前 Redis Cluster 尚未验证会话原子保护，不能启动工具运行");
        if (actor == null || actor.isBlank() || session == null || session.isBlank()
                || !lease.belongsTo(actor, session, client))
            throw new BusinessException(403, "会话租约与状态后端不匹配");
        lease.requireOwned();
        var nativeStore = RedisAgentStateStore.builder().keyPrefix(KEY_PREFIX)
                .clientAdapter(new TurnClient(client, lease)).build();
        return StrictAgentStateStore.forTurn(nativeStore, actor, session);
    }

    private static final class TurnClient implements RedisClientAdapter {
        private final RedisClientAdapter delegate;
        private final AgentSessionTurns.Lease lease;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TurnClient(RedisClientAdapter delegate, AgentSessionTurns.Lease lease) {
            this.delegate = delegate;
            this.lease = lease;
        }

        private void requireActive() {
            if (closed.get()) throw new BusinessException(409, "本轮会话状态视图已关闭");
            lease.requireActive();
        }

        @Override public long evalScript(String script, List<String> keys, List<String> args) {
            requireActive();
            if (!RedisStateVersionSupport.SAVE_SCRIPT.equals(script) || keys.size() != 3 || args.size() != 3
                    || !"agent_state".equals(args.get(2)))
                throw new BusinessException(403, "当前会话不允许此状态写入");
            return lease.saveOwned(script, keys, args);
        }

        @Override public String get(String key) { requireActive(); return delegate.get(key); }
        @Override public List<String> rangeList(String key, long start, long end) {
            requireActive(); return delegate.rangeList(key, start, end);
        }
        @Override public long getListLength(String key) { requireActive(); return delegate.getListLength(key); }
        @Override public Set<String> getSetMembers(String key) { requireActive(); return delegate.getSetMembers(key); }
        @Override public long getSetSize(String key) { requireActive(); return delegate.getSetSize(key); }
        @Override public boolean keyExists(String key) { requireActive(); return delegate.keyExists(key); }
        @Override public Set<String> findKeysByPattern(String pattern) { throw unsupported(); }
        @Override public void set(String key, String value) { throw unsupported(); }
        @Override public void rightPushList(String key, String value) { throw unsupported(); }
        @Override public void deleteKeys(String... keys) { throw unsupported(); }
        @Override public void addToSet(String key, String member) { throw unsupported(); }
        @Override public void close() { closed.set(true); }

        private static BusinessException unsupported() {
            return new BusinessException(403, "本轮不能绕过原生 CAS 修改会话状态");
        }
    }
}
