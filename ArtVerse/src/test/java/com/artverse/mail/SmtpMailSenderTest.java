package com.artverse.mail;

import com.artverse.common.BusinessException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SmtpMailSender 直连 SMTP 发信")
class SmtpMailSenderTest {

    private JavaMailSender javaMailSender;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> mailSenderProvider = mock(ObjectProvider.class);

    private MailProperties properties;
    private SmtpMailSender sender;

    @BeforeEach
    void setUp() {
        javaMailSender = mock(JavaMailSender.class);
        when(javaMailSender.createMimeMessage())
                .thenAnswer(inv -> new JavaMailSenderImpl().createMimeMessage());
        when(mailSenderProvider.getIfAvailable()).thenReturn(javaMailSender);

        properties = new MailProperties();
        properties.setEnabled(true);
        properties.setFrom("noreply@example.com");
        sender = new SmtpMailSender(mailSenderProvider, properties);
    }

    @Test
    void sendsHtmlMailWithFromToSubject() {
        sender.send("user@example.com", "主题", "<p>正文</p>");

        verify(javaMailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void rejectsWhenMailFeatureDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> sender.send("user@example.com", "主题", "<p>x</p>"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(MailErrorCodes.MAIL_DISABLED);
    }

    @Test
    void rejectsWhenSmtpNotConfigured() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> sender.send("user@example.com", "主题", "<p>x</p>"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(MailErrorCodes.MAIL_DISABLED);
    }

    @Test
    void wrapsSmtpFailureIntoEmailSendFailed() {
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("553"))
                .when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send("user@example.com", "主题", "<p>x</p>"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(MailErrorCodes.EMAIL_SEND_FAILED);
    }

    @Test
    void enabledFeatureDoesNotThrowBeforeSend() {
        assertThatCode(() -> {
            // enabled 且 SMTP 可用：不应在构建阶段抛异常
            new SmtpMailSender(mailSenderProvider, properties);
        }).doesNotThrowAnyException();
    }
}
