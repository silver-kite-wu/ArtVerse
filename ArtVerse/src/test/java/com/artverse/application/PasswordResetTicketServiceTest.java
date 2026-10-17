package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.mail.MailErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PasswordResetTicketService 一次性重置票据")
class PasswordResetTicketServiceTest {

    private StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private PasswordResetTicketService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new PasswordResetTicketService(redisTemplate);
    }

    @Test
    void issuesTicketBindingEmailWithTtl() {
        String ticket = service.issue("user@example.com");

        assertThat(ticket).isNotBlank();
        verify(valueOperations).set(
                startsWith("artverse:password-reset:ticket:"),
                eq("user@example.com"),
                any(Duration.class));
    }

    @Test
    void consumeReturnsBoundEmailViaAtomicGetAndDelete() {
        String ticket = service.issue("user@example.com");
        when(valueOperations.getAndDelete("artverse:password-reset:ticket:" + ticket))
                .thenReturn("user@example.com");

        assertThat(service.consume(ticket)).isEqualTo("user@example.com");
    }

    @Test
    void consumeRejectsUnknownOrExpiredTicketWithCode40004() {
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.consume("missing-ticket"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(MailErrorCodes.RESET_TICKET_INVALID);
    }

    @Test
    void consumeRejectsBlankTicketWithoutTouchingRedis() {
        assertThatThrownBy(() -> service.consume("  "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(MailErrorCodes.RESET_TICKET_INVALID);
    }
}
