package com.artverse.storage;

import com.artverse.config.ArtVerseProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only storage backend; keeps object semantics (conditional write, metadata) without MinIO/OSS. */
@Component
@Primary
public class InMemoryObjectStorageService implements ObjectStorageService {

    private record Stored(byte[] bytes, String contentType, Map<String, String> metadata) {}

    private final ArtVerseProperties properties;
    private final Map<String, Map<String, Stored>> buckets = new ConcurrentHashMap<>();

    public InMemoryObjectStorageService(ArtVerseProperties properties) {
        this.properties = properties;
    }

    @Override
    public String defaultBucket() {
        return properties.getOss().getBucket();
    }

    @Override
    public String privateBucket() {
        return properties.getOss().getPrivateBucket();
    }

    @Override
    public StoredObject putPng(String objectKey, Path localFile, String contentType) {
        try {
            return putObject(defaultBucket(), objectKey, Files.readAllBytes(localFile), contentType, Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public StoredObject put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        return putObject(defaultBucket(), objectKey, readExactly(content, sizeBytes), contentType, Map.of());
    }

    @Override
    public Optional<StoredObject> putIfAbsent(String bucket, String objectKey, InputStream content,
                                              long sizeBytes, String contentType, Map<String, String> metadata) {
        byte[] bytes = readExactly(content, sizeBytes);
        Map<String, Stored> objects = bucketMap(bucket);
        Stored existing = objects.putIfAbsent(objectKey, new Stored(bytes, contentType, Map.copyOf(metadata)));
        return existing == null
                ? Optional.of(new StoredObject(bucket, objectKey, contentType, sizeBytes))
                : Optional.empty();
    }

    @Override
    public InputStream get(String bucket, String objectKey) {
        return openIfPresent(bucket, objectKey)
                .orElseThrow(() -> new RuntimeException("Object not found: " + bucket + "/" + objectKey));
    }

    @Override
    public Optional<InputStream> openIfPresent(String bucket, String objectKey) {
        Stored stored = bucketMap(bucket).get(objectKey);
        return stored == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(stored.bytes()));
    }

    @Override
    public List<StoredObject> list(String bucket, String prefix, int limit) {
        if (limit <= 0) return List.of();
        List<StoredObject> result = new ArrayList<>();
        bucketMap(bucket).entrySet().stream()
                .filter(entry -> prefix == null || entry.getKey().startsWith(prefix))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .limit(limit)
                .forEach(entry -> result.add(new StoredObject(bucket, entry.getKey(),
                        entry.getValue().contentType(), entry.getValue().bytes().length)));
        return result;
    }

    @Override
    public Optional<URI> publicOrPresignedUrl(String bucket, String objectKey, Duration ttl) {
        return Optional.empty();
    }

    @Override
    public void deleteBestEffort(String bucket, String objectKey) {
        bucketMap(bucket).remove(objectKey);
    }

    @Override
    public Optional<ObjectStat> statWithMetadata(String bucket, String objectKey) {
        Stored stored = bucketMap(bucket).get(objectKey);
        return stored == null ? Optional.empty() : Optional.of(new ObjectStat(
                bucket, objectKey, stored.contentType(), stored.bytes().length, stored.metadata()));
    }

    /** Test helper: overwrite an object with explicit metadata (unlike putIfAbsent). */
    public StoredObject putObject(String bucket, String objectKey, byte[] bytes, String contentType, Map<String, String> metadata) {
        bucketMap(bucket).put(objectKey, new Stored(bytes.clone(), contentType, Map.copyOf(metadata)));
        return new StoredObject(bucket, objectKey, contentType, bytes.length);
    }

    public void clear() {
        buckets.clear();
    }

    private Map<String, Stored> bucketMap(String bucket) {
        return buckets.computeIfAbsent(bucket, ignored -> new ConcurrentHashMap<>());
    }

    private static byte[] readExactly(InputStream content, long sizeBytes) {
        if (content == null || sizeBytes <= 0) throw new IllegalArgumentException("Object content must not be empty");
        try {
            byte[] bytes = content.readNBytes((int) sizeBytes + 1);
            if (bytes.length > sizeBytes) throw new IOException("Stream contains more bytes than declared");
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
