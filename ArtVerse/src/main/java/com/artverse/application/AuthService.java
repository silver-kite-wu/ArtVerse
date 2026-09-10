package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Role;
import com.artverse.domain.User;
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

    @Transactional
    public User register(String username, String email, String password) {
        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        validateUsername(normalizedUsername);
        validateEmail(normalizedEmail);
        validatePassword(password);

        if (userRepository.existsByUsername(normalizedUsername) || userRepository.existsByEmail(normalizedEmail)) {
            throw duplicateIdentity();
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
    public User login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        User user = userRepository.findByUsername(normalizedUsername).orElse(null);
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
        return new BusinessException(401, "用户名或密码错误");
    }
}
