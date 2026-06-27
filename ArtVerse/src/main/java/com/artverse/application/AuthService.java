package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.BCryptPasswordEncoder;
import com.artverse.domain.Role;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int MIN_USERNAME_LENGTH = 2;
    private static final int MAX_USERNAME_LENGTH = 50;

    @Transactional
    public User register(String username, String email, String password) {
        validateUsername(username);
        validateEmail(email);
        validatePassword(password);

        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(409, "用户名已存在");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(409, "邮箱已被注册");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(401, "用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return user;
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        String trimmed = username.trim();
        if (trimmed.length() < MIN_USERNAME_LENGTH || trimmed.length() > MAX_USERNAME_LENGTH) {
            throw new BusinessException(400, "用户名长度需在 " + MIN_USERNAME_LENGTH + " 到 " + MAX_USERNAME_LENGTH + " 个字符之间");
        }
    }

    private void validateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(400, "邮箱不能为空");
        }
        String trimmed = email.trim();
        if (!trimmed.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException(400, "邮箱格式不正确");
        }
        if (trimmed.length() > 200) {
            throw new BusinessException(400, "邮箱长度不能超过 200 个字符");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new BusinessException(400, "密码不能为空");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(400, "密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(400, "密码长度不能超过 " + MAX_PASSWORD_LENGTH + " 个字符");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }
        int categories = (hasLetter ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);
        if (categories < 2) {
            throw new BusinessException(400, "密码需包含至少两种字符类型（字母、数字、特殊符号）");
        }
    }
}
