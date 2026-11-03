package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.ScheduledMangaGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stories/{storyId}/scheduled-manga")
@RequiredArgsConstructor
public class ScheduledMangaBatchController {
    private final ScheduledMangaGenerationService service;
    private final CurrentUserService currentUser;

    @PostMapping
    public Map<String,Object> create(@PathVariable Long storyId,
                                     @RequestHeader("Idempotency-Key") String key,
                                     @RequestBody CreateRequest body) {
        return service.createBatch(currentUser.requireCurrentUser().getId(),storyId,null,body.chapterNumbers(),
                body.localTime(),body.timezone(),body.model(),body.configId(),key);
    }
    public record CreateRequest(List<Integer> chapterNumbers,LocalDateTime localTime,String timezone,String model,Long configId) {}
}
