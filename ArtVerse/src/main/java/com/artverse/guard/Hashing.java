package com.artverse.guard;

import com.artverse.common.BusinessException;

import java.security.MessageDigest;

final class Hashing {

    private Hashing() {
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to hash idempotency payload");
        }
    }
}
