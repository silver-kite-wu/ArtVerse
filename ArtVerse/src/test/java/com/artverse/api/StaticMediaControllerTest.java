package com.artverse.api;

import com.artverse.config.ArtVerseProperties;
import com.artverse.media.MediaStorageService;
import com.artverse.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StaticMediaControllerTest {

    @Test
    void servesImageFromMinioObjectKey() throws Exception {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getMinio().setBucket("artverse-test");
        ObjectStorageService storage = new ObjectStorageService() {
            @Override
            public com.artverse.storage.StoredObject put(String objectKey, java.io.InputStream content, long sizeBytes, String contentType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.artverse.storage.StoredObject putPng(String objectKey, Path localFile, String contentType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.io.InputStream get(String bucket, String objectKey) {
                assertThat(bucket).isEqualTo("artverse-test");
                assertThat(objectKey).isEqualTo("stories/1/chapters/2/panels/panel.png");
                return new ByteArrayInputStream("png-bytes".getBytes());
            }

            @Override
            public List<com.artverse.storage.StoredObject> list(String bucket, String prefix, int limit) {
                return List.of();
            }

            @Override
            public Optional<URI> publicOrPresignedUrl(String bucket, String objectKey, Duration ttl) {
                return Optional.empty();
            }

            @Override
            public void deleteBestEffort(String bucket, String objectKey) {
            }
        };
        StaticMediaController controller = new StaticMediaController(new MediaStorageService(properties), storage, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/static/manga/stories/1/chapters/2/panels/panel.png");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.serveImage(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo("png-bytes".getBytes());
        assertThat(response.getContentType()).isEqualTo("image/png");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, max-age=31536000, immutable");
    }
}
