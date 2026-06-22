package com.artverse.guard;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String PROCESSING_MESSAGE = "请求正在处理中";

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final ArtVerseProperties properties;
    private final GuardMetricsService metricsService;
    private final GuardEventRecorder eventRecorder;

    public Map<String, Object> executeHttp(String action, String userId, Map<String, Object> canonicalPayload,
                                           Callable<Map<String, Object>> leader) {
        if (!properties.getIdempotency().isEnabled()) {
            return callLeader(leader);
        }

        metricsService.increment(action, "total");
        String key = buildKey(action, userId, canonicalPayload);
        String followersKey = key + ":followers";
        String channel = key + ":channel";

        Map<String, Object> existing = readState(key);
        if (isSucceeded(existing)) {
            metricsService.increment(action, "success_hit");
            eventRecorder.record(action, userId, "success_hit", "reused", key, canonicalPayload, null, "returned cached success");
            return resultWithHit(existing);
        }
        if (isFailed(existing)) {
            metricsService.increment(action, "failed_hit");
            eventRecorder.record(action, userId, "failed_hit", "failed", key, canonicalPayload, null, "returned cached failure");
            throw new BusinessException(502, String.valueOf(existing.getOrDefault("error", "Request failed")));
        }
        if (isProcessing(existing)) {
            return follow(action, userId, canonicalPayload, key, followersKey, channel);
        }

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key + ":lock",
                "1",
                Duration.ofSeconds(properties.getIdempotency().getProcessingTtlSeconds())
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return follow(action, userId, canonicalPayload, key, followersKey, channel);
        }

        long startedAt = System.currentTimeMillis();
        try {
            metricsService.increment(action, "leader");
            eventRecorder.record(action, userId, "leader", "processing", key, canonicalPayload, null, "leader started");
            writeState(key, Map.of("status", STATUS_PROCESSING, "startedAt", System.currentTimeMillis()),
                    properties.getIdempotency().getProcessingTtlSeconds());

            Map<String, Object> result = callLeader(leader);
            writeState(key, Map.of("status", STATUS_SUCCEEDED, "finishedAt", System.currentTimeMillis(), "result", result),
                    properties.getIdempotency().getSuccessTtlSeconds());
            eventRecorder.record(action, userId, "succeeded", "succeeded", key, canonicalPayload,
                    System.currentTimeMillis() - startedAt, "leader succeeded");
            publish(channel);
            return result;
        } catch (RuntimeException e) {
            if (e instanceof GuardNonTerminalException) {
                redisTemplate.delete(key);
                publish(channel);
                throw e;
            }
            metricsService.increment(action, "failed");
            writeState(key, Map.of(
                    "status", STATUS_FAILED,
                    "finishedAt", System.currentTimeMillis(),
                    "error", e.getMessage() == null ? "Request failed" : e.getMessage()
            ), properties.getIdempotency().getFailureTtlSeconds());
            eventRecorder.record(action, userId, "failed", "failed", key, canonicalPayload,
                    System.currentTimeMillis() - startedAt, e.getMessage());
            publish(channel);
            throw e;
        } finally {
            redisTemplate.delete(key + ":lock");
        }
    }

    public void rejectIfProcessing(String action, String userId, Map<String, Object> canonicalPayload) {
        if (!properties.getIdempotency().isEnabled()) return;
        metricsService.increment(action, "total");
        String key = buildKey(action, userId, canonicalPayload);
        if (isProcessing(readState(key))) {
            metricsService.increment(action, "processing_rejected");
            eventRecorder.record(action, userId, "processing_rejected", "rejected", key, canonicalPayload, null, PROCESSING_MESSAGE);
            throw new BusinessException(409, PROCESSING_MESSAGE);
        }
    }

    public void markProcessing(String action, String userId, Map<String, Object> canonicalPayload) {
        if (!properties.getIdempotency().isEnabled()) return;
        String key = buildKey(action, userId, canonicalPayload);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key + ":lock",
                "1",
                Duration.ofSeconds(properties.getIdempotency().getProcessingTtlSeconds())
        );
        if (!Boolean.TRUE.equals(acquired)) {
            metricsService.increment(action, "processing_rejected");
            eventRecorder.record(action, userId, "processing_rejected", "rejected", key, canonicalPayload, null, PROCESSING_MESSAGE);
            throw new BusinessException(409, PROCESSING_MESSAGE);
        }
        metricsService.increment(action, "leader");
        eventRecorder.record(action, userId, "leader", "processing", key, canonicalPayload, null, "leader started");
        writeState(key, Map.of("status", STATUS_PROCESSING, "startedAt", System.currentTimeMillis()),
                properties.getIdempotency().getProcessingTtlSeconds());
    }

    public void markSucceeded(String action, String userId, Map<String, Object> canonicalPayload, Object result) {
        if (!properties.getIdempotency().isEnabled()) return;
        String key = buildKey(action, userId, canonicalPayload);
        writeState(key, Map.of("status", STATUS_SUCCEEDED, "finishedAt", System.currentTimeMillis(), "result", result),
                properties.getIdempotency().getSuccessTtlSeconds());
        eventRecorder.record(action, userId, "succeeded", "succeeded", key, canonicalPayload, null, "leader succeeded");
        publish(key + ":channel");
        redisTemplate.delete(key + ":lock");
    }

    public void markFailed(String action, String userId, Map<String, Object> canonicalPayload, String error) {
        if (!properties.getIdempotency().isEnabled()) return;
        String key = buildKey(action, userId, canonicalPayload);
        metricsService.increment(action, "failed");
        writeState(key, Map.of(
                "status", STATUS_FAILED,
                "finishedAt", System.currentTimeMillis(),
                "error", error == null ? "Request failed" : error
        ), properties.getIdempotency().getFailureTtlSeconds());
        eventRecorder.record(action, userId, "failed", "failed", key, canonicalPayload, null, error);
        publish(key + ":channel");
        redisTemplate.delete(key + ":lock");
    }

    private Map<String, Object> follow(String action, String userId, Map<String, Object> canonicalPayload,
                                       String key, String followersKey, String channel) {
        Long followers = redisTemplate.opsForValue().increment(followersKey);
        redisTemplate.expire(followersKey, Duration.ofSeconds(properties.getIdempotency().getFollowerWaitSeconds() + 10L));
        if (followers != null && followers > properties.getIdempotency().getMaxFollowers()) {
            redisTemplate.opsForValue().decrement(followersKey);
            metricsService.increment(action, "follower_rejected");
            eventRecorder.record(action, userId, "follower_rejected", "rejected", key, canonicalPayload, null, PROCESSING_MESSAGE);
            throw new BusinessException(409, PROCESSING_MESSAGE);
        }

        metricsService.increment(action, "follower");
        long startedAt = System.currentTimeMillis();
        eventRecorder.record(action, userId, "follower", "processing", key, canonicalPayload, null, "follower waiting");

        try {
            Map<String, Object> current = readState(key);
            if (isSucceeded(current)) {
                return reuseSuccess(action, userId, canonicalPayload, key, current, startedAt);
            }
            if (isFailed(current)) {
                reuseFailure(action, userId, canonicalPayload, key, current, startedAt);
            }

            CountDownLatch latch = new CountDownLatch(1);
            MessageListener listener = (Message message, byte[] pattern) -> latch.countDown();
            ChannelTopic topic = new ChannelTopic(channel);
            try {
                listenerContainer.addMessageListener(listener, topic);
                current = readState(key);
                if (isSucceeded(current)) {
                    return reuseSuccess(action, userId, canonicalPayload, key, current, startedAt);
                }
                if (isFailed(current)) {
                    reuseFailure(action, userId, canonicalPayload, key, current, startedAt);
                }
                boolean notified = latch.await(properties.getIdempotency().getFollowerWaitSeconds(), TimeUnit.SECONDS);
                if (!notified) {
                    rejectStillProcessing(action, userId, canonicalPayload, key, startedAt);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException(409, PROCESSING_MESSAGE);
            } finally {
                listenerContainer.removeMessageListener(listener, topic);
            }

            Map<String, Object> done = readState(key);
            if (isSucceeded(done)) {
                return reuseSuccess(action, userId, canonicalPayload, key, done, startedAt);
            }
            if (isFailed(done)) {
                reuseFailure(action, userId, canonicalPayload, key, done, startedAt);
            }
            rejectStillProcessing(action, userId, canonicalPayload, key, startedAt);
            throw new BusinessException(409, PROCESSING_MESSAGE);
        } finally {
            redisTemplate.opsForValue().decrement(followersKey);
        }
    }

    private Map<String, Object> reuseSuccess(String action, String userId, Map<String, Object> canonicalPayload,
                                             String key, Map<String, Object> state, long startedAt) {
        metricsService.increment(action, "success_hit");
        eventRecorder.record(action, userId, "success_hit", "reused", key, canonicalPayload,
                System.currentTimeMillis() - startedAt, "follower reused success");
        return resultWithHit(state);
    }

    private void reuseFailure(String action, String userId, Map<String, Object> canonicalPayload,
                              String key, Map<String, Object> state, long startedAt) {
        metricsService.increment(action, "failed_hit");
        eventRecorder.record(action, userId, "failed_hit", "failed", key, canonicalPayload,
                System.currentTimeMillis() - startedAt, "follower reused failure");
        throw new BusinessException(502, String.valueOf(state.getOrDefault("error", "Request failed")));
    }

    private void rejectStillProcessing(String action, String userId, Map<String, Object> canonicalPayload,
                                       String key, long startedAt) {
        eventRecorder.record(action, userId, "processing_rejected", "rejected", key, canonicalPayload,
                System.currentTimeMillis() - startedAt, PROCESSING_MESSAGE);
        throw new BusinessException(409, PROCESSING_MESSAGE);
    }

    private Map<String, Object> resultWithHit(Map<String, Object> state) {
        Object result = state.get("result");
        Map<String, Object> map = objectMapper.convertValue(result, new TypeReference<>() {});
        map.put("idempotent_hit", true);
        return map;
    }

    private Map<String, Object> callLeader(Callable<Map<String, Object>> leader) {
        try {
            return leader.call();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, e.getMessage() == null ? "Request failed" : e.getMessage());
        }
    }

    private String buildKey(String action, String userId, Map<String, Object> canonicalPayload) {
        return "idem:v1:" + action + ":" + userId + ":" +
                Hashing.sha256Hex(canonicalJson(canonicalPayload).getBytes(StandardCharsets.UTF_8));
    }

    private String canonicalJson(Map<String, Object> payload) {
        try {
            JsonNode normalized = normalizeNode(objectMapper.valueToTree(payload));
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to normalize idempotency payload");
        }
    }

    private JsonNode normalizeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            var array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(normalizeNode(item)));
            return array;
        }
        ObjectNode object = objectMapper.createObjectNode();
        node.fieldNames().forEachRemaining(name -> object.set(name, normalizeNode(node.get(name))));
        return object;
    }

    private Map<String, Object> readState(String key) {
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse idempotency state {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void writeState(String key, Map<String, Object> state, int ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(state), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to write idempotency state");
        }
    }

    private void publish(String channel) {
        redisTemplate.convertAndSend(channel, "done");
    }

    private boolean isProcessing(Map<String, Object> state) {
        return state != null && STATUS_PROCESSING.equals(state.get("status"));
    }

    private boolean isSucceeded(Map<String, Object> state) {
        return state != null && STATUS_SUCCEEDED.equals(state.get("status"));
    }

    private boolean isFailed(Map<String, Object> state) {
        return state != null && STATUS_FAILED.equals(state.get("status"));
    }
}
