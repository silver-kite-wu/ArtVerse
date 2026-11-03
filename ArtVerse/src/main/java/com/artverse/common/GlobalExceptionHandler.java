package com.artverse.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import com.artverse.security.AuthErrorCodes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.artverse.application.imagequeue.ImageRequestException.class)
    public ResponseEntity<Result<?>> handleImageRequest(com.artverse.application.imagequeue.ImageRequestException ex) {
        return ResponseEntity.status(ex.getHttpStatus()).body(Result.fail(ex.getNumericCode(), ex.getMessage(), ex.getData()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> handleBusiness(BusinessException ex) {
        Object data = ex.getProvider() == null ? null : Map.of("provider", ex.getProvider());
        int code = ex.getCode() == null ? ex.getStatus() : ex.getCode();
        return ResponseEntity.status(ex.getStatus()).body(Result.fail(code, ex.getMessage(), data));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<?>> handleNotLogin(NotLoginException ex, HttpServletRequest request) {
        log.info("Authentication required: method={}, uri={}, reason={}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(401).body(Result.fail(AuthErrorCodes.AUTH_EXPIRED, "Login expired"));
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Result<?>> handleNotRole(NotRoleException ex) {
        return ResponseEntity.status(403).body(Result.fail(403, "Forbidden"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(Result.fail(400, detail));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<?>> handleNotFound(Exception ex) {
        return ResponseEntity.status(404).body(Result.fail(404, "请求的接口或资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleGeneral(Exception ex) {
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected while response was being written: {}", ex.getMessage());
            return ResponseEntity.noContent().build();
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError().body(Result.fail(500, "Internal server error"));
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
