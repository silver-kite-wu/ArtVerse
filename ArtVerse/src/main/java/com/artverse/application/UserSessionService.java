package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserSessionService {
    private final RefreshTokenService refreshTokenService;

    public void invalidateAll(long userId) {
        refreshTokenService.revokeAll(userId);
        StpUtil.kickout(userId);
    }
}
