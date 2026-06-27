package com.artverse.application;

import com.artverse.ai.GeneratedImage;
import com.artverse.ai.Image2Client;
import com.artverse.ai.ImageGenerationRequest;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.CharacterProfile;
import com.artverse.domain.MangaImage;
import com.artverse.domain.Story;
import com.artverse.domain.StoryAssetGroup;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaImageRepository;
import com.artverse.persistence.StoryAssetGroupRepository;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaGenerationServiceTest {

    private static final String STORYBOARD_SCENE = "[\""
            + "\\u3010\\u7b2c1\\u683c\\uff08wide\\uff09\\u3011Rainy alley and distant city lights. "
            + "\\u3010\\u7b2c2\\u683c\\uff08medium\\uff09\\u3011Protagonist opens the door. "
            + "\\u3010\\u7b2c3\\u683c\\uff08close-up\\uff09\\u3011Hand on the old door handle. "
            + "\\u3010\\u7b2c4\\u683c\\uff08close-up\\uff09\\u3011Reflected city lights in the eyes."
            + "\"]";

    @Test
    void generatesImagesFromStoryboardScenes() throws Exception {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getImage().setModel("gpt-image-2");
        properties.getMinio().setBucket("artverse-test");
        properties.getStorage().setRoot(Files.createTempDirectory("artverse-manga-test-").toString());

        Story story = new Story();
        story.setId(3L);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setImageCount(1);
        chapter.setColorMode(ColorMode.BW);
        chapter.setNovelContent("The protagonist opens the door on a rainy night and sees city lights.");
        chapter.setScenesText(STORYBOARD_SCENE);

        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        CharacterProfileService characterProfileService = mock(CharacterProfileService.class);
        StoryAssetGroupRepository storyAssetGroupRepository = mock(StoryAssetGroupRepository.class);
        CapturingImage2Client image2Client = new CapturingImage2Client();

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));
        when(mangaImageRepository.findByChapterIdAndImageNumber(7L, 1)).thenReturn(Optional.empty());
        when(mangaImageRepository.save(any(MangaImage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mangaImageRepository.saveAndFlush(any(MangaImage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MangaImageStorageService imageStorageService = new MangaImageStorageService(
                mangaImageRepository,
                chapterRepository,
                new CapturingObjectStorage(),
                new MediaStorageService(properties),
                properties
        );

        MangaGenerationService service = new MangaGenerationService(
                chapterRepository,
                image2Client,
                imageStorageService,
                directExecutor(),
                characterProfileService,
                storyAssetGroupRepository,
                properties,
                new ObjectMapper()
        );

        service.generateMangaStream(7L, "image-key", null);

        assertThat(image2Client.awaitRequest()).isTrue();
        ImageGenerationRequest request = image2Client.request.get();
        assertThat(request.model()).isEqualTo("gpt-image-2");
        assertThat(request.prompt()).contains("Rainy alley");
    }

    @Test
    void preloadsAssetGroupCharactersBeforeGenerating() throws Exception {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getImage().setModel("gpt-image-2");
        properties.getMinio().setBucket("artverse-test");
        properties.getStorage().setRoot(Files.createTempDirectory("artverse-manga-test-").toString());

        Story story = new Story();
        story.setId(3L);
        StoryAssetGroup group = new StoryAssetGroup();
        group.setId(11L);
        group.setStory(story);
        group.setName("Main cast");
        CharacterProfile character = new CharacterProfile();
        character.setId(21L);
        character.setName("Mika");
        character.setDescription("Short hair, red coat");
        group.getCharacters().add(character);

        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setAssetGroup(group);
        chapter.setImageCount(1);
        chapter.setColorMode(ColorMode.BW);
        chapter.setNovelContent("The protagonist opens the door on a rainy night and sees city lights.");
        chapter.setScenesText(STORYBOARD_SCENE);

        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        CharacterProfileService characterProfileService = mock(CharacterProfileService.class);
        StoryAssetGroupRepository storyAssetGroupRepository = mock(StoryAssetGroupRepository.class);
        CapturingImage2Client image2Client = new CapturingImage2Client();

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));
        when(storyAssetGroupRepository.findByIdAndUserIdWithCharacters(eq(11L), eq(99L))).thenReturn(Optional.of(group));
        when(mangaImageRepository.findByChapterIdAndImageNumber(7L, 1)).thenReturn(Optional.empty());
        when(mangaImageRepository.save(any(MangaImage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mangaImageRepository.saveAndFlush(any(MangaImage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(characterProfileService.resolveEffective(7L)).thenReturn(Map.of("content", ""));

        MangaImageStorageService imageStorageService = new MangaImageStorageService(
                mangaImageRepository,
                chapterRepository,
                new CapturingObjectStorage(),
                new MediaStorageService(properties),
                properties
        );

        MangaGenerationService service = new MangaGenerationService(
                chapterRepository,
                image2Client,
                imageStorageService,
                directExecutor(),
                characterProfileService,
                storyAssetGroupRepository,
                properties,
                new ObjectMapper()
        );

        service.generateMangaStream(7L, 11L, 99L, "image-key", null, () -> {}, error -> {});

        assertThat(image2Client.awaitRequest()).isTrue();
        ImageGenerationRequest request = image2Client.request.get();
        assertThat(request.prompt()).contains("Asset group: Main cast");
        assertThat(request.prompt()).contains("Character: Mika");
        verify(storyAssetGroupRepository).findByIdAndUserIdWithCharacters(eq(11L), eq(99L));
    }

    @Test
    void wrapsImageGenerationClientFailure() throws Exception {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getImage().setModel("gpt-image-2");
        properties.getMinio().setBucket("artverse-test");
        properties.getStorage().setRoot(Files.createTempDirectory("artverse-manga-test-").toString());

        Story story = new Story();
        story.setId(3L);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setImageCount(1);
        chapter.setColorMode(ColorMode.BW);
        chapter.setNovelContent("The protagonist opens the door on a rainy night and sees city lights.");
        chapter.setScenesText(STORYBOARD_SCENE);

        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        CharacterProfileService characterProfileService = mock(CharacterProfileService.class);
        StoryAssetGroupRepository storyAssetGroupRepository = mock(StoryAssetGroupRepository.class);

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));
        when(mangaImageRepository.findByChapterIdAndImageNumber(7L, 1)).thenReturn(Optional.empty());
        when(characterProfileService.resolveEffective(7L)).thenReturn(Map.of("content", ""));

        MangaImageStorageService imageStorageService = new MangaImageStorageService(
                mangaImageRepository,
                chapterRepository,
                new CapturingObjectStorage(),
                new MediaStorageService(properties),
                properties
        );

        MangaGenerationService service = new MangaGenerationService(
                chapterRepository,
                (request, apiKey) -> Mono.error(new IllegalStateException("downstream unavailable")),
                imageStorageService,
                directExecutor(),
                characterProfileService,
                storyAssetGroupRepository,
                properties,
                new ObjectMapper()
        );

        assertThatThrownBy(() -> service.generateImageForJob(List.of(), "image-key", "test prompt", "bw"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Image generation timed out or failed");
    }

    private static ExecutorService directExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    private static class CapturingImage2Client implements Image2Client {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<ImageGenerationRequest> request = new AtomicReference<>();

        @Override
        public Mono<GeneratedImage> generate(ImageGenerationRequest request, String apiKey) {
            this.request.set(request);
            latch.countDown();
            try {
                Path dir = Files.createTempDirectory("artverse-generated-");
                Path file = dir.resolve("panel.png");
                Files.write(file, new byte[] {1, 2, 3});
                return Mono.just(new GeneratedImage(file, "image/png", Files.size(file)));
            } catch (Exception e) {
                return Mono.error(e);
            }
        }

        boolean awaitRequest() throws InterruptedException {
            return latch.await(2, TimeUnit.SECONDS);
        }
    }

    private static class CapturingObjectStorage implements ObjectStorageService {
        @Override
        public StoredObject putPng(String objectKey, Path localFile, String contentType) {
            try {
                return new StoredObject("artverse-test", objectKey, contentType, Files.size(localFile));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public InputStream get(String bucket, String objectKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public List<StoredObject> list(String bucket, String prefix, int limit) {
            return List.of();
        }

        @Override
        public Optional<URI> publicOrPresignedUrl(String bucket, String objectKey, Duration ttl) {
            return Optional.empty();
        }

        @Override
        public void deleteBestEffort(String bucket, String objectKey) {
        }
    }
}
