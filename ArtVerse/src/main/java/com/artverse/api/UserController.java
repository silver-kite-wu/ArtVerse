package com.artverse.api;

import com.artverse.api.dto.AuthDtos.*;
import com.artverse.application.ApiKeyService;
import com.artverse.application.ApiKeyService.KeyInfo;
import com.artverse.application.ApiKeyService.ProviderInfo;
import com.artverse.application.CurrentUserService;
import com.artverse.application.UserProviderConfig;
import com.artverse.application.UserProfileService;
import com.artverse.domain.User;
import com.artverse.common.BusinessException;
import com.artverse.common.aspect.RateLimit;
import com.artverse.security.AuthCookieService;
import com.artverse.storage.ObjectStorageService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final CurrentUserService currentUserService;
    private final ApiKeyService apiKeyService;
    private final UserProfileService userProfileService;
    private final ObjectStorageService objectStorageService;
    private final AuthCookieService authCookieService;

    @GetMapping("/me")
    public ResponseEntity<UserInfo> me() {
        User user = currentUser();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(UserInfo.from(user));
    }

    @PutMapping("/me")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "profile-update")
    public ResponseEntity<UserInfo> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(UserInfo.from(userProfileService.updateName(currentUser().getId(), request.username())));
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(windowSeconds = 60, maxRequests = 10, key = "avatar-upload")
    public ResponseEntity<UserInfo> uploadAvatar(@RequestParam("image") MultipartFile image) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(UserInfo.from(userProfileService.saveAvatar(currentUser().getId(), image)));
    }

    @DeleteMapping("/me/avatar")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "avatar-delete")
    public ResponseEntity<UserInfo> deleteAvatar() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(UserInfo.from(userProfileService.deleteAvatar(currentUser().getId())));
    }

    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> avatar() {
        User user = currentUser();
        if (user.getAvatarKey() == null) throw new BusinessException(404, "尚未设置头像");
        try (var input = objectStorageService.get(user.getAvatarBucket(), user.getAvatarKey())) {
            byte[] bytes = input.readNBytes(UserProfileService.MAX_AVATAR_BYTES + 1);
            if (bytes.length > UserProfileService.MAX_AVATAR_BYTES) throw new BusinessException(404, "头像不可用");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(MediaType.parseMediaType(user.getAvatarContentType())).body(bytes);
        } catch (java.io.IOException ex) {
            throw new BusinessException(503, "头像暂时无法读取，请稍后重试");
        }
    }

    @PostMapping("/me/password")
    @RateLimit(windowSeconds = 600, maxRequests = 5, key = "profile-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                              HttpServletResponse response) {
        userProfileService.changePassword(currentUser().getId(), request.currentPassword(),
                request.newPassword(), request.confirmPassword());
        authCookieService.clearRefreshCookie(response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api-keys")
    public ResponseEntity<List<ApiKeyResponse>> listKeys() {
        User user = currentUser();
        List<KeyInfo> keys = apiKeyService.getKeys(user);
        return ResponseEntity.ok(keys.stream()
                .map(k -> new ApiKeyResponse(k.provider(), k.apiKeyMasked()))
                .toList());
    }

    @PutMapping("/api-keys")
    public ResponseEntity<ApiKeyResponse> saveKey(@RequestBody ApiKeyRequest req) {
        User user = currentUser();
        apiKeyService.saveKey(user, req.provider(), req.apiKey());
        List<KeyInfo> keys = apiKeyService.getKeys(user);
        KeyInfo saved = keys.stream()
                .filter(k -> k.provider().equals(req.provider()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(new ApiKeyResponse(saved.provider(), saved.apiKeyMasked()));
    }

    @DeleteMapping("/api-keys/{provider}")
    public ResponseEntity<Void> deleteKey(@PathVariable String provider) {
        User user = currentUser();
        apiKeyService.deleteKey(user, provider);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/provider-configs")
    public ResponseEntity<List<ProviderInfo>> getProviderConfigs() {
        User user = currentUser();
        return ResponseEntity.ok(apiKeyService.getProviderConfigs(user));
    }

    @GetMapping("/provider-configs/{configId}/api-key")
    public ResponseEntity<Map<String, String>> getProviderApiKey(@PathVariable Long configId) {
        String apiKey = apiKeyService.getProviderApiKey(currentUser(), configId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("api_key", apiKey));
    }

    @PutMapping("/provider-configs")
    public ResponseEntity<ProviderInfo> saveProviderConfig(@RequestBody Map<String, Object> body) {
        User user = currentUser();
        UserProviderConfig config = new UserProviderConfig(
                str(body, "slot"),
                str(body, "provider"),
                str(body, "label"),
                str(body, "api_key"),
                str(body, "base_url"),
                str(body, "model")
        );
        return ResponseEntity.ok(apiKeyService.saveProviderConfig(user, longValue(body, "config_id"), config, boolValue(body, "active")));
    }

    @DeleteMapping("/provider-configs/{configId}")
    public ResponseEntity<Void> deleteProviderConfig(@PathVariable Long configId) {
        apiKeyService.deleteProviderConfig(currentUser(), configId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/provider-configs/{configId}/activate")
    public ResponseEntity<ProviderInfo> activateProviderConfig(@PathVariable Long configId) {
        return ResponseEntity.ok(apiKeyService.activateProviderConfig(currentUser(), configId));
    }

    @PostMapping("/provider-configs/{configId}/deactivate")
    public ResponseEntity<ProviderInfo> deactivateProviderConfig(@PathVariable Long configId) {
        return ResponseEntity.ok(apiKeyService.deactivateProviderConfig(currentUser(), configId));
    }

    @PostMapping("/provider-models/discover")
    public ResponseEntity<Map<String, Object>> discoverModels(@RequestBody Map<String, Object> body) {
        List<String> models = apiKeyService.discoverModels(
                currentUser(),
                str(body, "slot"),
                str(body, "provider"),
                str(body, "api_key"),
                str(body, "base_url"),
                longValue(body, "config_id")
        );
        return ResponseEntity.ok(Map.of("models", models));
    }

    private User currentUser() {
        return currentUserService.requireCurrentUser();
    }

    private static String str(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? "" : val.toString();
    }

    private static Long longValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private static boolean boolValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
