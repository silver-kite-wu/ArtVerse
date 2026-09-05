package com.artverse.api;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.artverse.api.dto.AuthDtos.ChallengeConfigResponse;
import com.artverse.api.dto.AuthDtos.LoginRequest;
import com.artverse.api.dto.AuthDtos.RefreshRequest;
import com.artverse.api.dto.AuthDtos.RegisterRequest;
import com.artverse.application.AuthService;
import com.artverse.application.RefreshTokenService;
import com.artverse.application.RefreshTokenService.Consumption;
import com.artverse.common.BusinessException;
import com.artverse.domain.User;
import com.artverse.security.AuthCookieService;
import com.artverse.security.AuthErrorCodes;
import com.artverse.security.AuthGuardService;
import com.artverse.security.GraphicCaptchaService;
import com.artverse.api.dto.AuthDtos.CaptchaImageResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private AuthCookieService authCookieService;
    @Mock
    private AuthGuardService authGuardService;
    @Mock
    private GraphicCaptchaService graphicCaptchaService;
    @InjectMocks
    private AuthController controller;

    private MockedStatic<StpUtil> stpUtil;

    @BeforeEach
    void setUp() {
        stpUtil = org.mockito.Mockito.mockStatic(StpUtil.class);
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
    }

    @Test
    @DisplayName("returns the public challenge configuration")
    void challengeConfig() {
        when(authGuardService.isChallengeEnabled()).thenReturn(true);
        when(authGuardService.provider()).thenReturn("graphic-captcha");
        when(authGuardService.siteKey()).thenReturn("");
        when(authGuardService.requiresRegistrationChallenge()).thenReturn(true);
        when(authGuardService.loginMode()).thenReturn("always");

        ResponseEntity<ChallengeConfigResponse> result = controller.challengeConfig();

        assertThat(result.getBody()).isEqualTo(new ChallengeConfigResponse(true, "graphic-captcha", "", true, "always"));
    }

    @Test
    @DisplayName("returns a captcha image for the login page")
    void captchaImage() {
        when(graphicCaptchaService.generate()).thenReturn(
                new GraphicCaptchaService.CaptchaImage("captcha-uuid", "data:image/png;base64,abc"));

        ResponseEntity<CaptchaImageResponse> result = controller.captchaImage();

        assertThat(result.getBody()).isEqualTo(new CaptchaImageResponse("captcha-uuid", "data:image/png;base64,abc"));
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("issues a session cookie and clears failure counters on success")
        void success() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            User user = new User();
            user.setId(9L);
            user.setUsername("alice");
            when(authService.login("alice", "averylongpassword")).thenReturn(user);
            when(refreshTokenService.issue(9L)).thenReturn("refresh-token");
            when(refreshTokenService.getTimeoutSeconds()).thenReturn(43_200L);
            SaTokenInfo tokenInfo = new SaTokenInfo();
            tokenInfo.setTokenTimeout(3_600);
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(tokenInfo);

            var result = controller.login(new LoginRequest("alice", "averylongpassword", "challenge-token"), request, response);

            verify(authGuardService).enforceLoginRisk("alice", "challenge-token", request);
            verify(authGuardService).clearLoginFailures("alice");
            stpUtil.verify(() -> StpUtil.login(9L, "PC"));
            verify(authCookieService).writeRefreshCookie(response, "refresh-token", 43_200L);
            assertThat(result.getBody().authenticated()).isTrue();
            assertThat(result.getBody().tokenTimeout()).isEqualTo(3_600);
        }

        @Test
        @DisplayName("records a failed login attempt on 401")
        void recordsFailureOn401() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(authService.login("alice", "wrong-password"))
                    .thenThrow(new BusinessException(401, "用户名或密码错误"));

            assertThatThrownBy(() -> controller.login(new LoginRequest("alice", "wrong-password", null), request, response))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户名或密码错误");

            verify(authGuardService).recordLoginFailure("alice");
            verify(authCookieService, never()).writeRefreshCookie(any(HttpServletResponse.class), any(), any(Long.class));
        }
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("enforces human verification before creating an account")
        void enforcesChallenge() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            User user = new User();
            user.setId(5L);
            user.setUsername("alice");
            when(authService.register("alice", "alice@example.com", "averylongpassword")).thenReturn(user);
            when(refreshTokenService.issue(5L)).thenReturn("refresh-token");
            when(refreshTokenService.getTimeoutSeconds()).thenReturn(43_200L);
            SaTokenInfo tokenInfo = new SaTokenInfo();
            tokenInfo.setTokenTimeout(3_600);
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(tokenInfo);

            controller.register(new RegisterRequest("alice", "alice@example.com", "averylongpassword", "token"), request, response);

            verify(authGuardService).enforceRegistrationChallenge("token", request);
            verify(authCookieService).writeRefreshCookie(response, "refresh-token", 43_200L);
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @Test
        @DisplayName("restores a session from the refresh cookie")
        void restoresSessionFromCookie() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(authCookieService.readRefreshToken(request)).thenReturn("cookie-token");
            when(refreshTokenService.consume("cookie-token", null)).thenReturn(Consumption.valid(1L));
            when(refreshTokenService.issue(1L)).thenReturn("rotated-token");
            when(refreshTokenService.getTimeoutSeconds()).thenReturn(43_200L);
            stpUtil.when(StpUtil::isLogin).thenReturn(false);
            SaTokenInfo tokenInfo = new SaTokenInfo();
            tokenInfo.setTokenTimeout(1_800);
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(tokenInfo);

            var result = controller.refresh(null, request, response);

            stpUtil.verify(() -> StpUtil.login(1L, "PC"));
            verify(authCookieService).writeRefreshCookie(response, "rotated-token", 43_200L);
            assertThat(result.getBody().authenticated()).isTrue();
        }

        @Test
        @DisplayName("falls back to request body token during the migration window")
        void fallsBackToBodyToken() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(authCookieService.readRefreshToken(request)).thenReturn(null);
            when(authCookieService.isRefreshBodyFallbackEnabled()).thenReturn(true);
            when(refreshTokenService.consume("body-token", null)).thenReturn(Consumption.valid(3L));
            when(refreshTokenService.issue(3L)).thenReturn("new-cookie-token");
            when(refreshTokenService.getTimeoutSeconds()).thenReturn(43_200L);
            stpUtil.when(StpUtil::isLogin).thenReturn(false);
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(new SaTokenInfo());

            controller.refresh(new RefreshRequest("body-token"), request, response);

            verify(refreshTokenService).consume("body-token", null);
            verify(authCookieService).writeRefreshCookie(response, "new-cookie-token", 43_200L);
        }

        @Test
        @DisplayName("returns AUTH_EXPIRED when no session and no refresh token exist")
        void missingSessionAndToken() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(authCookieService.readRefreshToken(request)).thenReturn(null);
            when(authCookieService.isRefreshBodyFallbackEnabled()).thenReturn(false);
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            assertThatThrownBy(() -> controller.refresh(null, request, response))
                    .isInstanceOf(BusinessException.class)
                    .matches(ex -> ((BusinessException) ex).getStatus() == 401)
                    .matches(ex -> AuthErrorCodes.AUTH_EXPIRED.equals(((BusinessException) ex).getCode()));
        }
    }

    @Test
    @DisplayName("logout clears the refresh cookie and revokes refresh tokens")
    void logoutClearsCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        stpUtil.when(StpUtil::isLogin).thenReturn(true);
        stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(8L);

        controller.logout(response);

        verify(refreshTokenService).revokeAll(8L);
        verify(authCookieService).clearRefreshCookie(response);
        stpUtil.verify(StpUtil::logout);
    }
}
