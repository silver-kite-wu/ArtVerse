package com.artverse.ai;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** Provider output stays in memory until it is uploaded; no local output file is required. */
public record GeneratedImage(byte[] bytes, String contentType) {
    public GeneratedImage {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Image data cannot be empty");
        if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)
                && !"image/webp".equals(contentType)) {
            throw new IllegalArgumentException("Unsupported generated image content type");
        }
    }

    public long sizeBytes() {
        return bytes.length;
    }

    public String extension() {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }

    public InputStream openStream() {
        return new ByteArrayInputStream(bytes);
    }
}
