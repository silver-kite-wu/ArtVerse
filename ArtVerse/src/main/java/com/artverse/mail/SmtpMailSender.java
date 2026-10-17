package com.artverse.mail;

import com.artverse.common.BusinessException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 直连 SMTP 发送 HTML 邮件（原独立 mail-service 已并入主后端，省去第二个 JVM）。
 *
 * JavaMailSender 由 spring.mail.* 自动配置提供：未配置 SMTP 时 getIfAvailable()
 * 返回 null，此时发信抛 MAIL_DISABLED，不影响应用启动与其他功能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpMailSender implements MailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MailProperties properties;

    @Override
    public void send(String to, String subject, String html) {
        if (!properties.isEnabled()) {
            throw BusinessException.withCode(503, "邮箱服务未启用", MailErrorCodes.MAIL_DISABLED);
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw BusinessException.withCode(503, "SMTP 未配置，无法发送邮件", MailErrorCodes.MAIL_DISABLED);
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (MessagingException | MailException e) {
            log.warn("SMTP 发信失败: to={}, subject={}, error={}", to, subject, e.getMessage());
            throw BusinessException.withCode(502, "邮件发送失败，请稍后重试", MailErrorCodes.EMAIL_SEND_FAILED);
        }
    }
}
