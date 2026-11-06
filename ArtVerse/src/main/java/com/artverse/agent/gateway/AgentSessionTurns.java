package com.artverse.agent.gateway;

import com.artverse.agent.AgentRunRequest;
import com.artverse.common.BusinessException;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** The native turn-gate contract, backed by the application's existing Redis connection. */
@Slf4j
@Component
public class AgentSessionTurns implements SessionTurnGate {
    private static final Duration TTL = Duration.ofSeconds(90);
    private static final String ACQUIRE = """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
            return 0
            """;
    private static final String RENEW = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """;
    private static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;
    private static final String CHECK_OWNER = """
            if redis.call('GET', KEYS[#KEYS]) ~= ARGV[#ARGV] then return -2 end
            """;
    private final RedisClientAdapter redis;
    private final ConcurrentMap<String, Lease> held = new ConcurrentHashMap<>();

    public AgentSessionTurns(RedisClientAdapter redis) { this.redis = Objects.requireNonNull(redis); }

    public Lease acquire(AgentRunRequest request) {
        if (request.userId() == null || request.userId().isBlank()) throw new BusinessException(403, "执行身份缺失");
        try {
            return acquire(keyFor(request));
        } catch (TurnBusyException error) {
            throw new BusinessException(409, "会话正在执行，请查看原任务进度后重试");
        }
    }

    static String keyFor(AgentRunRequest request) {
        return request.userId() + ":" + AgentScopeRuntimeContextFactory.createSessionId(request);
    }

    @Override public Lease acquire(String key) throws TurnBusyException {
        String redisKey = redisKey(key);
        String owner = UUID.randomUUID().toString();
        try {
            if (redis.evalScript(ACQUIRE, List.of(redisKey), List.of(owner, String.valueOf(TTL.toMillis()))) != 1)
                throw new TurnBusyException(redisKey);
        } catch (TurnBusyException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable();
        }
        var lease = new Lease(key, redisKey, owner);
        held.put(owner, lease);
        return lease;
    }

    @Override public boolean isRunning(String key) {
        try { return redis.keyExists(redisKey(key)); }
        catch (RuntimeException error) { throw unavailable(); }
    }

    @Scheduled(fixedDelay = 20_000)
    public void renewOwnedTurns() {
        held.values().forEach(lease -> {
            if (lease.closed.get()) return;
            try {
                if (redis.evalScript(RENEW, List.of(lease.key), List.of(lease.owner, String.valueOf(TTL.toMillis()))) != 1) {
                    lease.lose();
                }
            } catch (RuntimeException error) {
                // An uncertain lease cannot authorize any more model/tool execution.
                lease.lose();
            }
        });
    }

    public final class Lease implements TurnLease {
        private final String sessionKey;
        private final String key;
        private final String owner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private Runnable interrupt;
        private boolean notified;

        private Lease(String sessionKey, String key, String owner) {
            this.sessionKey = sessionKey;
            this.key = key;
            this.owner = owner;
        }

        public synchronized void onLost(Runnable callback) {
            if (interrupt != null) throw new IllegalStateException("Turn loss handler is already registered");
            interrupt = Objects.requireNonNull(callback);
            notifyLost();
        }

        public void requireOwned() {
            requireActive();
            try {
                if (!owner.equals(redis.get(key))) {
                    lose();
                    throw new BusinessException(409, "会话执行权已失效，已有结果保留");
                }
            } catch (BusinessException error) { throw error; }
            catch (RuntimeException error) { lose(); throw unavailable(); }
        }

        public void requireRequest(AgentRunRequest request) {
            if (!sessionKey.equals(keyFor(request))) throw new BusinessException(403, "会话租约不属于本次请求");
            requireOwned();
        }

        private void lose() {
            if (lost.compareAndSet(false, true)) {
                held.remove(owner, this);
                notifyLost();
            }
        }

        private synchronized void notifyLost() {
            if (!lost.get() || interrupt == null || notified) return;
            notified = true;
            try { interrupt.run(); } catch (RuntimeException error) { log.warn("Could not interrupt lost agent turn"); }
        }

        void requireActive() {
            if (closed.get() || lost.get()) throw new BusinessException(409, "会话执行权已失效，已有结果保留");
        }

        boolean belongsTo(String actor, String session, RedisClientAdapter client) {
            return redis == client && sessionKey.equals(actor + ":" + session);
        }

        // Ownership and the framework's original CAS execute in one Redis operation.
        long saveOwned(String script, List<String> keys, List<String> args) {
            requireActive();
            var guardedKeys = new ArrayList<>(keys); guardedKeys.add(key);
            var guardedArgs = new ArrayList<>(args); guardedArgs.add(owner);
            long result;
            try { result = redis.evalScript(CHECK_OWNER + script, guardedKeys, guardedArgs); }
            catch (RuntimeException error) { lose(); throw unavailable(); }
            if (result == -2) {
                lose();
                throw new BusinessException(409, "会话执行权已失效，已有结果保留");
            }
            return result;
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            held.remove(owner, this);
            try { redis.evalScript(RELEASE, List.of(key), List.of(owner)); }
            catch (RuntimeException error) { log.warn("Could not release agent turn; its lease will expire"); }
        }
    }

    private static String redisKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Session key is required");
        try {
            return "artverse:agent:turn:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static BusinessException unavailable() { return new BusinessException(503, "暂时无法取得会话执行权，请稍后重试"); }
}
