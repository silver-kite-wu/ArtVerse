package com.artverse.api;

import com.artverse.application.*;
import com.artverse.common.ApiResponseAdvice;
import com.artverse.domain.User;
import com.artverse.security.AuthCookieService;
import com.artverse.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserProfileContractTest {
    CurrentUserService current = mock(CurrentUserService.class);
    UserProfileService profiles = mock(UserProfileService.class);
    ObjectStorageService storage = mock(ObjectStorageService.class);
    AuthCookieService cookies = mock(AuthCookieService.class);
    MockMvc mvc;
    User user;
    @BeforeEach void setup() {
        user = new User(); user.setId(9L); user.setUsername("alice"); user.setEmail("alice@example.test");
        when(current.requireCurrentUser()).thenReturn(user);
        var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(new UserController(current, mock(ApiKeyService.class), profiles, storage, cookies))
                .setControllerAdvice(new ApiResponseAdvice())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    @Test void returnsAccountAndPrivateAvatarUrlWithoutStorageKeys() throws Exception {
        user.setAvatarBucket("private"); user.setAvatarKey("avatars/9/version.png");
        mvc.perform(get("/api/user/me")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.username").value("alice")).andExpect(jsonPath("$.data.avatar_url").value("/api/user/me/avatar?v=version.png"))
                .andExpect(jsonPath("$.data.avatar_key").doesNotExist()).andExpect(jsonPath("$.data.password_hash").doesNotExist());
    }
    @Test void savesUsernameForCurrentAccount() throws Exception {
        when(profiles.updateName(9, "new-name")).thenReturn(user);
        mvc.perform(put("/api/user/me").contentType("application/json").content("{\"username\":\"new-name\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value("alice@example.test"));
        verify(profiles).updateName(9, "new-name");
    }
    @Test void passwordSnakeCaseContractRevokesCookieAndReturnsNoContent() throws Exception {
        mvc.perform(post("/api/user/me/password").contentType("application/json").content("""
                {"current_password":"old-password","new_password":"new-password","confirm_password":"new-password"}
                """))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(profiles).changePassword(9, "old-password", "new-password", "new-password");
        verify(cookies).clearRefreshCookie(any());
    }
    @Test void readsOnlyCurrentUsersBlobAsBinary() throws Exception {
        user.setAvatarBucket("private"); user.setAvatarKey("avatars/9/test.png"); user.setAvatarContentType("image/png");
        byte[] bytes = {1, 2, 3}; when(storage.get("private", "avatars/9/test.png")).thenReturn(new ByteArrayInputStream(bytes));
        mvc.perform(get("/api/user/me/avatar")).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(content().bytes(bytes));
        verify(storage).get("private", "avatars/9/test.png");
    }
    @Test void uploadAndDeleteUseCurrentAccount() throws Exception {
        var file = new MockMultipartFile("image", new byte[]{1}); when(profiles.saveAvatar(eq(9L), any())).thenReturn(user);
        mvc.perform(multipart("/api/user/me/avatar").file(file)).andExpect(status().isOk()); verify(profiles).saveAvatar(eq(9L), any());
        when(profiles.deleteAvatar(9)).thenReturn(user); mvc.perform(delete("/api/user/me/avatar")).andExpect(status().isOk()); verify(profiles).deleteAvatar(9);
    }
}
