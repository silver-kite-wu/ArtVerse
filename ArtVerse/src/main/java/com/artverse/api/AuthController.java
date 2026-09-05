package com.artverse.api;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.artverse.api.dto.AuthDtos.AuthResponse;
import com.artverse.api.dto.AuthDtos.CaptchaImageResponse;
import com.artverse.api.dto.AuthDtos.ChallengeConfigResponse;
import com.artverse.api.dto.AuthDtos.LoginRequest;
import com.artverse.api.dto.AuthDtos.RefreshRequest;
import com.artverse.api.dto.AuthDtos.RegisterRequest;
import com.artverse.application.AuthService;
import com.artverse.application.RefreshTokenService;
import com.artverse.application.RefreshTokenService.Consumption;
import com.artverse.application.RefreshTokenService.ConsumptionStatus;
import com.artverse.common.BusinessException;
import com.artverse.common.aspect.RateLimit;
import com.artverse.domain.User;
import com.artverse.security.AuthCookieService;
import com.artverse.security.AuthGuardService;
import com.artverse.security.AuthErrorCodes;
import com.artverse.security.GraphicCaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookieService authCookieService;
    private final AuthGuardService authGuardService;
    private final GraphicCaptchaService graphicCaptchaService;

    @GetMapping("/challenge/config")
    public ResponseEntity<ChallengeConfigResponse> challengeConfig() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ChallengeConfigResponse(
                        authGuardService.isChallengeEnabled(),
                        authGuardService.provider(),
                        authGuardService.siteKey(),
                        authGuardService.requiresRegistrationChallenge(),
                        authGuardService.loginMode()
                ));
    }

    @GetMapping("/captcha/image")
    @RateLimit(windowSeconds = 60, maxRequests = 30, key = "captcha")
    public ResponseEntity<CaptchaImageResponse> captchaImage() {
        GraphicCaptchaService.CaptchaImage captcha = graphicCaptchaService.generate();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CaptchaImageResponse(captcha.captchaId(), captcha.image()));
    }

    @PostMapping("/register")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest req,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        authGuardService.enforceRegistrationChallenge(req.challengeToken(), request);
        User user = authService.register(req.username(), req.email(), req.password());
        StpUtil.login(user.getId(), "PC");
        String refreshToken = refreshTokenService.issue(user.getId());
        authCookieService.writeRefreshCookie(response, refreshToken, refreshTokenService.getTimeoutSeconds());
        log.info("User registered: id={}, username={}", user.getId(), user.getUsername());
        return noStore(toResponse(StpUtil.getTokenInfo()));
    }

    @PostMapping("/login")
    @RateLimit(windowSeconds = 60, maxRequests = 10, key = "login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        authGuardService.enforceLoginRisk(req.username(), req.challengeToken(), request);
        try {
            User user = authService.login(req.username(), req.password());
            authGuardService.clearLoginFailures(req.username());
            StpUtil.login(user.getId(), "PC");
            String refreshToken = refreshTokenService.issue(user.getId());
            authCookieService.writeRefreshCookie(response, refreshToken, refreshTokenService.getTimeoutSeconds());
            log.info("User logged in: id={}, username={}", user.getId(), user.getUsername());
            return noStore(toResponse(StpUtil.getTokenInfo()));
        } catch (BusinessException ex) {
            if (ex.getStatus() == 401) {
                authGuardService.recordLoginFailure(req.username());
            }
            throw ex;
        }
    }

    @PostMapping("/logout")
    @RateLimit(windowSeconds = 60, maxRequests = 30, key = "logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        if (StpUtil.isLogin()) {
            refreshTokenService.revokeAll(StpUtil.getLoginIdAsLong());
        }
        authCookieService.clearRefreshCookie(response);
        StpUtil.logout();
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/refresh")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "refresh")
    public ResponseEntity<AuthResponse> refresh(
            @RequestBody(required = false) RefreshRequest req,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String refreshToken = resolveRefreshToken(request, req);
        Long authenticatedUserId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;

        if (refreshToken != null && !refreshToken.isBlank()) {
            Consumption consumption = refreshTokenService.consume(refreshToken, authenticatedUserId);
            if (consumption.status() == ConsumptionStatus.REUSED) {
                refreshTokenService.revokeAll(consumption.userId());
                StpUtil.kickout(consumption.userId());
                log.warn("Refresh token reuse detected for userId={}, all tokens revoked", consumption.userId());
                throw BusinessException.withCode(401, "Refresh token 已失效，请重新登录", AuthErrorCodes.AUTH_EXPIRED);
            }
            if (consumption.status() != ConsumptionStatus.VALID) {
                throw BusinessException.withCode(401, "Refresh token 无效，请重新登录", AuthErrorCodes.AUTH_EXPIRED);
            }

            long userId = consumption.userId();
            if (authenticatedUserId != null) {
                StpUtil.logout();
            }
            StpUtil.login(userId, "PC");
            String newRefreshToken = refreshTokenService.issue(userId);
            authCookieService.writeRefreshCookie(response, newRefreshToken, refreshTokenService.getTimeoutSeconds());
            return noStore(toResponse(StpUtil.getTokenInfo()));
        }

        if (authenticatedUserId == null) {
            throw BusinessException.withCode(401, "登录已过期", AuthErrorCodes.AUTH_EXPIRED);
        }
        StpUtil.renewTimeout(StpUtil.getTokenTimeout());
        String newRefreshToken = refreshTokenService.issue(authenticatedUserId);
        authCookieService.writeRefreshCookie(response, newRefreshToken, refreshTokenService.getTimeoutSeconds());
        return noStore(toResponse(StpUtil.getTokenInfo()));
    }

    @PostMapping("/kickout")
    @RateLimit(windowSeconds = 60, maxRequests = 10, key = "kickout")
    public ResponseEntity<Void> kickout(@RequestParam Long userId) {
        StpUtil.checkRole("ADMIN");
        refreshTokenService.revokeAll(userId);
        StpUtil.kickout(userId);
        log.warn("User kicked out: id={}, by admin id={}", userId, StpUtil.getLoginIdAsLong());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/me")
    @RateLimit(windowSeconds = 60, maxRequests = 60, key = "me")
    public ResponseEntity<Object> me() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(StpUtil.getTokenInfo());
    }

    private ResponseEntity<AuthResponse> noStore(AuthResponse response) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private AuthResponse toResponse(SaTokenInfo info) {
        return new AuthResponse(
                true,
                info.getTokenTimeout(),
                refreshTokenService.getTimeoutSeconds()
        );
    }

    private String resolveRefreshToken(HttpServletRequest request, RefreshRequest req) {
        String cookieToken = authCookieService.readRefreshToken(request);
        if (cookieToken != null && !cookieToken.isBlank()) {
            return cookieToken;
        }
        if (!authCookieService.isRefreshBodyFallbackEnabled() || req == null) {
            return null;
        }
        return req.refreshToken();
    }
}
