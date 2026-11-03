package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.ScheduledMangaGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chapters/{chapterId}/scheduled-manga")
@RequiredArgsConstructor
public class ScheduledMangaGenerationController {
    private final ScheduledMangaGenerationService service;
    private final CurrentUserService currentUser;

    @PostMapping
    public Map<String,Object> create(@PathVariable Long chapterId,@RequestHeader("Idempotency-Key") String key,@RequestBody CreateRequest body){
        return service.create(currentUser.requireCurrentUser().getId(),chapterId,body.localTime(),body.timezone(),body.model(),body.configId(),key);
    }
    @GetMapping("/{taskId}") public Map<String,Object> get(@PathVariable Long chapterId,@PathVariable UUID taskId){return service.get(currentUser.requireCurrentUser().getId(),chapterId,taskId);}
    @GetMapping(value="/{taskId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long chapterId,@PathVariable UUID taskId){return service.streamProgress(currentUser.requireCurrentUser().getId(),chapterId,taskId);}
    @DeleteMapping("/{taskId}") public Map<String,Object> cancel(@PathVariable Long chapterId,@PathVariable UUID taskId){return service.cancel(currentUser.requireCurrentUser().getId(),chapterId,taskId);}
    @PostMapping("/{taskId}/retry") public Map<String,Object> retry(@PathVariable Long chapterId,@PathVariable UUID taskId){return service.retryFailed(currentUser.requireCurrentUser().getId(),chapterId,taskId);}

    public record CreateRequest(LocalDateTime localTime,String timezone,String model,Long configId) {}
}
