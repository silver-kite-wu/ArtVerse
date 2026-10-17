package com.artverse.security;

public final class AuthErrorCodes {

    public static final int AUTH_EXPIRED = 40101;
    public static final int AUTH_RATE_LIMITED = 42901;
    public static final int CHALLENGE_REQUIRED = 40301;
    public static final int CHALLENGE_FAILED = 40302;
    public static final int CHALLENGE_UNAVAILABLE = 50301;
    public static final int CSRF_REJECTED = 40303;
    public static final int PASSWORD_LOGIN_LOCKED = 40304;

    private AuthErrorCodes() {
    }
}