package com.artverse.guard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardMetricsService {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration REDIS_STATS_TTL = Duration.ofDays(3);

    private final StringRedisTemplate redisTemplate;
    private final GuardMetricBucketService bucketService;

    public void increment(String action, String field) {
        try {
            String key = todayStatsKey(action);
            redisTemplate.opsForHash().increment(key, field, 1);
            redisTemplate.expire(key, REDIS_STATS_TTL);
        } catch (Exception e) {
            log.debug("Failed to write guard metric to Redis: {}", e.getMessage());
        }
        try {
            bucketService.increment(action, field);
        } catch (Exception e) {
            log.warn("Failed to persist guard metric action={} field={}: {}", action, field, e.getMessage());
        }
    }

    public Map<Object, Object> readStats(String action) {
        return redisTemplate.opsForHash().entries(todayStatsKey(action));
    }

    private String todayStatsKey(String action) {
        return "idem:stats:" + LocalDate.now(BEIJING_ZONE) + ":" + action;
    }
}
