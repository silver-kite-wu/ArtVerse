package com.artverse.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final String PREFIX = "rt:";
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofHours(12);
    private static final long REFRESH_TOKEN_TIMEOUT_SECONDS = 12 * 3600;

    private final StringRedisTemplate redis;

    public RefreshTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String issue(long userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + userId + ":" + token, "1", REFRESH_TOKEN_TTL);
        return token;
    }

    public boolean validateAndConsume(long userId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = PREFIX + userId + ":" + token;
        Boolean deleted = redis.delete(key);
        return Boolean.TRUE.equals(deleted);
    }

    public void revokeAll(long userId) {
        var keys = redis.keys(PREFIX + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    public long getTimeoutSeconds() {
        return REFRESH_TOKEN_TIMEOUT_SECONDS;
    }
}
