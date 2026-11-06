package com.artverse.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.ServiceException;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.CreateBucketRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.PutObjectRequest;
import com.artverse.config.ArtVerseProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "artverse.oss", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OssStorageService implements ObjectStorageService {

    private static final int MAX_KEYS_PER_LISTING = 1000;
    private static final Set<String> ALREADY_EXISTS_CODES =
            Set.of("FileAlreadyExists", "ObjectAlreadyExists", "PreconditionFailed");
    private static final Set<String> MISSING_CODES =
            Set.of("NoSuchKey", "NoSuchBucket", "NoSuchObject");
    /** 标准地域 endpoint：oss-<cn|ap|us|eu>-<...>[-internal].aliyuncs.com */
    private static final Pattern REGION_ENDPOINT = Pattern.compile(
            "^https?://oss-(cn|ap|us|eu)-([a-z0-9-]+?)(?:-internal)?\\.aliyuncs\\.com(?::\\d+)?(?:/.*)?$");

    private final ArtVerseProperties properties;
    private final Set<String> ensuredBuckets = ConcurrentHashMap.newKeySet();
    private OSS client;

    public OssStorageService(ArtVerseProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        ArtVerseProperties.Oss cfg = properties.getOss();
        requireConfigured(cfg);
        this.client = createClient(cfg);
        log.info("Aliyun OSS storage enabled: endpoint={}, region={}, bucket={}, privateBucket={}",
                cfg.getEndpoint(), resolveRegion(cfg), cfg.getBucket(), cfg.getPrivateBucket());
    }

    OSS createClient(ArtVerseProperties.Oss cfg) {
        String region = resolveRegion(cfg);
        if (region == null) {
            throw new IllegalStateException("Cannot derive OSS region from endpoint " + cfg.getEndpoint()
                    + "; set ARTVERSE_OSS_REGION (for example cn-beijing)");
        }
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        configuration.setConnectionTimeout((int) Duration.ofSeconds(cfg.getConnectTimeoutSeconds()).toMillis());
        configuration.setSocketTimeout((int) Duration.ofSeconds(cfg.getReadTimeoutSeconds()).toMillis());
        configuration.setRequestTimeoutEnabled(true);
        configuration.setRequestTimeout((int) Duration.ofSeconds(cfg.getReadTimeoutSeconds() + cfg.getConnectTimeoutSeconds()).toMillis());
        configuration.setMaxConnections(cfg.getMaxConnections());
        configuration.setMaxErrorRetry(2);
        OSS built = new OSSClientBuilder().build(cfg.getEndpoint(), cfg.getAccessKey(), cfg.getSecretKey(), configuration);
        if (built instanceof OSSClient ossClient) {
            // V4 签名依赖 region；builder 不会自动从 endpoint 推导，必须显式设置。
            ossClient.setRegion(region);
        }
        return built;
    }

    static String resolveRegion(ArtVerseProperties.Oss cfg) {
        String configured = cfg.getRegion();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String endpoint = cfg.getEndpoint() == null ? "" : cfg.getEndpoint().trim().toLowerCase(Locale.ROOT);
        Matcher matcher = REGION_ENDPOINT.matcher(endpoint);
        return matcher.matches() ? matcher.group(1) + "-" + matcher.group(2) : null;
    }

    @PreDestroy
    void shutdown() {
        if (client != null) client.shutdown();
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
            long size = Files.size(localFile);
            ensureBucket(defaultBucket());
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType);
            metadata.setContentLength(size);
            client.putObject(new PutObjectRequest(defaultBucket(), objectKey, localFile.toFile(), metadata));
            return new StoredObject(defaultBucket(), objectKey, contentType, size);
        } catch (Exception e) {
            throw storageFailure("upload", objectKey, e);
        }
    }

    @Override
    public StoredObject put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        if (content == null || sizeBytes <= 0) throw new IllegalArgumentException("Image content must not be empty");
        try {
            ensureBucket(defaultBucket());
            client.putObject(new PutObjectRequest(defaultBucket(), objectKey, content, objectMetadata(sizeBytes, contentType, Map.of())));
            return new StoredObject(defaultBucket(), objectKey, contentType, sizeBytes);
        } catch (Exception e) {
            throw storageFailure("upload", objectKey, e);
        }
    }

    @Override
    public Optional<StoredObject> putIfAbsent(String bucket, String objectKey, InputStream content,
                                              long sizeBytes, String contentType, Map<String, String> metadata) {
        if (content == null || sizeBytes <= 0) throw new IllegalArgumentException("Object content must not be empty");
        try {
            ensureBucket(bucket);
            PutObjectRequest request = new PutObjectRequest(bucket, objectKey, content,
                    objectMetadata(sizeBytes, contentType, metadata));
            request.addHeader("x-oss-forbid-overwrite", "true");
            client.putObject(request);
            return Optional.of(new StoredObject(bucket, objectKey, contentType, sizeBytes));
        } catch (ServiceException e) {
            if (ALREADY_EXISTS_CODES.contains(e.getErrorCode())) return Optional.empty();
            throw storageFailure("conditional upload", objectKey, e);
        } catch (Exception e) {
            throw storageFailure("conditional upload", objectKey, e);
        }
    }

    @Override
    public InputStream get(String bucket, String objectKey) {
        try {
            OSSObject object = client.getObject(bucket, objectKey);
            return object.getObjectContent();
        } catch (Exception e) {
            throw storageFailure("download", objectKey, e);
        }
    }

    @Override
    public Optional<InputStream> openIfPresent(String bucket, String objectKey) {
        try {
            return Optional.of(client.getObject(bucket, objectKey).getObjectContent());
        } catch (OSSException e) {
            if (isMissing(e)) return Optional.empty();
            throw storageFailure("download", objectKey, e);
        } catch (Exception e) {
            throw storageFailure("download", objectKey, e);
        }
    }

    @Override
    public List<StoredObject> list(String bucket, String prefix, int limit) {
        if (limit <= 0) return List.of();
        try {
            List<StoredObject> objects = new ArrayList<>();
            String marker = null;
            while (objects.size() < limit) {
                int maxKeys = Math.min(MAX_KEYS_PER_LISTING, limit - objects.size());
                ListObjectsRequest request = new ListObjectsRequest(bucket)
                        .withPrefix(prefix)
                        .withMaxKeys(maxKeys);
                if (marker != null) request.withMarker(marker);
                ObjectListing listing = client.listObjects(request);
                for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                    objects.add(new StoredObject(bucket, summary.getKey(), contentTypeFor(summary.getKey()), summary.getSize()));
                    if (objects.size() >= limit) break;
                }
                if (!listing.isTruncated()) break;
                String next = listing.getNextMarker();
                if (next == null || next.isBlank()) {
                    List<OSSObjectSummary> summaries = listing.getObjectSummaries();
                    if (summaries.isEmpty()) break;
                    // Without a delimiter OSS may omit NextMarker; the last key is the next marker.
                    next = summaries.get(summaries.size() - 1).getKey();
                }
                if (next.equals(marker)) break;
                marker = next;
            }
            return objects;
        } catch (Exception e) {
            throw storageFailure("list", prefix, e);
        }
    }

    @Override
    public Optional<URI> publicOrPresignedUrl(String bucket, String objectKey, Duration ttl) {
        ArtVerseProperties.Oss cfg = properties.getOss();
        if (cfg.getPublicBaseUrl() != null && !cfg.getPublicBaseUrl().isBlank() && bucket.equals(defaultBucket())) {
            String base = cfg.getPublicBaseUrl().replaceAll("/+$", "");
            return Optional.of(URI.create(base + "/" + objectKey));
        }
        try {
            Duration effective = ttl == null || ttl.isNegative() || ttl.isZero()
                    ? Duration.ofSeconds(cfg.getPresignedUrlExpireSeconds()) : ttl;
            Date expiration = Date.from(Instant.now().plus(effective));
            return Optional.of(client.generatePresignedUrl(bucket, objectKey, expiration).toURI());
        } catch (Exception e) {
            log.warn("Failed to generate presigned URL for object {}: {}", objectKey, errorCode(e));
            return Optional.empty();
        }
    }

    @Override
    public void deleteBestEffort(String bucket, String objectKey) {
        try {
            client.deleteObject(bucket, objectKey);
        } catch (Exception e) {
            log.warn("Failed to delete OSS object {}: {}", objectKey, errorCode(e));
        }
    }

    @Override
    public Optional<ObjectStat> statWithMetadata(String bucket, String objectKey) {
        try {
            ObjectMetadata metadata = client.getObjectMetadata(bucket, objectKey);
            return Optional.of(new ObjectStat(bucket, objectKey, metadata.getContentType(), metadata.getContentLength(),
                    normalizeMetadata(metadata.getUserMetadata())));
        } catch (OSSException e) {
            if (isMissing(e)) return Optional.empty();
            throw storageFailure("metadata", objectKey, e);
        } catch (Exception e) {
            throw storageFailure("metadata", objectKey, e);
        }
    }

    private ObjectMetadata objectMetadata(long sizeBytes, String contentType, Map<String, String> metadata) {
        ObjectMetadata result = new ObjectMetadata();
        result.setContentLength(sizeBytes);
        result.setContentType(contentType);
        if (metadata != null && !metadata.isEmpty()) result.setUserMetadata(normalizeMetadata(metadata));
        return result;
    }

    private Map<String, String> normalizeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        Map<String, String> normalized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) normalized.put(key.toLowerCase(Locale.ROOT), value);
        });
        return normalized;
    }

    private void ensureBucket(String bucket) {
        if (ensuredBuckets.contains(bucket)) return;
        if (!client.doesBucketExist(bucket)) {
            if (!properties.getOss().isAutoCreateBucket()) {
                throw new IllegalStateException("OSS bucket does not exist: " + bucket);
            }
            try {
                client.createBucket(new CreateBucketRequest(bucket));
                log.info("Created OSS bucket {}", bucket);
            } catch (OSSException e) {
                if (!Set.of("BucketAlreadyExists", "BucketAlreadyOwnedByYou").contains(e.getErrorCode())) {
                    throw storageFailure("bucket", bucket, e);
                }
            }
        }
        ensuredBuckets.add(bucket);
    }

    private static boolean isMissing(OSSException e) {
        return MISSING_CODES.contains(e.getErrorCode());
    }

    private static String contentTypeFor(String objectKey) {
        String lower = objectKey.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".json")) return "application/json";
        return "image/png";
    }

    private static RuntimeException storageFailure(String action, String objectKey, Exception cause) {
        String code = errorCode(cause);
        return new RuntimeException("OSS " + action + " failed for object " + objectKey + " (" + code + ")", cause);
    }

    private static String errorCode(Exception e) {
        if (e instanceof ServiceException service) {
            String code = service.getErrorCode();
            return code == null ? service.getClass().getSimpleName() : code;
        }
        return e.getClass().getSimpleName();
    }

    private static void requireConfigured(ArtVerseProperties.Oss cfg) {
        List<String> missing = new ArrayList<>();
        if (isBlank(cfg.getEndpoint())) missing.add("endpoint");
        if (isBlank(cfg.getBucket())) missing.add("bucket");
        if (isBlank(cfg.getPrivateBucket())) missing.add("private-bucket");
        if (isBlank(cfg.getAccessKey())) missing.add("access-key");
        if (isBlank(cfg.getSecretKey())) missing.add("secret-key");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("artverse.oss.enabled=true but missing configuration: " + String.join(", ", missing)
                    + " (set ARTVERSE_OSS_* environment variables)");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
