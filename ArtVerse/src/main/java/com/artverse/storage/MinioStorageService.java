package com.artverse.storage;

import com.artverse.config.ArtVerseProperties;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService implements ObjectStorageService {

    private final ArtVerseProperties properties;
    private MinioClient minioClient;

    @PostConstruct
    void init() {
        ArtVerseProperties.Minio cfg = properties.getMinio();
        minioClient = MinioClient.builder()
                .endpoint(cfg.getEndpoint())
                .credentials(cfg.getAccessKey(), cfg.getSecretKey())
                .region(cfg.getRegion())
                .build();
    }

    @Override
    public StoredObject putPng(String objectKey, Path localFile, String contentType) {
        try {
            ArtVerseProperties.Minio cfg = properties.getMinio();
            long size = Files.size(localFile);

            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(cfg.getBucket()).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(cfg.getBucket()).build());
            }

            minioClient.uploadObject(
                    UploadObjectArgs.builder()
                            .bucket(cfg.getBucket())
                            .object(objectKey)
                            .filename(localFile.toString())
                            .contentType(contentType)
                            .build()
            );

            return new StoredObject(cfg.getBucket(), objectKey, contentType, size);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload to MinIO: " + e.getMessage(), e);
        }
    }

    @Override
    public StoredObject put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        if (content == null || sizeBytes <= 0) throw new IllegalArgumentException("Image content must not be empty");
        try {
            ArtVerseProperties.Minio cfg = properties.getMinio();
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(cfg.getBucket()).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(cfg.getBucket()).build());
            }
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(cfg.getBucket())
                    .object(objectKey)
                    .stream(content, sizeBytes, -1)
                    .contentType(contentType)
                    .build());
            return new StoredObject(cfg.getBucket(), objectKey, contentType, sizeBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload to MinIO: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream get(String bucket, String objectKey) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to get from MinIO: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredObject> list(String bucket, String prefix, int limit) {
        try {
            List<StoredObject> objects = new ArrayList<>();
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .recursive(true)
                            .build()
            );
            for (Result<Item> result : results) {
                Item item = result.get();
                if (!item.isDir()) {
                    objects.add(new StoredObject(bucket, item.objectName(), "image/png", item.size()));
                    if (objects.size() >= limit) break;
                }
            }
            return objects;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list MinIO objects: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<URI> publicOrPresignedUrl(String bucket, String objectKey, Duration ttl) {
        ArtVerseProperties.Minio cfg = properties.getMinio();
        if (cfg.getPublicBaseUrl() != null && !cfg.getPublicBaseUrl().isBlank()) {
            String base = cfg.getPublicBaseUrl().replaceAll("/+$", "");
            return Optional.of(URI.create(base + "/" + objectKey));
        }
        try {
            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry((int) ttl.getSeconds(), TimeUnit.SECONDS)
                            .build()
            );
            return Optional.of(URI.create(url));
        } catch (Exception e) {
            log.warn("Failed to generate presigned URL for {}/{}: {}", bucket, objectKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void deleteBestEffort(String bucket, String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            log.warn("Failed to delete MinIO object {}/{}: {}", bucket, objectKey, e.getMessage());
        }
    }

    @Override
    public Optional<StoredObject> stat(String bucket, String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
            return Optional.of(new StoredObject(bucket, objectKey, stat.contentType(), stat.size()));
        } catch (Exception e) {
            log.debug("Failed to stat MinIO object {}/{}: {}", bucket, objectKey, e.getMessage());
            return Optional.empty();
        }
    }
}
