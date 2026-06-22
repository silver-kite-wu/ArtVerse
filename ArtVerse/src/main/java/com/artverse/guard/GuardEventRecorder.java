package com.artverse.guard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardEventRecorder {

    static final String EVENT_LIST_KEY = "idem:events";
    private static final int MAX_EVENTS = 200;
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration REDIS_EVENTS_TTL = Duration.ofDays(3);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GuardEventPersistenceService persistenceService;

    public void record(String action, String scope, String decision, String result, String key,
                       Map<String, Object> canonicalPayload, Long durationMs, String message) {
        Map<String, Object> event = buildEvent(action, scope, decision, result, key, canonicalPayload, durationMs, message);
        try {
            String eventKey = todayEventKey();
            redisTemplate.opsForList().leftPush(eventKey, objectMapper.writeValueAsString(event));
            redisTemplate.opsForList().trim(eventKey, 0, MAX_EVENTS - 1);
            redisTemplate.expire(eventKey, REDIS_EVENTS_TTL);
        } catch (Exception e) {
            log.debug("Failed to record guard event to Redis: {}", e.getMessage());
        }
        try {
            persistenceService.insert(event);
        } catch (Exception e) {
            log.warn("Failed to persist guard event: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildEvent(String action, String scope, String decision, String result, String key,
                                           Map<String, Object> canonicalPayload, Long durationMs, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("time", OffsetDateTime.now(BEIJING_ZONE).toString());
        event.put("action", action);
        event.put("scope", scope);
        event.put("decision", decision);
        event.put("result", result);
        event.put("key_hash", keyHash(key));
        event.put("duration_ms", durationMs);
        event.put("summary", summarizePayload(canonicalPayload));
        event.put("message", truncate(message, 160));
        return event;
    }

    private Map<String, Object> summarizePayload(Map<String, Object> payload) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (payload == null) return summary;
        List<String> fields = List.of("chapterId", "storyId", "imageCount", "imageNumber", "workflowId", "prompt", "material", "refImages");
        for (String field : fields) {
            if (!payload.containsKey(field)) continue;
            Object value = payload.get(field);
            if (value instanceof String text) {
                summary.put(field, truncate(text, field.equals("material") ? 120 : 80));
            } else if (value instanceof List<?> list) {
                summary.put(field, Map.of("count", list.size()));
            } else {
                summary.put(field, value);
            }
        }
        return summary;
    }

    private String keyHash(String key) {
        if (key == null || key.isBlank()) return "";
        int idx = key.lastIndexOf(':');
        String hash = idx >= 0 ? key.substring(idx + 1) : key;
        return hash.length() <= 16 ? hash : hash.substring(0, 16);
    }

    private String truncate(String value, int maxChars) {
        if (value == null) return "";
        String normalized = value.trim().replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\t ]+", " ");
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars) + "...";
    }

    private String todayEventKey() {
        return EVENT_LIST_KEY + ":" + LocalDate.now(BEIJING_ZONE);
    }
}
