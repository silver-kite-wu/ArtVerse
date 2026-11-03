package com.artverse.api;

import com.artverse.application.ImageGenService;
import com.artverse.application.ApiKeyService;
import com.artverse.application.UserProviderConfig;
import com.artverse.application.CurrentUserService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/image-gen")
@RequiredArgsConstructor
public class ImageGenController {

    private final ImageGenService imageGenService;
    private final ApiKeyService apiKeyService;
    private final CurrentUserService currentUserService;

    private final ImageGenerationRequestController requests;

    @PostMapping("/generate")
    public ResponseEntity<Map<String,Object>> generate(@RequestBody Map<String,Object> body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return requests.submit(currentUser().getId(),null,null,"STANDALONE",body,key);
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        @RequestParam(required = false, name = "conversation_id") UUID conversationId) {
        return imageGenService.listHistory(page, size, conversationId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        imageGenService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        return currentUserService.requireCurrentUser();
    }

    private UserProviderConfig requestConfig(Map<String, Object> body) {
        return new UserProviderConfig(
                ApiKeyService.SLOT_IMAGE,
                stringValue(body.get("provider")),
                stringValue(body.get("label")),
                firstNonBlank(stringValue(body.get("api_key")), stringValue(body.get("apiKey"))),
                firstNonBlank(stringValue(body.get("base_url")), stringValue(body.get("baseUrl"))),
                stringValue(body.get("model"))
        );
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private Long configId(Map<String, Object> body) {
        Object value = body.get("config_id");
        if (value == null || value.toString().isBlank()) return null;
        return Long.valueOf(value.toString());
    }

    private UUID uuidValue(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try { return UUID.fromString(value.toString()); }
        catch (IllegalArgumentException e) { throw new com.artverse.common.BusinessException(400, "Invalid conversation_id"); }
    }
}
