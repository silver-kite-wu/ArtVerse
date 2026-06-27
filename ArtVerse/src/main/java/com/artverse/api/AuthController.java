package com.artverse.api;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.artverse.api.dto.AuthDtos.*;
import com.artverse.application.AuthService;
import com.artverse.application.RefreshTokenService;
import com.artverse.common.BusinessException;
import com.artverse.common.aspect.RateLimit;
import com.artverse.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/register")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        User user = authService.register(req.username(), req.email(), req.password());
        StpUtil.login(user.getId(), "PC");
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        String refreshToken = refreshTokenService.issue(user.getId());
        log.info("User registered: id={}, username={}", user.getId(), user.getUsername());
        return toResponse(tokenInfo, refreshToken);
    }

    @PostMapping("/login")
    @RateLimit(windowSeconds = 60, maxRequests = 10, key = "login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        User user = authService.login(req.username(), req.password());
        StpUtil.login(user.getId(), "PC");
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        String refreshToken = refreshTokenService.issue(user.getId());
        log.info("User logged in: id={}, username={}", user.getId(), user.getUsername());
        return toResponse(tokenInfo, refreshToken);
    }

    @PostMapping("/logout")
    @RateLimit(windowSeconds = 60, maxRequests = 30, key = "logout")
    public void logout() {
        if (StpUtil.isLogin()) {
            refreshTokenService.revokeAll(StpUtil.getLoginIdAsLong());
        }
        StpUtil.logout();
    }

    @PostMapping("/refresh")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "refresh")
    public AuthResponse refresh(@RequestBody(required = false) RefreshRequest req) {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(401, "未登录");
        }
        long userId = StpUtil.getLoginIdAsLong();
        String refreshToken = req != null ? req.refreshToken() : null;

        if (refreshToken != null && !refreshToken.isBlank()) {
            if (!refreshTokenService.validateAndConsume(userId, refreshToken)) {
                refreshTokenService.revokeAll(userId);
                StpUtil.logout();
                log.warn("Refresh token reuse detected for userId={}, all tokens revoked", userId);
                throw new BusinessException(401, "Refresh token 已失效，请重新登录");
            }
        }

        StpUtil.renewTimeout(43200);
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        String newRefreshToken = refreshTokenService.issue(userId);
        return toResponse(tokenInfo, newRefreshToken);
    }

    @PostMapping("/kickout")
    @RateLimit(windowSeconds = 60, maxRequests = 10, key = "kickout")
    public void kickout(@RequestParam Long userId) {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(401, "未登录");
        }
        StpUtil.checkRole("ADMIN");
        refreshTokenService.revokeAll(userId);
        StpUtil.kickout(userId);
        log.warn("User kicked out: id={}, by admin id={}", userId, StpUtil.getLoginIdAsLong());
    }

    @GetMapping("/me")
    @RateLimit(windowSeconds = 60, maxRequests = 60, key = "me")
    public Object me() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(401, "未登录");
        }
        return StpUtil.getTokenInfo();
    }

    private AuthResponse toResponse(SaTokenInfo info, String refreshToken) {
        return new AuthResponse(
                info.getTokenName(),
                info.getTokenValue(),
                info.getTokenTimeout(),
                refreshToken,
                refreshTokenService.getTimeoutSeconds()
        );
    }
}
