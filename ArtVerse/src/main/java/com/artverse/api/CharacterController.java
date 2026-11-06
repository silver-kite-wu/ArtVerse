package com.artverse.api;

import com.artverse.application.CharacterProfileService;
import com.artverse.domain.CharacterProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterProfileService characterProfileService;

    // ===== Individual character profile CRUD =====

    @GetMapping("/stories/{storyId}/characters")
    public List<CharacterProfile> listByStory(@PathVariable Long storyId) {
        return characterProfileService.listByStory(storyId);
    }

    @PostMapping("/stories/{storyId}/characters")
    public CharacterProfile create(@PathVariable Long storyId, @RequestBody Map<String, String> body) {
        return characterProfileService.create(storyId, body.get("name"), body.get("description"));
    }

    @PutMapping("/stories/{storyId}/characters/{characterId}")
    public CharacterProfile update(@PathVariable Long storyId, @PathVariable Long characterId,
                                   @RequestBody Map<String, String> body) {
        return characterProfileService.update(characterId, body.get("name"), body.get("description"));
    }

    @DeleteMapping("/stories/{storyId}/characters/{characterId}")
    public ResponseEntity<Void> delete(@PathVariable Long storyId, @PathVariable Long characterId) {
        characterProfileService.delete(storyId, characterId);
        return ResponseEntity.noContent().build();
    }

    // ===== Character ref images =====

    @GetMapping("/stories/{storyId}/characters/{characterId}/ref-images")
    public List<Map<String, Object>> listRefImages(@PathVariable Long storyId, @PathVariable Long characterId) {
        return characterProfileService.listRefImages(storyId, characterId);
    }

    @PostMapping("/stories/{storyId}/characters/{characterId}/ref-images")
    public Map<String, Object> addRefImage(@PathVariable Long storyId, @PathVariable Long characterId,
                                           @RequestBody Map<String, String> body) {
        return characterProfileService.addRefImage(storyId, characterId, body.get("image"));
    }

    @DeleteMapping("/stories/{storyId}/characters/{characterId}/ref-images/{filename}")
    public ResponseEntity<Void> deleteRefImage(@PathVariable Long storyId, @PathVariable Long characterId,
                                               @PathVariable String filename) {
        characterProfileService.deleteRefImage(storyId, characterId, filename);
        return ResponseEntity.noContent().build();
    }

}