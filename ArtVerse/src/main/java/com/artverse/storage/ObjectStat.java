package com.artverse.storage;

import java.util.Map;

public record ObjectStat(
        String bucket,
        String objectKey,
        String contentType,
        long sizeBytes,
        Map<String, String> metadata
) {
    public ObjectStat {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
