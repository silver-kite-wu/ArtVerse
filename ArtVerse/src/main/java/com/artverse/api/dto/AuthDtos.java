package com.artverse.api.dto;

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
            @Size(min = 8, max = 128, message = "密码长度需在 8 到 128 个字符之间")
            String password
    ) {}

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空")
            String username,

            @NotBlank(message = "密码不能为空")
            String password
    ) {}

    public record RefreshRequest(
            String refreshToken
    ) {}

    public record AuthResponse(
            String tokenName,
            String tokenValue,
            long tokenTimeout,
            String refreshToken,
            long refreshTokenTimeout
    ) {}

    public record UserInfo(Long id, String username, String email) {}

    public record ApiKeyRequest(
            @NotBlank(message = "provider 不能为空")
            String provider,

            @NotBlank(message = "apiKey 不能为空")
            String apiKey
    ) {}

    public record ApiKeyResponse(String provider, String apiKeyMasked) {}
}
