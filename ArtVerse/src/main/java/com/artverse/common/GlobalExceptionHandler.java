package com.artverse.common;

import cn.dev33.satoken.exception.NotLoginException;
import com.artverse.security.AuthErrorCodes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        if (ex.getCode() != null) {
            body.put("code", ex.getCode());
        }
        if (ex.getProvider() != null) {
            body.put("provider", ex.getProvider());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, Object>> handleNotLogin(NotLoginException ex, HttpServletRequest request) {
        log.info("Authentication required: method={}, uri={}, reason={}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(401).body(Map.of(
                "message", "Login expired",
                "code", AuthErrorCodes.AUTH_EXPIRED));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(Map.of("message", detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected while response was being written: {}", ex.getMessage());
            return ResponseEntity.noContent().build();
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError().body(Map.of("message", "Internal server error"));
    }

    private boolean isClientDisconnect(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (current instanceof IOException || className.contains("ClientAbortException")) {
                if (message == null || isClientDisconnectMessage(message)) {
                    return true;
                }
            }
            if (message != null && isClientDisconnectMessage(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isClientDisconnectMessage(String message) {
        return message.contains("你的主机中的软件中止了一个已建立的连接")
                || message.contains("Broken pipe")
                || message.contains("Connection reset by peer")
                || message.contains("An established connection was aborted")
                || message.contains("远程主机强迫关闭了一个现有的连接");
    }
}
