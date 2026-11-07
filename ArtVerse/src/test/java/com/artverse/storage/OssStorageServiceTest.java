package com.artverse.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import com.artverse.config.ArtVerseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssStorageServiceTest {

    private OSS client;
    private OssStorageService storage;

    @BeforeEach
    void setUp() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getOss().setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        properties.getOss().setBucket("image-test");
        properties.getOss().setPrivateBucket("image-test-private");
        properties.getOss().setAccessKey("test-ak");
        properties.getOss().setSecretKey("test-sk");
        client = mock(OSS.class);
        when(client.doesBucketExist(anyString())).thenReturn(true);
        storage = new TestOssStorageService(properties, client);
        storage.init();
    }

    @Test
    void uploadsOriginalStreamWithLengthAndContentType() {
        byte[] original = {1, 2, 3, 4};
        StoredObject result = storage.put("panels/output.jpg", new ByteArrayInputStream(original), original.length, "image/jpeg");

        ArgumentCaptor<PutObjectRequest> upload = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(upload.capture());
        PutObjectRequest request = upload.getValue();
        assertThat(request.getBucketName()).isEqualTo("image-test");
        assertThat(request.getKey()).isEqualTo("panels/output.jpg");
        assertThat(request.getMetadata().getContentLength()).isEqualTo(original.length);
        assertThat(request.getMetadata().getContentType()).isEqualTo("image/jpeg");
        assertThat(request.getInputStream()).isNotNull();
        assertThat(result).isEqualTo(new StoredObject("image-test", "panels/output.jpg", "image/jpeg", original.length));
    }

    @Test
    void conditionalUploadReturnsEmptyWhenObjectAlreadyExists() {
        OSSException exists = mock(OSSException.class);
        when(exists.getErrorCode()).thenReturn("FileAlreadyExists");
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(exists);

        var result = storage.putIfAbsent("image-test", "snapshot.enc", new ByteArrayInputStream(new byte[]{1}), 1,
                "application/octet-stream", Map.of());

        assertThat(result).isEmpty();
        ArgumentCaptor<PutObjectRequest> upload = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(upload.capture());
        assertThat(upload.getValue().getHeaders()).containsEntry("x-oss-forbid-overwrite", "true");
    }

    @Test
    void conditionalUploadCreatesObjectWhenAbsent() {
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());

        var result = storage.putIfAbsent("image-test", "snapshot.enc", new ByteArrayInputStream(new byte[]{1}), 1,
                "application/octet-stream", Map.of("sha256", "abc"));

        assertThat(result).contains(new StoredObject("image-test", "snapshot.enc", "application/octet-stream", 1));
    }

    @Test
    void metadataIsNormalizedForCandidateVerification() {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("image/png");
        metadata.setContentLength(5);
        metadata.setUserMetadata(Map.of("SHA256", "abc", "operation-id", "op"));
        when(client.getObjectMetadata("image-test", "candidate.png")).thenReturn(metadata);

        var stat = storage.statWithMetadata("image-test", "candidate.png");

        assertThat(stat).isPresent();
        assertThat(stat.get().contentType()).isEqualTo("image/png");
        assertThat(stat.get().sizeBytes()).isEqualTo(5);
        assertThat(stat.get().metadata()).containsEntry("sha256", "abc").containsEntry("operation-id", "op");
    }

    @Test
    void missingObjectYieldsEmptyMetadata() {
        OSSException missing = mock(OSSException.class);
        when(missing.getErrorCode()).thenReturn("NoSuchKey");
        when(client.getObjectMetadata("image-test", "missing.png")).thenThrow(missing);

        assertThat(storage.statWithMetadata("image-test", "missing.png")).isEmpty();
    }

    @Test
    void listingFollowsPaginationUntilLimitIsReached() {
        ObjectListing first = new ObjectListing();
        first.addObjectSummary(summary("generation-results/1/1/a.png", 10));
        first.addObjectSummary(summary("generation-results/1/2/b.png", 20));
        first.setTruncated(true);
        first.setNextMarker("generation-results/1/2/b.png");
        ObjectListing second = new ObjectListing();
        second.addObjectSummary(summary("generation-results/1/3/c.png", 30));
        second.setTruncated(false);
        when(client.listObjects(any(ListObjectsRequest.class))).thenReturn(first, second);

        List<StoredObject> listed = storage.list("image-test", "generation-results/1/", 3);

        assertThat(listed).extracting(StoredObject::objectKey)
                .containsExactly("generation-results/1/1/a.png", "generation-results/1/2/b.png", "generation-results/1/3/c.png");
        verify(client, times(2)).listObjects(any(ListObjectsRequest.class));
    }

    @Test
    void presignedUrlIsGeneratedForPrivateObjects() throws Exception {
        when(client.generatePresignedUrl(any(), any(), any())).thenReturn(new java.net.URL(
                "https://image-test.oss-cn-hangzhou.aliyuncs.com/private.png?Signature=x"));

        var url = storage.publicOrPresignedUrl("image-test-private", "private.png", Duration.ofMinutes(10));

        assertThat(url).contains(java.net.URI.create("https://image-test.oss-cn-hangzhou.aliyuncs.com/private.png?Signature=x"));
    }

    @Test
    void regionIsDerivedFromStandardEndpointOrTakenFromConfiguration() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getOss().setEndpoint("https://oss-cn-beijing-internal.aliyuncs.com");
        assertThat(OssStorageService.resolveRegion(properties.getOss())).isEqualTo("cn-beijing");

        properties.getOss().setRegion("cn-shanghai");
        assertThat(OssStorageService.resolveRegion(properties.getOss())).isEqualTo("cn-shanghai");

        properties.getOss().setRegion("");
        properties.getOss().setEndpoint("https://img.example.com");
        assertThat(OssStorageService.resolveRegion(properties.getOss())).isNull();
    }

    private static OSSObjectSummary summary(String key, long size) {
        OSSObjectSummary summary = new OSSObjectSummary();
        summary.setKey(key);
        summary.setSize(size);
        return summary;
    }

    private static class TestOssStorageService extends OssStorageService {
        private final OSS client;

        TestOssStorageService(ArtVerseProperties properties, OSS client) {
            super(properties);
            this.client = client;
        }

        @Override
        OSS createClient(ArtVerseProperties.Oss cfg) {
            return client;
        }
    }
}
