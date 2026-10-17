package com.artverse.security;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Service
public class AuthRiskService {

    private final ArtVerseProperties properties;
    private final SlidingWindowRateLimiter rateLimiter;

    public AuthRiskService(ArtVerseProperties properties, SlidingWindowRateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    /** 仅做 IP 维度硬限流：超过 login-ip-hard-limit 直接 429 */
    public void enforceLoginIpLimit(String clientIp) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        long ipCount = rateLimiter.increment(key("login-ip", clientIp), risk.getLoginIpWindowSeconds());
        if (ipCount > risk.getLoginIpHardLimit()) {
            throw BusinessException.withCode(429, "登录请求过于频繁，请稍后重试", AuthErrorCodes.AUTH_RATE_LIMITED);
        }
    }

    /** 记录一次密码登录失败：窗口期内累计，用于分级防爆破 */
    public void recordPasswordFailure(String username) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        rateLimiter.increment(key("password-failure", username), risk.getPasswordFailureWindowSeconds());
    }

    /**
     * 分级防爆破第一档：窗口内密码失败达到 challengeThreshold（且尚未锁定）时，
     * 后续密码登录必须附加图形验证码
     */
    public boolean requiresPasswordChallenge(String username) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        long failures = rateLimiter.count(key("password-failure", username), risk.getPasswordFailureWindowSeconds());
        return failures >= risk.getPasswordChallengeThreshold() && failures < risk.getPasswordFailureLimit();
    }

    /** 分级防爆破第二档：窗口内密码失败达到 passwordFailureLimit 后锁定密码登录 */
    public boolean isPasswordLoginLocked(String username) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        long failures = rateLimiter.count(key("password-failure", username), risk.getPasswordFailureWindowSeconds());
        return failures >= risk.getPasswordFailureLimit();
    }

    /** 登录成功后清空密码失败计数 */
    public void clearPasswordFailures(String username) {
        rateLimiter.clear(key("password-failure", username));
    }

    public void enforceRegistrationLimit(String clientIp) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        long ipCount = rateLimiter.increment(key("register-ip", clientIp), risk.getRegisterIpWindowSeconds());
        if (ipCount > risk.getRegisterIpLimit()) {
            throw BusinessException.withCode(429, "请求过于频繁，请稍后再试", AuthErrorCodes.AUTH_RATE_LIMITED);
        }
    }

    private String key(String category, String value) {
        return "auth:risk:" + category + ":" + hmac(category + ":" + normalize(value));
    }

    private String hmac(String raw) {
        String configuredKey = properties.getAuth().getRisk().getHmacKey();
        String key = configuredKey == null || configuredKey.isBlank()
                ? properties.getSecrets().getEncryptionKey()
                : configuredKey.trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash auth risk key", ex);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
