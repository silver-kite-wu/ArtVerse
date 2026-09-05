package com.artverse.security;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AuthGuardService {

    private final ArtVerseProperties properties;
    private final AuthRiskService authRiskService;
    private final ClientIpResolver clientIpResolver;
    private final HumanVerificationService humanVerificationService;

    public AuthGuardService(
            ArtVerseProperties properties,
            AuthRiskService authRiskService,
            ClientIpResolver clientIpResolver,
            HumanVerificationService humanVerificationService
    ) {
        this.properties = properties;
        this.authRiskService = authRiskService;
        this.clientIpResolver = clientIpResolver;
        this.humanVerificationService = humanVerificationService;
    }

    public void enforceRegistrationChallenge(String challengeToken, HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        authRiskService.enforceRegistrationLimit(clientIp);
        if (!isChallengeEnabled()) {
            return;
        }
        if (isBlank(challengeToken)) {
            if (isObserveMode()) {
                log.info("Registration challenge skipped in observe mode");
                return;
            }
            throw challengeRequired();
        }
        HumanVerificationService.VerificationResult verification = humanVerificationService.verify("register", challengeToken, clientIp);
        if (verification.status() == HumanVerificationService.Status.SUCCESS || isObserveMode()) {
            if (verification.status() != HumanVerificationService.Status.SUCCESS) {
                log.info("Registration challenge observe result: {}", verification.errorCodes());
            }
            return;
        }
        if (verification.status() == HumanVerificationService.Status.UNAVAILABLE) {
            throw BusinessException.withCode(503, "人机验证服务暂时不可用，请稍后重试", AuthErrorCodes.CHALLENGE_UNAVAILABLE);
        }
        throw BusinessException.withCode(403, "人机验证失败，请重试", AuthErrorCodes.CHALLENGE_FAILED);
    }

    public void enforceLoginRisk(String username, String challengeToken, HttpServletRequest request) {
        String normalizedUsername = normalize(username);
        String clientIp = clientIpResolver.resolve(request);
        AuthRiskService.LoginRiskDecision decision = authRiskService.recordLoginAttempt(normalizedUsername, clientIp);
        if (decision.hardLimited()) {
            throw BusinessException.withCode(429, "登录请求过于频繁，请稍后重试", AuthErrorCodes.AUTH_RATE_LIMITED);
        }
        boolean alwaysMode = properties.getAuth().getChallenge().getLoginChallengeMode()
                == ArtVerseProperties.LoginChallengeMode.ALWAYS;
        boolean needChallenge = (alwaysMode || decision.challengeRequired()) && isChallengeEnabled();
        if (!needChallenge) {
            return;
        }
        if (isBlank(challengeToken)) {
            if (isObserveMode()) {
                log.info("Login challenge skipped in observe mode for attempt threshold");
                return;
            }
            throw challengeRequired();
        }

        HumanVerificationService.VerificationResult verification = humanVerificationService.verify("login", challengeToken, clientIp);
        if (verification.status() == HumanVerificationService.Status.SUCCESS) {
            return;
        }
        if (isObserveMode()) {
            log.info("Login challenge observe result: {}", verification.errorCodes());
            return;
        }
        if (verification.status() == HumanVerificationService.Status.UNAVAILABLE) {
            if (authRiskService.allowDegradedLogin(normalizedUsername, clientIp)) {
                log.warn("Login challenge degraded due to upstream availability for clientIp={}", clientIp);
                return;
            }
            throw BusinessException.withCode(503, "人机验证服务暂时不可用，请稍后重试", AuthErrorCodes.CHALLENGE_UNAVAILABLE);
        }
        throw BusinessException.withCode(403, "请先完成人机验证", AuthErrorCodes.CHALLENGE_FAILED);
    }

    public void recordLoginFailure(String username) {
        authRiskService.recordLoginFailure(normalize(username));
    }

    public void clearLoginFailures(String username) {
        authRiskService.clearLoginFailures(normalize(username));
    }

    public boolean isChallengeEnabled() {
        return properties.getAuth().getChallenge().getMode() != ArtVerseProperties.ChallengeMode.DISABLED
                && humanVerificationService.isEnabled();
    }

    public boolean requiresRegistrationChallenge() {
        return isChallengeEnabled();
    }

    public String provider() {
        return humanVerificationService.provider();
    }

    public String siteKey() {
        return humanVerificationService.siteKey();
    }

    public String loginMode() {
        if (properties.getAuth().getChallenge().getMode() == ArtVerseProperties.ChallengeMode.DISABLED) {
            return "disabled";
        }
        return properties.getAuth().getChallenge().getLoginChallengeMode().name().toLowerCase();
    }

    private boolean isObserveMode() {
        return properties.getAuth().getChallenge().getMode() == ArtVerseProperties.ChallengeMode.OBSERVE;
    }

    private BusinessException challengeRequired() {
        return BusinessException.withCode(403, "请先完成人机验证", AuthErrorCodes.CHALLENGE_REQUIRED);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
