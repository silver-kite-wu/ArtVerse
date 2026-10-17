package com.artverse.mail;

import com.artverse.common.BusinessException;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final MailProperties properties;
    private final EmailCodeStore codeStore;
    private final MailSender mailSender;
    private final UserRepository userRepository;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public void sendCode(String email, VerificationScenario scenario) {
        ensureEnabled();
        String normalized = normalizeEmail(email);
        validateEmail(normalized);

        if (scenario == VerificationScenario.REGISTER) {
            if (userRepository.existsByEmail(normalized)) {
                throw new BusinessException(409, "该邮箱已被注册");
            }
        } else if (!userRepository.existsByEmail(normalized)) {
            if (scenario == VerificationScenario.LOGIN) {
                // 邮箱验证码登录：未注册邮箱直接提示，避免用户等待一封永远收不到的邮件
                throw BusinessException.withCode(404, "该邮箱未注册，请先注册", MailErrorCodes.EMAIL_NOT_REGISTERED);
            }
            log.info("邮箱未注册，跳过发送验证码（防枚举）: scenario={}, email={}", scenario, normalized);
            return;
        }

        Duration cooldown = Duration.ofSeconds(properties.getResendCooldownSeconds());
        if (!codeStore.tryAcquireCooldown(normalized, scenario, cooldown)) {
            throw BusinessException.withCode(429, "验证码发送过于频繁，请 " + cooldown.toMinutes() + " 分钟后再试",
                    MailErrorCodes.EMAIL_CODE_TOO_MANY_ATTEMPTS);
        }

        String code = generateCode();
        String subject = properties.getSubjectPrefix() + " " + scenario.subjectSuffix();
        String html = buildHtmlBody(scenario, code, properties.getCodeTtlSeconds());

        try {
            mailSender.send(normalized, subject, html);
        } catch (BusinessException e) {
            codeStore.releaseCooldown(normalized, scenario);
            throw e;
        }

        codeStore.saveCode(normalized, scenario, code, Duration.ofSeconds(properties.getCodeTtlSeconds()));
        codeStore.clearAttempts(normalized, scenario);
    }

    public void consume(String email, String code, VerificationScenario scenario) {
        ensureEnabled();
        String normalized = normalizeEmail(email);
        if (code == null || code.isBlank()) {
            throw BusinessException.withCode(400, "验证码不能为空", MailErrorCodes.EMAIL_CODE_INVALID);
        }
        if (codeStore.currentAttempts(normalized, scenario) >= properties.getMaxVerifyAttempts()) {
            throw BusinessException.withCode(400, "验证码尝试次数过多，请重新获取", MailErrorCodes.EMAIL_CODE_TOO_MANY_ATTEMPTS);
        }
        Optional<String> stored = codeStore.findCode(normalized, scenario);
        if (stored.isEmpty()) {
            throw BusinessException.withCode(400, "验证码已过期，请重新获取", MailErrorCodes.EMAIL_CODE_EXPIRED);
        }
        if (!constantTimeEquals(stored.get(), code)) {
            codeStore.incrementAttempts(normalized, scenario, Duration.ofSeconds(properties.getCodeTtlSeconds()));
            throw BusinessException.withCode(400, "验证码错误", MailErrorCodes.EMAIL_CODE_INVALID);
        }
        codeStore.deleteCode(normalized, scenario);
        codeStore.clearAttempts(normalized, scenario);
        codeStore.releaseCooldown(normalized, scenario);
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw BusinessException.withCode(503, "邮箱服务未启用", MailErrorCodes.MAIL_DISABLED);
        }
    }

    private String buildHtmlBody(VerificationScenario scenario, String code, int ttlSeconds) {
        String spaced = String.join(" ", code.split(""));
        int minutes = Math.max(1, ttlSeconds / 60);
        String brand = properties.getSubjectPrefix();
        return """
                <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif;background:#f5f6f8;padding:32px 16px;">
                  <div style="max-width:420px;margin:0 auto;background:#ffffff;border-radius:12px;padding:32px 28px;">
                    <div style="text-align:center;color:#4A90E2;font-size:18px;font-weight:600;margin-bottom:20px;">%s</div>
                    <div style="font-size:15px;color:#333333;line-height:1.7;">您好，<br>您正在进行 <strong>%s</strong> 操作，验证码为：</div>
                    <div style="text-align:center;font-size:34px;letter-spacing:12px;color:#4A90E2;font-weight:500;margin:26px 0;">%s</div>
                    <div style="font-size:13px;color:#999999;text-align:center;">验证码 %d 分钟内有效，请勿泄露给他人。</div>
                  </div>
                  <div style="text-align:center;font-size:12px;color:#bbbbbb;margin-top:24px;">此邮件由系统自动发送，请勿回复。</div>
                </div>
                """.formatted(brand, scenario.actionLabel(), spaced, minutes);
    }

    private String generateCode() {
        int length = properties.getCodeLength();
        int max = (int) Math.pow(10, length) - 1;
        int value = ThreadLocalRandom.current().nextInt(0, max + 1);
        return String.format("%0" + length + "d", value);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private void validateEmail(String email) {
        if (email.isBlank()) {
            throw new BusinessException(400, "邮箱不能为空");
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException(400, "邮箱格式不正确");
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}