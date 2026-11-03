package com.artverse.application;

import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledMangaGenerationServiceTest {
    @Test
    void rejectsAmbiguousDaylightSavingLocalTimeBeforePersisting() {
        var tasks=mock(ScheduledMangaGenerationRepository.class);
        var service=new ScheduledMangaGenerationService(tasks,mock(ScheduledMangaPanelRepository.class),mock(ChapterRepository.class),mock(UserRepository.class),mock(MangaImageRepository.class),mock(ApiKeyService.class),mock(MangaGenerationService.class),mock(MangaImageStorageService.class),mock(ScheduledMangaWorkerSlotService.class),new com.artverse.config.ArtVerseProperties(),new ObjectMapper());
        var error=assertThrows(com.artverse.common.BusinessException.class,()->service.create(4L,9L,LocalDateTime.of(2026,10,25,2,30),"Europe/Paris",null,null,"dst-key"));
        assertTrue(error.getMessage().contains("ambiguous"));
        verify(tasks,never()).insertIfAbsent(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void persistsOneTimeTaskAndOnlyMissingPanelsWithRequestIdempotency() {
        var tasks=mock(ScheduledMangaGenerationRepository.class);
        var panels=mock(ScheduledMangaPanelRepository.class);
        var chapters=mock(ChapterRepository.class);
        var users=mock(UserRepository.class);
        var images=mock(MangaImageRepository.class);
        var keys=mock(ApiKeyService.class);
        var generation=mock(MangaGenerationService.class);
        var imageStorage=mock(MangaImageStorageService.class);
        var workerSlots=mock(ScheduledMangaWorkerSlotService.class);
        var properties=new com.artverse.config.ArtVerseProperties();
        var mapper=new ObjectMapper();
        var service=new ScheduledMangaGenerationService(tasks,panels,chapters,users,images,keys,generation,imageStorage,workerSlots,properties,mapper);
        var chapter=new Chapter(); chapter.setId(9L); chapter.setVersion(3L); chapter.setImageCount(2); chapter.setScenesText("[\"panel one\",\"panel two\"]");
        var user=new User(); user.setId(4L); var story=new Story(); story.setId(14L); story.setUser(user); chapter.setStory(story);
        var persistedTask=new ScheduledMangaGeneration(); persistedTask.setId(java.util.UUID.randomUUID());
        persistedTask.setState(ScheduledMangaGeneration.State.SCHEDULED); persistedTask.setRequestedAt(java.time.OffsetDateTime.now().plusDays(1));
        persistedTask.setTimezoneId("Asia/Shanghai"); persistedTask.setChapter(chapter);
        when(tasks.findByUserIdAndIdempotencyKey(4L,"request-1")).thenReturn(Optional.empty(),Optional.of(persistedTask));
        when(chapters.findByIdForIdempotencyAndUserId(9L,4L)).thenReturn(Optional.of(chapter));
        when(tasks.existsByChapterIdAndActive(9L)).thenReturn(false);
        when(users.findByIdForScheduledTaskCreation(4L)).thenReturn(Optional.of(user));
        when(keys.requireActiveUserProviderConfig(user,ApiKeyService.SLOT_IMAGE,"Configure an active image provider before scheduling"))
                .thenReturn(new UserProviderConfig(ApiKeyService.SLOT_IMAGE,"p","label","secret","https://provider","model",7L));
        when(images.findByChapterIdAndImageNumber(9L,1)).thenReturn(Optional.of(new MangaImage()));
        when(images.findByChapterIdAndImageNumber(9L,2)).thenReturn(Optional.empty());
        when(generation.scheduledGenerationProfiles(9L,4L)).thenReturn("Character: Mira");
        when(imageStorage.previewReferenceImageKeys(14L,9L,null,null,null)).thenReturn(java.util.List.of());
        when(tasks.insertIfAbsent(any(),eq(4L),eq(9L),any(),eq("Asia/Shanghai"),eq("SCHEDULED"),eq(3L),eq(7L),eq("model"),anyString(),anyString(),anyString(),eq("manga-image-prompt-v1"),eq("request-1"))).thenReturn(1);
        var savedPanels=new java.util.ArrayList<ScheduledMangaPanel>();
        when(panels.save(any())).thenAnswer(inv->{var panel=(ScheduledMangaPanel)inv.getArgument(0);savedPanels.add(panel);return panel;});
        when(panels.findByTaskIdOrderByImageNumberAsc(any())).thenAnswer(inv->java.util.List.copyOf(savedPanels));

        var result=service.create(4L,9L,LocalDateTime.now().plusDays(1),"Asia/Shanghai",null,null,"request-1");

        assertEquals("SCHEDULED",result.get("state"));
        assertEquals(1,result.get("panel_count"));
        verify(panels).save(argThat(panel -> panel.getImageNumber()==2 && panel.getPromptSnapshot().equals("panel two")));
        verify(tasks).insertIfAbsent(any(),eq(4L),eq(9L),any(),eq("Asia/Shanghai"),eq("SCHEDULED"),eq(3L),eq(7L),eq("model"),argThat(scenes->scenes.contains("panel one")),anyString(),anyString(),eq("manga-image-prompt-v1"),eq("request-1"));
    }

    @Test
    void repeatedRequestReturnsThePersistedTaskWithoutCreatingAnother() {
        var tasks=mock(ScheduledMangaGenerationRepository.class);
        var panels=mock(ScheduledMangaPanelRepository.class);
        var task=new ScheduledMangaGeneration(); task.setId(java.util.UUID.randomUUID()); task.setState(ScheduledMangaGeneration.State.SCHEDULED);
        task.setRequestedAt(java.time.OffsetDateTime.now().plusDays(1)); task.setTimezoneId("UTC"); task.setChapter(new Chapter()); task.getChapter().setId(9L);
        when(tasks.findByUserIdAndIdempotencyKey(4L,"same-key")).thenReturn(Optional.of(task));
        when(panels.findByTaskIdOrderByImageNumberAsc(task.getId())).thenReturn(java.util.List.of());
        var service=new ScheduledMangaGenerationService(tasks,panels,mock(ChapterRepository.class),mock(UserRepository.class),mock(MangaImageRepository.class),mock(ApiKeyService.class),mock(MangaGenerationService.class),mock(MangaImageStorageService.class),mock(ScheduledMangaWorkerSlotService.class),new com.artverse.config.ArtVerseProperties(),new ObjectMapper());
        assertEquals(task.getId().toString(),service.create(4L,9L,LocalDateTime.now().minusDays(1),"UTC",null,null,"same-key").get("task_id"));
        verify(tasks,never()).insertIfAbsent(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void rejectsReusingAnIdempotencyKeyForAnotherChapter() {
        var tasks=mock(ScheduledMangaGenerationRepository.class);
        var task=new ScheduledMangaGeneration(); task.setId(java.util.UUID.randomUUID()); task.setState(ScheduledMangaGeneration.State.SCHEDULED);
        task.setRequestedAt(java.time.OffsetDateTime.now().plusDays(1)); task.setTimezoneId("UTC"); task.setChapter(new Chapter()); task.getChapter().setId(9L);
        when(tasks.findByUserIdAndIdempotencyKey(4L,"same-key")).thenReturn(Optional.of(task));
        var service=new ScheduledMangaGenerationService(tasks,mock(ScheduledMangaPanelRepository.class),mock(ChapterRepository.class),mock(UserRepository.class),mock(MangaImageRepository.class),mock(ApiKeyService.class),mock(MangaGenerationService.class),mock(MangaImageStorageService.class),mock(ScheduledMangaWorkerSlotService.class),new com.artverse.config.ArtVerseProperties(),new ObjectMapper());

        var error=assertThrows(com.artverse.common.BusinessException.class,()->service.create(4L,10L,LocalDateTime.now().plusDays(1),"UTC",null,null,"same-key"));

        assertEquals(409,error.getStatus());
        verify(tasks,never()).insertIfAbsent(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void cancellationResponseReflectsThePersistedCancelledState() {
        var tasks=mock(ScheduledMangaGenerationRepository.class);
        var panels=mock(ScheduledMangaPanelRepository.class);
        var task=new ScheduledMangaGeneration(); task.setId(java.util.UUID.randomUUID()); task.setState(ScheduledMangaGeneration.State.SCHEDULED);
        task.setRequestedAt(java.time.OffsetDateTime.now().plusDays(1)); task.setTimezoneId("UTC"); task.setChapter(new Chapter()); task.getChapter().setId(9L);
        when(tasks.findByIdAndUserId(task.getId(),4L)).thenReturn(Optional.of(task));
        when(tasks.cancelIfScheduled(eq(task.getId()),any())).thenReturn(1);
        when(panels.findByTaskIdOrderByImageNumberAsc(task.getId())).thenReturn(java.util.List.of());
        var service=new ScheduledMangaGenerationService(tasks,panels,mock(ChapterRepository.class),mock(UserRepository.class),mock(MangaImageRepository.class),mock(ApiKeyService.class),mock(MangaGenerationService.class),mock(MangaImageStorageService.class),mock(ScheduledMangaWorkerSlotService.class),new com.artverse.config.ArtVerseProperties(),new ObjectMapper());

        var result=service.cancel(4L,9L,task.getId());

        assertEquals("CANCELLED",result.get("state"));
    }

    @Test
    void retryRequeuesFailedPanelsWithoutTouchingSuccessfulPanels() {
        var tasks=mock(ScheduledMangaGenerationRepository.class);
        var panels=mock(ScheduledMangaPanelRepository.class);
        var users=mock(UserRepository.class);
        var task=new ScheduledMangaGeneration(); task.setId(java.util.UUID.randomUUID()); task.setState(ScheduledMangaGeneration.State.PARTIAL_FAILED);
        task.setRequestedAt(java.time.OffsetDateTime.now().minusDays(1)); task.setCompletedAt(java.time.OffsetDateTime.now()); task.setTimezoneId("UTC");
        task.setChapter(new Chapter()); task.getChapter().setId(9L);
        when(tasks.findByIdAndUserId(task.getId(),4L)).thenReturn(Optional.of(task));
        when(panels.countByTaskIdAndState(task.getId(),ScheduledMangaPanel.State.FAILED)).thenReturn(1L);
        when(users.findByIdForScheduledTaskCreation(4L)).thenReturn(Optional.of(new User()));
        when(tasks.countActiveForUser(4L)).thenReturn(0L);
        when(tasks.existsByChapterIdAndActive(9L)).thenReturn(false);
        when(tasks.retryFailedIfFinal(eq(task.getId()),eq(4L),any())).thenReturn(1);
        when(panels.resetFailedForRetry(task.getId())).thenReturn(1);
        when(panels.findByTaskIdOrderByImageNumberAsc(task.getId())).thenReturn(java.util.List.of());
        var service=new ScheduledMangaGenerationService(tasks,panels,mock(ChapterRepository.class),users,mock(MangaImageRepository.class),mock(ApiKeyService.class),mock(MangaGenerationService.class),mock(MangaImageStorageService.class),mock(ScheduledMangaWorkerSlotService.class),new com.artverse.config.ArtVerseProperties(),new ObjectMapper());

        var result=service.retryFailed(4L,9L,task.getId());

        assertEquals("SCHEDULED",result.get("state"));
        verify(panels).resetFailedForRetry(task.getId());
    }
}
