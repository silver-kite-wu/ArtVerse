package com.artverse.storage;

import com.artverse.config.ArtVerseProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioStorageServiceTest {
    @Test
    void uploadsOriginalStreamWithLengthAndContentType() throws Exception {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getMinio().setBucket("image-test");
        MinioStorageService storage = new MinioStorageService(properties);
        MinioClient client = mock(MinioClient.class);
        ReflectionTestUtils.setField(storage, "minioClient", client);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        byte[] original = {1, 2, 3, 4};
        try (var content = new ByteArrayInputStream(original)) {
            StoredObject result = storage.put("panels/output.jpg", content, original.length, "image/jpeg");

            ArgumentCaptor<PutObjectArgs> upload = ArgumentCaptor.forClass(PutObjectArgs.class);
            verify(client).putObject(upload.capture());
            PutObjectArgs args = upload.getValue();
            assertThat(args.bucket()).isEqualTo("image-test");
            assertThat(args.object()).isEqualTo("panels/output.jpg");
            assertThat(args.objectSize()).isEqualTo(original.length);
            assertThat(args.contentType()).isEqualTo("image/jpeg");
            assertThat(args.stream().readAllBytes()).isEqualTo(original);
            assertThat(result).isEqualTo(new StoredObject("image-test", "panels/output.jpg", "image/jpeg", original.length));
            verify(client, never()).uploadObject(any());
        }
    }
}
