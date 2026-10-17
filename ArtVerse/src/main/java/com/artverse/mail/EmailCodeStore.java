package com.artverse.mail;

import java.time.Duration;
import java.util.Optional;

public interface EmailCodeStore {

    boolean tryAcquireCooldown(String email, VerificationScenario scenario, Duration cooldown);

    void releaseCooldown(String email, VerificationScenario scenario);

    void saveCode(String email, VerificationScenario scenario, String code, Duration ttl);

    Optional<String> findCode(String email, VerificationScenario scenario);

    void deleteCode(String email, VerificationScenario scenario);

    long incrementAttempts(String email, VerificationScenario scenario, Duration ttl);

    long currentAttempts(String email, VerificationScenario scenario);

    void clearAttempts(String email, VerificationScenario scenario);
}