package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Role;
import com.artverse.domain.User;
import com.artverse.mail.EmailVerificationService;
import com.artverse.mail.VerificationScenario;
import com.artverse.persistence.UserRepository;
import com.artverse.security.PasswordHashService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 16;
    private static final int MIN_USERNAME_LENGTH = 2;
    private static final int MAX_USERNAME_LENGTH = 50;

    private final UserRepository userRepository;
    private final PasswordHashService passwordHashService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetTicketService passwordResetTicketService;

    @Transactional
    public User register(String username, String email, String password, String emailCode) {
        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        validateUsername(normalizedUsername);
        validateEmail(normalizedEmail);
        validatePassword(password);

        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new BusinessException(409, "用户名已被占用，请换一个用户名");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(409, "该邮箱已被注册，请直接登录或找回密码");
        }

        if (emailVerificationService.isEnabled()) {
            emailVerificationService.consume(normalizedEmail, emailCode, VerificationScenario.REGISTER);
        }

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordHashService.encode(password));
        user.setRole(Role.USER);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateIdentity();
        }
    }

    @Transactional
    public User login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        validateEmail(normalizedEmail);
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            passwordHashService.consumeDummyCheck(password);
            throw invalidCredentials();
        }
        if (!passwordHashService.matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (passwordHashService.requiresUpgrade(user.getPasswordHash())) {
            user.setPasswordHash(passwordHashService.encode(password));
            userRepository.save(user);
        }
        return user;
    }

    @Transactional
    public User loginByCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        validateEmail(normalizedEmail);
        emailVerificationService.consume(normalizedEmail, code, VerificationScenario.LOGIN);
        return userRepository.findByEmail(normalizedEmail)
                .orElseThrow(this::invalidCredentials);
    }

    /** 找回密码第一步：校验验证码，通过后签发一次性重置票据 */
    public String verifyPasswordResetCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        validateEmail(normalizedEmail);
        emailVerificationService.consume(normalizedEmail, code, VerificationScenario.RESET_PASSWORD);
        return passwordResetTicketService.issue(normalizedEmail);
    }

    /** 找回密码第二步：凭一次性票据重置密码，票据即身份凭证，无需再传邮箱 */
    @Transactional
    public void resetPassword(String resetTicket, String newPassword) {
        validatePassword(newPassword);
        String normalizedEmail = passwordResetTicketService.consume(resetTicket);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(400, "账号不存在"));
        user.setPasswordHash(passwordHashService.encode(newPassword));
        userRepository.save(user);
    }

    private void validateUsername(String username) {
        if (username.isBlank()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) {
            throw new BusinessException(400, "用户名长度需在 " + MIN_USERNAME_LENGTH + " 到 " + MAX_USERNAME_LENGTH + " 个字符之间");
        }
    }

    private void validateEmail(String email) {
        if (email.isBlank()) {
            throw new BusinessException(400, "邮箱不能为空");
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException(400, "邮箱格式不正确");
        }
        if (email.length() > 200) {
            throw new BusinessException(400, "邮箱长度不能超过 200 个字符");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new BusinessException(400, "密码不能为空");
        }
        int codePointLength = password.codePointCount(0, password.length());
        if (codePointLength < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(400, "密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
        if (codePointLength > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(400, "密码长度不能超过 " + MAX_PASSWORD_LENGTH + " 个字符");
        }
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private BusinessException duplicateIdentity() {
        return new BusinessException(409, "用户名或邮箱已存在");
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(401, "邮箱或密码错误");
    }
}
