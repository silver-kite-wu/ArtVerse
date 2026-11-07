package com.artverse.api;

import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.artverse.application.ChapterAccessService;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.StoryAssetGroupRepository;
import com.artverse.persistence.StoryRepository;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceImageControllerTest {

    @Test
    void addChapterRefImageUploadsToObjectStorageAndReturnsStoredImage() throws Exception {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getOss().setBucket("artverse-test");
        Path tempRoot = Files.createTempDirectory("artverse-ref-test-");
        properties.getStorage().setRoot(tempRoot.toString());

        Story story = new Story();
        story.setId(3L);
        com.artverse.domain.User owner = new com.artverse.domain.User();
        owner.setId(1L);
        story.setUser(owner);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);

        CapturingStorage storage = new CapturingStorage();
        StoryRepository storyRepository = mock(StoryRepository.class);
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        StoryAssetGroupRepository assetGroupRepository = mock(StoryAssetGroupRepository.class);
        when(storyRepository.findById(3L)).thenReturn(Optional.of(story));
        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));

        ReferenceImageController controller = new ReferenceImageController(
                storyRepository,
                assetGroupRepository,
                new ChapterAccessService(chapterRepository),
                new MediaStorageService(properties),
                storage,
                properties
        ) {
            @Override
            Long currentUserId() {
                return 1L;
            }
        };

        Map<String, Object> result = controller.addChapterRefImage(7L, Map.of("image", pngBase64()));

        assertThat(storage.uploadedKey).startsWith("stories/3/chapters/7/ref_images/ref_");
        assertThat(storage.uploadedKey).endsWith(".png");
        assertThat(storage.uploadedBytes).isGreaterThan(0);
        assertThat(result.get("source")).isEqualTo("chapter");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) result.get("images");
        assertThat(images).hasSize(1);
        assertThat(images.get(0).get("image_path")).isEqualTo(storage.uploadedKey);
    }

    private static String pngBase64() {
        return "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";
    }

    private static class CapturingStorage implements ObjectStorageService {
        @Override
        public String defaultBucket() {
            return "artverse-test";
        }

        @Override
        public String privateBucket() {
            return "artverse-test-private";
        }

        @Override
        public StoredObject put(String objectKey, java.io.InputStream content, long sizeBytes, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredObject> putIfAbsent(String bucket, String objectKey, java.io.InputStream content, long sizeBytes, String contentType, Map<String, String> metadata) {
            throw new UnsupportedOperationException();
        }

        private String uploadedKey;
        private long uploadedBytes;

        @Override
        public StoredObject putPng(String objectKey, Path localFile, String contentType) {
            try {
                uploadedKey = objectKey;
                uploadedBytes = Files.size(localFile);
                return new StoredObject("artverse-test", objectKey, contentType, uploadedBytes);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public java.io.InputStream get(String bucket, String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.io.InputStream> openIfPresent(String bucket, String objectKey) {
            return Optional.empty();
        }

        @Override
        public List<StoredObject> list(String bucket, String prefix, int limit) {
            if (uploadedKey == null || !uploadedKey.startsWith(prefix)) {
                return List.of();
            }
            return List.of(new StoredObject(bucket, uploadedKey, "image/png", uploadedBytes));
        }

        @Override
        public Optional<java.net.URI> publicOrPresignedUrl(String bucket, String objectKey, java.time.Duration ttl) {
            return Optional.empty();
        }

        @Override
        public void deleteBestEffort(String bucket, String objectKey) {
        }
    }
}
