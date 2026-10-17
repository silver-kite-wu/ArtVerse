package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.mail.MailErrorCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 找回密码第二步的一次性重置凭证（ticket）。
 *
 * 流程：验证码校验通过 → issue(email) 签发票据 → 前端持票据调用密码重置。
 * 票据与邮箱绑定、单次消费（GETDEL 原子删除）、10 分钟过期，避免明文重传验证码。
 */
@Service
@RequiredArgsConstructor
public class PasswordResetTicketService {

    private static final String KEY_PREFIX = "artverse:password-reset:ticket:";
    private static final Duration TICKET_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public String issue(String email) {
        String ticket = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(KEY_PREFIX + ticket, email, TICKET_TTL);
        return ticket;
    }

    /** 单次消费：取到邮箱的同时删除票据，重复使用或过期均抛 40004 */
    public String consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw invalidTicket();
        }
        String email = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + ticket);
        if (email == null) {
            throw invalidTicket();
        }
        return email;
    }

    private BusinessException invalidTicket() {
        return BusinessException.withCode(
                400, "重置凭证无效或已过期，请重新完成验证码校验", MailErrorCodes.RESET_TICKET_INVALID);
    }
}
