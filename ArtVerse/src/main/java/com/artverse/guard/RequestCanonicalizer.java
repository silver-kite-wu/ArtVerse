package com.artverse.guard;

import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
public class RequestCanonicalizer {

    public String normalizeText(String value) {
        if (value == null) return "";
        return value.trim().replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\t ]+", " ");
    }

    public String imageHash(String base64) {
        if (base64 == null || base64.isBlank()) return "";
        String data = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        byte[] bytes = Base64.getDecoder().decode(data);
        return "sha256:" + Hashing.sha256Hex(bytes);
    }
}
