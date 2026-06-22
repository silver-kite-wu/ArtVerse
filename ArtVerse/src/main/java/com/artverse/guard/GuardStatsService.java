package com.artverse.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GuardStatsService {

    private static final List<String> ACTIONS = List.of(
            "image-gen",
            "generate-scenes",
            "generate-manga",
            "regenerate-image"
    );

    private final GuardMetricsService metricsService;
    private final GuardMetricBucketService bucketService;

    public Map<String, Object> stats() {
        List<Map<String, Object>> actions = ACTIONS.stream()
                .map(this::actionStats)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updated_at", OffsetDateTime.now().toString());
        response.put("actions", actions);
        return response;
    }

    private Map<String, Object> actionStats(String action) {
        Map<Object, Object> raw = metricsService.readStats(action);
        long total = value(raw, "total");
        long leader = value(raw, "leader");
        long follower = value(raw, "follower");
        long successHit = value(raw, "success_hit");
        long failedHit = value(raw, "failed_hit");
        long followerRejected = value(raw, "follower_rejected");
        long processingRejected = value(raw, "processing_rejected");
        long failed = value(raw, "failed");

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("action", action);
        stats.put("total", total);
        stats.put("leader", leader);
        stats.put("follower", follower);
        stats.put("success_hit", successHit);
        stats.put("failed_hit", failedHit);
        stats.put("follower_rejected", followerRejected);
        stats.put("processing_rejected", processingRejected);
        stats.put("failed", failed);
        stats.put("hit_rate", rate(successHit, total));
        stats.put("reuse_rate", rate(successHit + failedHit, total));
        stats.put("single_flight_rate", rate(follower, total));
        stats.put("reject_rate", rate(followerRejected + processingRejected, total));
        return stats;
    }

    private long value(Map<Object, Object> raw, String field) {
        Object value = raw.get(field);
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private double rate(long numerator, long denominator) {
        if (denominator <= 0) return 0D;
        return (double) numerator / denominator;
    }

    public Map<String, Object> metricBuckets(String bucketType, int range) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updated_at", OffsetDateTime.now().toString());
        response.put("bucket_type", bucketType);
        response.put("items", bucketService.query(bucketType, range));
        return response;
    }
}
