package com.artverse.application;

import com.artverse.ai.GeneratedImage;
import com.artverse.ai.Image2Client;
import com.artverse.ai.ImageGenerationRequest;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.ImageGenRecord;
import com.artverse.domain.ImageGenStatus;
import com.artverse.domain.User;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ImageGenRecordRepository;
import com.artverse.persistence.UserRepository;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageGenServiceTest {
    private final ImageGenRecordRepository records = mock(ImageGenRecordRepository.class);
    private final Image2Client provider = mock(Image2Client.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ArtVerseProperties properties = new ArtVerseProperties();
    private final ImageGenService service = new ImageGenService(records, mock(UserRepository.class), provider,
            new MediaStorageService(properties), storage, properties, mock(TransactionTemplate.class),
            mock(AiConversationService.class), mock(ExecutorService.class));
    private final UserProviderConfig config = new UserProviderConfig("image", "image2", "test", "test-key", "https://example.test/v1", "test");

    @Test
    void marksOrdinaryImageSuccessfulAfterStreamUpload() {
        ImageGenRecord record = runningRecord();
        byte[] original = {1, 2, 3};
        when(provider.generate(any(ImageGenerationRequest.class), eq(config)))
                .thenReturn(Mono.just(new GeneratedImage(original, "image/jpeg")));
        when(storage.put(anyString(), any(InputStream.class), eq(3L), eq("image/jpeg"))).thenAnswer(call -> {
            String key = call.getArgument(0);
            assertThat(key).startsWith("image_gen/5/").endsWith(".jpg");
            assertThat(((InputStream) call.getArgument(1)).readAllBytes()).isEqualTo(original);
            assertThat(record.getStatus()).isEqualTo(ImageGenStatus.RUNNING);
            return new StoredObject("test-bucket", key, "image/jpeg", 3);
        });

        service.generateInBackground(9L, List.of(), config);

        assertThat(record.getStatus()).isEqualTo(ImageGenStatus.SUCCEEDED);
        assertThat(record.getImagePath()).endsWith(".jpg");
        assertThat(record.getCompletedAt()).isNotNull();
        verify(records).save(record);
        verify(storage, never()).putPng(anyString(), any(), anyString());
    }

    @Test
    void streamUploadFailureDoesNotReportSuccessOrCallModelAgain() {
        ImageGenRecord record = runningRecord();
        when(provider.generate(any(ImageGenerationRequest.class), eq(config)))
                .thenReturn(Mono.just(new GeneratedImage(new byte[] {1}, "image/png")));
        when(storage.put(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("MinIO unavailable"));

        service.generateInBackground(9L, List.of(), config);

        assertThat(record.getStatus()).isEqualTo(ImageGenStatus.FAILED);
        assertThat(record.getImagePath()).isNull();
        verify(provider).generate(any(ImageGenerationRequest.class), eq(config));
    }

    private ImageGenRecord runningRecord() {
        User user = new User();
        user.setId(5L);
        ImageGenRecord record = new ImageGenRecord();
        record.setUser(user);
        record.setStatus(ImageGenStatus.RUNNING);
        record.setIsDeleted(false);
        record.setPrompt("test");
        record.setModel("test-model");
        record.setSize("1024x1024");
        when(records.findById(9L)).thenReturn(Optional.of(record));
        return record;
    }
}
