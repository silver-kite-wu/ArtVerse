package com.artverse.guard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardEventPersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void insert(Map<String, Object> event) {
        try {
            String sql = """
                    INSERT INTO guard_events (
                      id, event_time, action, scope, decision, result, key_hash,
                      duration_ms, summary_json, message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """;
            jdbcTemplate.update(sql,
                    UUID.fromString(String.valueOf(event.get("id"))),
                    Timestamp.from(OffsetDateTime.parse(String.valueOf(event.get("time"))).toInstant()),
                    stringValue(event, "action"),
                    stringValue(event, "scope"),
                    stringValue(event, "decision"),
                    stringValue(event, "result"),
                    stringValue(event, "key_hash"),
                    longValue(event.get("duration_ms")),
                    objectMapper.writeValueAsString(event.getOrDefault("summary", Map.of())),
                    stringValue(event, "message"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Map<String, Object>> recentEvents(int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        String sql = """
                SELECT id, event_time, action, scope, decision, result, key_hash,
                       duration_ms, summary_json::text AS summary_json, message
                FROM guard_events
                ORDER BY event_time DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, this::mapEvent, safeLimit);
    }

    private Map<String, Object> mapEvent(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", rs.getObject("id", UUID.class).toString());
        event.put("time", rs.getObject("event_time", OffsetDateTime.class).toString());
        event.put("action", rs.getString("action"));
        event.put("scope", rs.getString("scope"));
        event.put("decision", rs.getString("decision"));
        event.put("result", rs.getString("result"));
        event.put("key_hash", rs.getString("key_hash"));
        Object duration = rs.getObject("duration_ms");
        event.put("duration_ms", duration == null ? null : rs.getLong("duration_ms"));
        event.put("summary", parseSummary(rs.getString("summary_json")));
        event.put("message", rs.getString("message"));
        return event;
    }

    private Map<String, Object> parseSummary(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Failed to parse persisted guard event summary: {}", e.getMessage());
            return Map.of();
        }
    }

    private String stringValue(Map<String, Object> event, String field) {
        Object value = event.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
