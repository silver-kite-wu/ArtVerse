package com.artverse.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GuardMetricBucketService {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void increment(String action, String field) {
        MetricDelta delta = MetricDelta.forField(field);
        if (delta == null) return;
        OffsetDateTime now = OffsetDateTime.now(BEIJING_ZONE);
        upsert("MINUTE", now.truncatedTo(ChronoUnit.MINUTES), action, delta);
        upsert("HOUR", now.truncatedTo(ChronoUnit.HOURS), action, delta);
        upsert("DAY", now.truncatedTo(ChronoUnit.DAYS), action, delta);
    }

    public List<Map<String, Object>> query(String bucketType, int range) {
        String safeBucketType = switch (String.valueOf(bucketType).toUpperCase()) {
            case "MINUTE", "HOUR", "DAY" -> String.valueOf(bucketType).toUpperCase();
            default -> "HOUR";
        };
        int safeRange = Math.max(1, Math.min(range, 1000));
        OffsetDateTime cutoff = switch (safeBucketType) {
            case "MINUTE" -> OffsetDateTime.now(BEIJING_ZONE).minusMinutes(safeRange);
            case "DAY" -> OffsetDateTime.now(BEIJING_ZONE).minusDays(safeRange);
            default -> OffsetDateTime.now(BEIJING_ZONE).minusHours(safeRange);
        };
        String sql = """
                SELECT bucket_type, bucket_start, action, total, leader_count, follower_count,
                       success_hit_count, failed_hit_count, follower_rejected_count,
                       processing_rejected_count, failed_count
                FROM guard_metric_buckets
                WHERE bucket_type = ? AND bucket_start >= ?
                ORDER BY bucket_start DESC, action ASC
                """;
        return jdbcTemplate.query(sql, this::mapBucket, safeBucketType, Timestamp.from(cutoff.toInstant()));
    }

    private void upsert(String bucketType, OffsetDateTime bucketStart, String action, MetricDelta delta) {
        String sql = """
                INSERT INTO guard_metric_buckets (
                  bucket_type, bucket_start, action, total, leader_count, follower_count,
                  success_hit_count, failed_hit_count, follower_rejected_count,
                  processing_rejected_count, failed_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (bucket_type, bucket_start, action) DO UPDATE SET
                  total = guard_metric_buckets.total + EXCLUDED.total,
                  leader_count = guard_metric_buckets.leader_count + EXCLUDED.leader_count,
                  follower_count = guard_metric_buckets.follower_count + EXCLUDED.follower_count,
                  success_hit_count = guard_metric_buckets.success_hit_count + EXCLUDED.success_hit_count,
                  failed_hit_count = guard_metric_buckets.failed_hit_count + EXCLUDED.failed_hit_count,
                  follower_rejected_count = guard_metric_buckets.follower_rejected_count + EXCLUDED.follower_rejected_count,
                  processing_rejected_count = guard_metric_buckets.processing_rejected_count + EXCLUDED.processing_rejected_count,
                  failed_count = guard_metric_buckets.failed_count + EXCLUDED.failed_count,
                  updated_at = now()
                """;
        jdbcTemplate.update(sql,
                bucketType,
                Timestamp.from(bucketStart.toInstant()),
                action,
                delta.total,
                delta.leader,
                delta.follower,
                delta.successHit,
                delta.failedHit,
                delta.followerRejected,
                delta.processingRejected,
                delta.failed);
    }

    private Map<String, Object> mapBucket(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("bucket_type", rs.getString("bucket_type"));
        item.put("bucket_start", rs.getObject("bucket_start", OffsetDateTime.class).toString());
        item.put("action", rs.getString("action"));
        item.put("total", rs.getLong("total"));
        item.put("leader", rs.getLong("leader_count"));
        item.put("follower", rs.getLong("follower_count"));
        item.put("success_hit", rs.getLong("success_hit_count"));
        item.put("failed_hit", rs.getLong("failed_hit_count"));
        item.put("follower_rejected", rs.getLong("follower_rejected_count"));
        item.put("processing_rejected", rs.getLong("processing_rejected_count"));
        item.put("failed", rs.getLong("failed_count"));
        return item;
    }

    private record MetricDelta(
            long total,
            long leader,
            long follower,
            long successHit,
            long failedHit,
            long followerRejected,
            long processingRejected,
            long failed
    ) {
        private static MetricDelta forField(String field) {
            return switch (field) {
                case "total" -> new MetricDelta(1, 0, 0, 0, 0, 0, 0, 0);
                case "leader" -> new MetricDelta(0, 1, 0, 0, 0, 0, 0, 0);
                case "follower" -> new MetricDelta(0, 0, 1, 0, 0, 0, 0, 0);
                case "success_hit" -> new MetricDelta(0, 0, 0, 1, 0, 0, 0, 0);
                case "failed_hit" -> new MetricDelta(0, 0, 0, 0, 1, 0, 0, 0);
                case "follower_rejected" -> new MetricDelta(0, 0, 0, 0, 0, 1, 0, 0);
                case "processing_rejected" -> new MetricDelta(0, 0, 0, 0, 0, 0, 1, 0);
                case "failed" -> new MetricDelta(0, 0, 0, 0, 0, 0, 0, 1);
                default -> null;
            };
        }
    }
}
