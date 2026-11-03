package com.artverse.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface ObjectStorageService {

    StoredObject putPng(String objectKey, Path localFile, String contentType);

    /** Upload exactly sizeBytes bytes. The caller owns and closes the input stream. */
    StoredObject put(String objectKey, InputStream content, long sizeBytes, String contentType);

    InputStream get(String bucket, String objectKey);

    List<StoredObject> list(String bucket, String prefix, int limit);

    Optional<URI> publicOrPresignedUrl(String bucket, String objectKey, Duration ttl);

    void deleteBestEffort(String bucket, String objectKey);

    /**
     * Retrieve object metadata without fetching the content.
     * Default returns empty — storage backends that support this (e.g. MinIO) should override.
     */
    default Optional<StoredObject> stat(String bucket, String objectKey) {
        return Optional.empty();
    }
}
