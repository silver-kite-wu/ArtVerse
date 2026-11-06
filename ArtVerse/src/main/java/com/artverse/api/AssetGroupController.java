package com.artverse.api;

import com.artverse.application.AssetGroupService;
import com.artverse.domain.Chapter;
import com.artverse.domain.CharacterProfile;
import com.artverse.domain.StoryAssetGroup;
import com.artverse.persistence.ChapterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AssetGroupController {

    private final AssetGroupService assetGroupService;
    private final ChapterRepository chapterRepository;

    @GetMapping("/stories/{storyId}/asset-groups")
    public List<Map<String, Object>> listByStory(@PathVariable Long storyId) {
        return assetGroupService.listByStory(storyId).stream()
                .map(this::toGroupMap)
                .toList();
    }

    @PostMapping("/stories/{storyId}/asset-groups")
    public Map<String, Object> create(@PathVariable Long storyId, @RequestBody Map<String, Object> body) {
        String name = body.get("name") != null ? body.get("name").toString() : null;
        String description = body.get("description") != null ? body.get("description").toString() : null;
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("characterIds");
        List<Long> characterIds = rawIds != null ? rawIds.stream().map(Integer::longValue).toList() : List.of();
        StoryAssetGroup group = assetGroupService.create(storyId, name, description, characterIds);
        return toGroupMap(group);
    }

    @PutMapping("/asset-groups/{groupId}")
    public Map<String, Object> update(@PathVariable Long groupId, @RequestBody Map<String, Object> body) {
        String name = body.get("name") != null ? body.get("name").toString() : null;
        String description = body.get("description") != null ? body.get("description").toString() : null;
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("characterIds");
        List<Long> characterIds = rawIds != null ? rawIds.stream().map(Integer::longValue).toList() : null;
        StoryAssetGroup group = assetGroupService.update(groupId, name, description, characterIds);
        return toGroupMap(group);
    }

    @DeleteMapping("/asset-groups/{groupId}")
    public ResponseEntity<Void> delete(@PathVariable Long groupId) {
        assetGroupService.delete(groupId);
        return ResponseEntity.noContent().build();
    }

    @Transactional(readOnly = true)
    @GetMapping("/chapters/{chapterId}/asset-group")
    public Map<String, Object> getChapterAssetGroup(@PathVariable Long chapterId) {
        return assetGroupService.getChapterAssetGroupData(chapterId);
    }

    @Transactional
    @PutMapping("/chapters/{chapterId}/asset-group")
    public Map<String, Object> setChapterAssetGroup(@PathVariable Long chapterId, @RequestBody Map<String, Object> body) {
        Long groupId = body.get("group_id") != null ? ((Number) body.get("group_id")).longValue() : null;
        assetGroupService.setChapterAssetGroup(chapterId, groupId);
        return assetGroupService.getChapterAssetGroupData(chapterId);
    }

    private Map<String, Object> toGroupMap(StoryAssetGroup group) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", group.getId());
        map.put("name", group.getName());
        map.put("description", group.getDescription());
        List<Map<String, Object>> chars = group.getCharacters().stream()
                .map(cp -> {
                    Map<String, Object> cm = new HashMap<>();
                    cm.put("id", cp.getId());
                    cm.put("name", cp.getName());
                    cm.put("description", cp.getDescription());
                    return cm;
                })
                .toList();
        map.put("characters", chars);
        return map;
    }
}
