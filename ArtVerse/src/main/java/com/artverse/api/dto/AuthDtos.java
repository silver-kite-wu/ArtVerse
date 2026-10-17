package com.artverse.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 2, max = 50, message = "用户名长度需在 2 到 50 个字符之间")
            String username,

            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 200, message = "邮箱长度不能超过 200 个字符")
            String email,

            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 16, message = "密码长度需在 8 到 16 个字符之间")
            String password,

            @Size(max = 8, message = "验证码长度不能超过 8 个字符")
            String emailCode,

            @Size(max = 2048, message = "challenge token is too long")
            String challengeToken
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 200, message = "邮箱长度不能超过 200 个字符")
            String email,

            @NotBlank(message = "密码不能为空")
            String password,

            @Size(max = 2048, message = "challenge token is too long")
            String challengeToken
    ) {
    }

    public record RefreshRequest(
            @JsonAlias("refresh_token")
            @Size(max = 2048, message = "refresh token is too long")
            String refreshToken
    ) {
    }

    public record LoginByCodeRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            String email,

            @NotBlank(message = "验证码不能为空")
            String code
    ) {
    }

    public record VerifyResetCodeRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            String email,

            @NotBlank(message = "验证码不能为空")
            String code
    ) {
    }

    public record VerifyResetCodeResponse(String resetTicket) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "重置凭证不能为空，请先完成验证码校验")
            String resetTicket,

            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 16, message = "密码长度需在 8 到 16 个字符之间")
            String newPassword
    ) {
    }

    public record SendCodeRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            String email,

            @NotBlank(message = "验证码场景不能为空")
            String scenario
    ) {
    }

    public record AuthResponse(
            boolean authenticated,
            long tokenTimeout,
            long refreshTokenTimeout
    ) {
    }

    public record ChallengeConfigResponse(
            boolean enabled,
            String provider,
            String siteKey,
            boolean registrationRequired
    ) {
    }

    public record CaptchaImageResponse(String captchaId, String image) {
    }

    public record UserInfo(Long id, String username, String email) {
    }

    public record ApiKeyRequest(
            @NotBlank(message = "provider 不能为空")
            String provider,

            @NotBlank(message = "apiKey 不能为空")
            String apiKey
    ) {
    }

    public record ApiKeyResponse(String provider, String apiKeyMasked) {
    }
}
