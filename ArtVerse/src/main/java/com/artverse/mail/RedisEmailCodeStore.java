package com.artverse.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisEmailCodeStore implements EmailCodeStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryAcquireCooldown(String email, VerificationScenario scenario, Duration cooldown) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(sentKey(email, scenario), "1", cooldown);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void releaseCooldown(String email, VerificationScenario scenario) {
        redisTemplate.delete(sentKey(email, scenario));
    }

    @Override
    public void saveCode(String email, VerificationScenario scenario, String code, Duration ttl) {
        redisTemplate.opsForValue().set(codeKey(email, scenario), code, ttl);
    }

    @Override
    public Optional<String> findCode(String email, VerificationScenario scenario) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(codeKey(email, scenario)));
    }

    @Override
    public void deleteCode(String email, VerificationScenario scenario) {
        redisTemplate.delete(codeKey(email, scenario));
    }

    @Override
    public long incrementAttempts(String email, VerificationScenario scenario, Duration ttl) {
        Long value = redisTemplate.opsForValue().increment(attemptKey(email, scenario));
        if (value != null && value == 1L) {
            redisTemplate.expire(attemptKey(email, scenario), ttl);
        }
        return value == null ? 0L : value;
    }

    @Override
    public long currentAttempts(String email, VerificationScenario scenario) {
        String raw = redisTemplate.opsForValue().get(attemptKey(email, scenario));
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public void clearAttempts(String email, VerificationScenario scenario) {
        redisTemplate.delete(attemptKey(email, scenario));
    }

    private String codeKey(String email, VerificationScenario scenario) {
        return "mail:code:" + scenario.code() + ":" + email;
    }

    private String sentKey(String email, VerificationScenario scenario) {
        return "mail:sent:" + scenario.code() + ":" + email;
    }

    private String attemptKey(String email, VerificationScenario scenario) {
        return "mail:attempt:" + scenario.code() + ":" + email;
    }
}