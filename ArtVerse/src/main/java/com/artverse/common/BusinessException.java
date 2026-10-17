package com.artverse.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int status;
    private final Integer code;
    private final String provider;

    public BusinessException(int status, String message) {
        this(status, message, null, null);
    }

    public BusinessException(int status, String message, String provider) {
        this(status, message, null, provider);
    }

    public BusinessException(int status, String message, Integer code, String provider) {
        super(message);
        this.status = status;
        this.code = code;
        this.provider = provider;
    }

    public static BusinessException withCode(int status, String message, int code) {
        return new BusinessException(status, message, code, null);
    }

    public static BusinessException withCodeAndProvider(int status, String message, int code, String provider) {
        return new BusinessException(status, message, code, provider);
    }
}