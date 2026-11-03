package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.ScheduledMangaGenerationService;
import com.artverse.domain.User;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ScheduledMangaGenerationControllerTest {
    @Test
    void createTakesIdentityFromCurrentUserAndForwardsTheRequest() {
        var service=mock(ScheduledMangaGenerationService.class);
        var currentUser=mock(CurrentUserService.class);
        var user=new User(); user.setId(55L);
        when(currentUser.requireCurrentUser()).thenReturn(user);
        var controller=new ScheduledMangaGenerationController(service,currentUser);
        var when=LocalDateTime.of(2026,11,1,0,0);

        controller.create(9L,"idem-key",new ScheduledMangaGenerationController.CreateRequest(when,"Asia/Shanghai",null,null));

        verify(service).create(55L,9L,when,"Asia/Shanghai",null,null,"idem-key");
        assertFalse(java.util.Arrays.stream(ScheduledMangaGenerationController.CreateRequest.class.getRecordComponents())
                .anyMatch(component->component.getName().equalsIgnoreCase("userId")));
    }

    @Test
    void retryIsScopedToAuthenticatedUserAndChapter() {
        var service=mock(ScheduledMangaGenerationService.class);
        var currentUser=mock(CurrentUserService.class);
        var user=new User(); user.setId(55L);
        when(currentUser.requireCurrentUser()).thenReturn(user);
        var controller=new ScheduledMangaGenerationController(service,currentUser);
        var taskId=java.util.UUID.randomUUID();

        controller.retry(9L,taskId);

        verify(service).retryFailed(55L,9L,taskId);
    }

    @Test
    void progressStreamUsesTheAuthenticatedTaskScope() {
        var service=mock(ScheduledMangaGenerationService.class);
        var currentUser=mock(CurrentUserService.class);
        var user=new User(); user.setId(55L);
        when(currentUser.requireCurrentUser()).thenReturn(user);
        var taskId=java.util.UUID.randomUUID();
        var emitter=new SseEmitter();
        when(service.streamProgress(55L,9L,taskId)).thenReturn(emitter);
        var controller=new ScheduledMangaGenerationController(service,currentUser);

        var actual=controller.stream(9L,taskId);

        org.junit.jupiter.api.Assertions.assertSame(emitter,actual);
        verify(service).streamProgress(55L,9L,taskId);
    }
}
