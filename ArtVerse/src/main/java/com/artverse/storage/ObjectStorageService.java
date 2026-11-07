package com.artverse.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ObjectStorageService {

    /** Bucket used by the regular image endpoints. */
    String defaultBucket();

    /** Private bucket for generation snapshots and candidate evidence. */
    String privateBucket();

    StoredObject putPng(String objectKey, Path localFile, String contentType);

    /** Upload exactly sizeBytes bytes. The caller owns and closes the input stream. */
    StoredObject put(String objectKey, InputStream content, long sizeBytes, String contentType);

    /**
     * Upload only when the object does not exist yet. Returns empty when an object with the
     * same key already exists. The caller owns and closes the input stream.
     */
    Optional<StoredObject> putIfAbsent(String bucket, String objectKey, InputStream content,
                                       long sizeBytes, String contentType, Map<String, String> metadata);

    InputStream get(String bucket, String objectKey);

    /** Open an object, returning empty when it does not exist. */
    Optional<InputStream> openIfPresent(String bucket, String objectKey);

    List<StoredObject> list(String bucket, String prefix, int limit);

    Optional<URI> publicOrPresignedUrl(String bucket, String objectKey, Duration ttl);

    void deleteBestEffort(String bucket, String objectKey);

    /** Retrieve object metadata without fetching the content; empty when the object is missing. */
    default Optional<ObjectStat> statWithMetadata(String bucket, String objectKey) {
        return Optional.empty();
    }

    /** Size and content type only; backs off to empty on any storage error. */
    default Optional<StoredObject> stat(String bucket, String objectKey) {
        try {
            return statWithMetadata(bucket, objectKey)
                    .map(stat -> new StoredObject(stat.bucket(), stat.objectKey(), stat.contentType(), stat.sizeBytes()));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }
}
