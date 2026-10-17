package com.artverse.api;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.artverse.api.dto.AuthDtos.AuthResponse;
import com.artverse.api.dto.AuthDtos.CaptchaImageResponse;
import com.artverse.api.dto.AuthDtos.ChallengeConfigResponse;
import com.artverse.api.dto.AuthDtos.LoginByCodeRequest;
import com.artverse.api.dto.AuthDtos.LoginRequest;
import com.artverse.api.dto.AuthDtos.RefreshRequest;
import com.artverse.api.dto.AuthDtos.ResetPasswordRequest;
import com.artverse.api.dto.AuthDtos.RegisterRequest;
import com.artverse.api.dto.AuthDtos.VerifyResetCodeRequest;
import com.artverse.api.dto.AuthDtos.VerifyResetCodeResponse;
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
                        authGuardService.requiresRegistrationChallenge()
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
        User user = authService.register(req.username(), req.email(), req.password(), req.emailCode());
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
        authGuardService.enforceLoginRisk(req.email(), request);
        if (authGuardService.isPasswordLoginLocked(req.email())) {
            throw BusinessException.withCode(403,
                    "密码错误次数过多，请 10 分钟后重试，或改用邮箱验证码登录 / 找回密码",
                    AuthErrorCodes.PASSWORD_LOGIN_LOCKED);
        }
        // 分级防爆破：窗口内已错 3 次（未达 6 次锁定）时，要求附加图形验证码；
        // 挑战阶段的请求同样计为一次失败尝试，否则计数停滞在阈值、永远无法升级到锁定
        if (authGuardService.requiresPasswordChallenge(req.email())) {
            authGuardService.recordPasswordFailure(req.email());
            authGuardService.enforcePasswordChallenge(req.challengeToken(), request);
        }
        try {
            User user = authService.login(req.email(), req.password());
            authGuardService.clearPasswordFailures(req.email());
            StpUtil.login(user.getId(), "PC");
            String refreshToken = refreshTokenService.issue(user.getId());
            authCookieService.writeRefreshCookie(response, refreshToken, refreshTokenService.getTimeoutSeconds());
            log.info("User logged in: id={}, username={}", user.getId(), user.getUsername());
            return noStore(toResponse(StpUtil.getTokenInfo()));
        } catch (BusinessException ex) {
            if (ex.getStatus() == 401) {
                authGuardService.recordPasswordFailure(req.email());
                // 错误次数已达挑战阈值时，在凭据错误响应上标记 40301，引导前端追加图形验证码
                if (authGuardService.requiresPasswordChallenge(req.email())) {
                    throw BusinessException.withCode(401, "邮箱或密码错误，请完成图形验证码后重试",
                            AuthErrorCodes.CHALLENGE_REQUIRED);
                }
            }
            throw ex;
        }
    }

    @PostMapping("/login-by-code")
    @RateLimit(windowSeconds = 60, maxRequests = 10, key = "login-code")
    public ResponseEntity<AuthResponse> loginByCode(
            @Valid @RequestBody LoginByCodeRequest req,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        authGuardService.enforceLoginRisk(req.email(), request);
        User user = authService.loginByCode(req.email(), req.code());
        StpUtil.login(user.getId(), "PC");
        String refreshToken = refreshTokenService.issue(user.getId());
        authCookieService.writeRefreshCookie(response, refreshToken, refreshTokenService.getTimeoutSeconds());
        log.info("User logged in by code: id={}, username={}", user.getId(), user.getUsername());
        return noStore(toResponse(StpUtil.getTokenInfo()));
    }

    /** 找回密码第一步：校验验证码，签发一次性重置票据 */
    @PostMapping("/password/verify-code")
    @RateLimit(windowSeconds = 60, maxRequests = 10, key = "password-verify")
    public ResponseEntity<VerifyResetCodeResponse> verifyPasswordResetCode(@Valid @RequestBody VerifyResetCodeRequest req) {
        String ticket = authService.verifyPasswordResetCode(req.email(), req.code());
        log.info("Password reset code verified: email={}", req.email());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new VerifyResetCodeResponse(ticket));
    }

    /** 找回密码第二步：凭一次性票据重置密码 */
    @PostMapping("/password/reset")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "password-reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.resetTicket(), req.newPassword());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
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
