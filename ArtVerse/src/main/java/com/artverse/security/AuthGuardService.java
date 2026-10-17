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

    /**
     * 登录入口（密码登录与邮箱验证码登录共用）：
     * 不再要求图形验证码，仅保留 IP 维度硬限流；
     * 密码错误锁定由 PasswordLoginGuard 语义（AuthRiskService）在 Controller 层执行。
     */
    public void enforceLoginRisk(String username, HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        authRiskService.enforceLoginIpLimit(clientIp);
    }

    public void recordPasswordFailure(String username) {
        authRiskService.recordPasswordFailure(normalize(username));
    }

    /** 密码失败达到挑战阈值（未锁定）时，要求附加图形验证码 */
    public boolean requiresPasswordChallenge(String username) {
        return authRiskService.requiresPasswordChallenge(normalize(username));
    }

    /** 密码登录被要求挑战时强制校验图形验证码（与注册同一套人机验证） */
    public void enforcePasswordChallenge(String challengeToken, HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        if (!isChallengeEnabled()) {
            return;
        }
        if (isBlank(challengeToken)) {
            if (isObserveMode()) {
                log.info("Password login challenge skipped in observe mode");
                return;
            }
            throw challengeRequired();
        }
        HumanVerificationService.VerificationResult verification = humanVerificationService.verify("login", challengeToken, clientIp);
        if (verification.status() == HumanVerificationService.Status.SUCCESS || isObserveMode()) {
            return;
        }
        if (verification.status() == HumanVerificationService.Status.UNAVAILABLE) {
            throw BusinessException.withCode(503, "人机验证服务暂时不可用，请稍后重试", AuthErrorCodes.CHALLENGE_UNAVAILABLE);
        }
        throw BusinessException.withCode(403, "人机验证失败，请重试", AuthErrorCodes.CHALLENGE_FAILED);
    }

    public boolean isPasswordLoginLocked(String username) {
        return authRiskService.isPasswordLoginLocked(normalize(username));
    }

    public void clearPasswordFailures(String username) {
        authRiskService.clearPasswordFailures(normalize(username));
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
