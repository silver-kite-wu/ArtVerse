package com.artverse.mail;

import com.artverse.common.BusinessException;
import com.artverse.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationService")
class EmailVerificationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private MailSender mailSender;

    private MailProperties properties;
    private FakeEmailCodeStore store;
    private EmailVerificationService service;

    private static final String EMAIL = "alice@example.com";
    private static final String CODE = "123456";

    @BeforeEach
    void setUp() {
        properties = new MailProperties();
        properties.setEnabled(true);
        properties.setCodeLength(6);
        properties.setCodeTtlSeconds(900);
        properties.setResendCooldownSeconds(60);
        properties.setMaxVerifyAttempts(5);
        properties.setSubjectPrefix("ArtVerse");
        store = new FakeEmailCodeStore();
        service = new EmailVerificationService(properties, store, mailSender, userRepository);
    }

    @Test
    @DisplayName("generates a 6-digit code and sends it")
    void sendCodeGeneratesAndSends() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        service.sendCode(EMAIL, VerificationScenario.LOGIN);

        Optional<String> stored = store.findCode(EMAIL, VerificationScenario.LOGIN);
        assertThat(stored).isPresent();
        assertThat(stored.get()).matches("\\d{6}");

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq(EMAIL), subjectCaptor.capture(), textCaptor.capture());
        assertThat(subjectCaptor.getValue()).contains("登录验证码");
        assertThat(textCaptor.getValue()).contains(String.join(" ", stored.get().split("")));
    }

    @Test
    @DisplayName("consumes a valid code once and removes it")
    void consumeValidCodeOnce() {
        store.saveCode(EMAIL, VerificationScenario.LOGIN, CODE, Duration.ofSeconds(900));

        service.consume(EMAIL, CODE, VerificationScenario.LOGIN);

        assertThat(store.findCode(EMAIL, VerificationScenario.LOGIN)).isEmpty();
    }

    @Test
    @DisplayName("rejects a wrong code and increments the attempt counter")
    void consumeWrongCodeIncrementsAttempts() {
        store.saveCode(EMAIL, VerificationScenario.LOGIN, CODE, Duration.ofSeconds(900));

        assertThatThrownBy(() -> service.consume(EMAIL, "000000", VerificationScenario.LOGIN))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getStatus() == 400)
                .matches(ex -> Integer.valueOf(MailErrorCodes.EMAIL_CODE_INVALID).equals(((BusinessException) ex).getCode()));

        assertThat(store.currentAttempts(EMAIL, VerificationScenario.LOGIN)).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects consuming after the attempt limit is reached")
    void rejectsWhenMaxAttemptsReached() {
        store.saveCode(EMAIL, VerificationScenario.LOGIN, CODE, Duration.ofSeconds(900));
        for (int i = 0; i < properties.getMaxVerifyAttempts(); i++) {
            store.incrementAttempts(EMAIL, VerificationScenario.LOGIN, Duration.ofSeconds(900));
        }

        assertThatThrownBy(() -> service.consume(EMAIL, CODE, VerificationScenario.LOGIN))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getStatus() == 400)
                .matches(ex -> Integer.valueOf(MailErrorCodes.EMAIL_CODE_TOO_MANY_ATTEMPTS).equals(((BusinessException) ex).getCode()));
    }

    @Test
    @DisplayName("enforces the resend cooldown window")
    void enforcesCooldown() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);
        service.sendCode(EMAIL, VerificationScenario.LOGIN);

        assertThatThrownBy(() -> service.sendCode(EMAIL, VerificationScenario.LOGIN))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getStatus() == 429);
    }

    @Test
    @DisplayName("rejects registration for an already-registered email")
    void rejectsRegisterForExistingEmail() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.sendCode(EMAIL, VerificationScenario.REGISTER))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getStatus() == 409);
    }

    @Test
    @DisplayName("rejects login code send for an unregistered email with a clear 404")
    void rejectsUnknownEmailForLoginSend() {
        when(userRepository.existsByEmail("ghost@example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.sendCode("ghost@example.com", VerificationScenario.LOGIN))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getStatus() == 404)
                .hasMessageContaining("该邮箱未注册");

        verify(mailSender, never()).send(any(), any(), any());
        assertThat(store.findCode("ghost@example.com", VerificationScenario.LOGIN)).isEmpty();
    }

    @Test
    @DisplayName("silently skips reset-password send for an unregistered email to prevent enumeration")
    void skipsUnknownEmailForResetSend() {
        when(userRepository.existsByEmail("ghost@example.com")).thenReturn(false);

        service.sendCode("ghost@example.com", VerificationScenario.RESET_PASSWORD);

        verify(mailSender, never()).send(any(), any(), any());
        assertThat(store.findCode("ghost@example.com", VerificationScenario.RESET_PASSWORD)).isEmpty();
    }

    @Test
    @DisplayName("throws MAIL_DISABLED when the feature is not enabled")
    void throwsWhenDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.sendCode(EMAIL, VerificationScenario.LOGIN))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getStatus() == 503)
                .matches(ex -> Integer.valueOf(MailErrorCodes.MAIL_DISABLED).equals(((BusinessException) ex).getCode()));
    }

    private static final class FakeEmailCodeStore implements EmailCodeStore {

        private final Map<String, String> codes = new HashMap<>();
        private final Map<String, Long> attempts = new HashMap<>();
        private final Set<String> cooldowns = new HashSet<>();

        private static String key(String email, VerificationScenario scenario) {
            return scenario.code() + ":" + email;
        }

        @Override
        public boolean tryAcquireCooldown(String email, VerificationScenario scenario, Duration cooldown) {
            return cooldowns.add(key(email, scenario));
        }

        @Override
        public void releaseCooldown(String email, VerificationScenario scenario) {
            cooldowns.remove(key(email, scenario));
        }

        @Override
        public void saveCode(String email, VerificationScenario scenario, String code, Duration ttl) {
            codes.put(key(email, scenario), code);
        }

        @Override
        public Optional<String> findCode(String email, VerificationScenario scenario) {
            return Optional.ofNullable(codes.get(key(email, scenario)));
        }

        @Override
        public void deleteCode(String email, VerificationScenario scenario) {
            codes.remove(key(email, scenario));
        }

        @Override
        public long incrementAttempts(String email, VerificationScenario scenario, Duration ttl) {
            return attempts.merge(key(email, scenario), 1L, Long::sum);
        }

        @Override
        public long currentAttempts(String email, VerificationScenario scenario) {
            return attempts.getOrDefault(key(email, scenario), 0L);
        }

        @Override
        public void clearAttempts(String email, VerificationScenario scenario) {
            attempts.remove(key(email, scenario));
        }
    }
}