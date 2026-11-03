package com.artverse.application;

import com.artverse.ai.GeneratedImage;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaImageRepository;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MangaImageStorageServiceTest {
    private final MangaImageRepository images = mock(MangaImageRepository.class);
    private final ChapterRepository chapters = mock(ChapterRepository.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ArtVerseProperties properties = new ArtVerseProperties();
    private final MangaImageStorageService service = new MangaImageStorageService(
            images, chapters, storage, new MediaStorageService(properties), properties);

    @Test
    void savesJpegStreamAndPersistsActualMetadata() {
        byte[] original = {1, 2, 3};
        when(storage.put(anyString(), any(InputStream.class), eq(3L), eq("image/jpeg"))).thenAnswer(call -> {
            String key = call.getArgument(0);
            assertThat(key).startsWith("stories/3/chapters/7/panels/").endsWith(".jpg");
            assertThat(((InputStream) call.getArgument(1)).readAllBytes()).isEqualTo(original);
            return new StoredObject("test-bucket", key, "image/jpeg", 3);
        });
        when(images.findByChapterIdAndImageNumber(7L, 1)).thenReturn(Optional.empty());
        when(chapters.getReferenceById(7L)).thenReturn(new Chapter());
        when(images.saveAndFlush(any(MangaImage.class))).thenAnswer(call -> call.getArgument(0));

        MangaImage result = service.saveGeneratedPanel(7L, 3L, 1, new GeneratedImage(original, "image/jpeg"), "prompt");

        assertThat(result.getContentType()).isEqualTo("image/jpeg");
        assertThat(result.getSizeBytes()).isEqualTo(3L);
        assertThat(result.getImagePath()).endsWith(".jpg");
        verify(storage, never()).putPng(anyString(), any(), anyString());
    }

    @Test
    void uploadFailureDoesNotSaveOrDeleteExistingImage() {
        when(storage.put(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("MinIO unavailable"));

        assertThatThrownBy(() -> service.saveGeneratedPanel(7L, 3L, 1,
                new GeneratedImage(new byte[] {1}, "image/png"), "prompt"))
                .isInstanceOf(IllegalStateException.class).hasMessage("MinIO unavailable");

        verifyNoInteractions(images, chapters);
        verify(storage, never()).deleteBestEffort(anyString(), anyString());
    }
}
