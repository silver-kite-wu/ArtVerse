package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.CharacterProfile;
import com.artverse.domain.Story;
import com.artverse.domain.StoryAssetGroup;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.CharacterProfileRepository;
import com.artverse.persistence.StoryAssetGroupRepository;
import com.artverse.persistence.StoryRepository;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CharacterProfileService {

    private final CharacterProfileRepository profileRepository;
    private final StoryRepository storyRepository;
    private final StoryAssetGroupRepository assetGroupRepository;
    private final ChapterRepository chapterRepository;
    private final MediaStorageService mediaStorageService;
    private final ObjectStorageService objectStorageService;
    private final ArtVerseProperties properties;

    @Transactional(readOnly = true)
    public List<CharacterProfile> listByStory(Long storyId) {
        Long userId = currentUserId();
        storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
        return profileRepository.findByStoryIdAndUserIdOrderByIdAsc(storyId, userId);
    }

    @Transactional(readOnly = true)
    public CharacterProfile getRequired(Long id) {
        Long userId = currentUserId();
        return profileRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Character profile not found"));
    }

    @Transactional
    public CharacterProfile create(Long storyId, String name, String description) {
        Long userId = currentUserId();
        Story story = storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
        CharacterProfile profile = new CharacterProfile();
        profile.setStory(story);
        profile.setName(name != null ? name : "");
        profile.setDescription(description != null ? description : "");
        return profileRepository.save(profile);
    }

    @Transactional
    public CharacterProfile update(Long id, String name, String description) {
        CharacterProfile profile = getRequired(id);
        if (name != null) profile.setName(name);
        if (description != null) profile.setDescription(description);
        return profileRepository.save(profile);
    }

    @Transactional
    public void delete(Long storyId, Long id) {
        Long userId = currentUserId();
        storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
        CharacterProfile profile = getRequired(id);
        if (!profile.getStory().getId().equals(storyId)) {
            throw new BusinessException(400, "Character does not belong to this story");
        }
        // Delete all ref images from MinIO
        String prefix = refImagePrefix(storyId, id);
        objectStorageService.list(properties.getMinio().getBucket(), prefix, 100).stream()
                .filter(o -> isImageObject(o.objectKey()))
                .forEach(o -> objectStorageService.deleteBestEffort(properties.getMinio().getBucket(), o.objectKey()));
        profileRepository.delete(profile);
    }

    // --- Ref images (stored in MinIO) ---
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRefImages(Long storyId, Long characterId) {
        CharacterProfile profile = getRequired(characterId);
        if (!profile.getStory().getId().equals(storyId)) {
            throw new BusinessException(400, "Character does not belong to this story");
        }
        String prefix = refImagePrefix(storyId, characterId);
        return objectStorageService.list(properties.getMinio().getBucket(), prefix, 100).stream()
                .filter(o -> isImageObject(o.objectKey()))
                .sorted(Comparator.comparing(StoredObject::objectKey))
                .map(o -> Map.<String, Object>of(
                        "filename", Path.of(o.objectKey()).getFileName().toString(),
                        "object_key", o.objectKey(),
                        "size_kb", o.sizeBytes() / 1024
                ))
                .toList();
    }

    @Transactional
    public Map<String, Object> addRefImage(Long storyId, Long characterId, String base64) {
        CharacterProfile profile = getRequired(characterId);
        if (!profile.getStory().getId().equals(storyId)) {
            throw new BusinessException(400, "Character does not belong to this story");
        }

        // Max 5 ref images per character
        String prefix = refImagePrefix(storyId, characterId);
        long count = objectStorageService.list(properties.getMinio().getBucket(), prefix, 100).stream()
                .filter(o -> {
                    String name = o.objectKey().toLowerCase();
                    return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
                })
                .count();
        if (count >= 5) {
            throw new BusinessException(400, "Max 5 reference images per character");
        }

        byte[] imageData = mediaStorageService.decodeBase64Image(base64);
        mediaStorageService.validateImageBytes(imageData, properties.getUpload().getMaxImageBytes());

        String filename = mediaStorageService.generateUniqueFilename("char_ref", ".png");
        String objectKey = prefix + filename;

        Path temp = null;
        try {
            temp = Files.createTempFile("artverse-char-ref-", ".png");
            mediaStorageService.savePng(imageData, temp);
            objectStorageService.putPng(objectKey, temp, "image/png");
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload character ref image: " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
            }
        }

        return Map.of("filename", filename, "object_key", objectKey);
    }

    @Transactional
    public void deleteRefImage(Long storyId, Long characterId, String filename) {
        CharacterProfile profile = getRequired(characterId);
        if (!profile.getStory().getId().equals(storyId)) {
            throw new BusinessException(400, "Character does not belong to this story");
        }
        mediaStorageService.validateImagePath(filename);
        String safeFilename = Path.of(filename).getFileName().toString();
        String objectKey = refImagePrefix(storyId, characterId) + safeFilename;
        objectStorageService.deleteBestEffort(properties.getMinio().getBucket(), objectKey);
    }

    private String refImagePrefix(Long storyId, Long characterId) {
        return "stories/" + storyId + "/characters/" + characterId + "/ref_images/";
    }

    private boolean isImageObject(String objectKey) {
        String name = objectKey.toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }


    // ===== Legacy resolveEffective for manga generation compatibility =====

    @Transactional(readOnly = true)
    public Map<String, Object> resolveEffective(Long chapterId) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        // Collect character profiles from asset group's characters
        if (chapter.getAssetGroup() != null) {
            Set<CharacterProfile> characters = chapter.getAssetGroup().getCharacters();
            if (characters != null && !characters.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (CharacterProfile cp : characters) {
                    sb.append("角色名：").append(cp.getName()).append("\n");
                    if (cp.getDescription() != null && !cp.getDescription().isBlank()) {
                        sb.append("描述：").append(cp.getDescription()).append("\n");
                    }
                    sb.append("\n");
                }
                String content = sb.toString().trim();
                if (!content.isEmpty()) {
                    return Map.of("content", content, "source", "asset_group");
                }
            }
        }

        // Fallback to story-level character profiles text
        Story story = chapter.getStory();
        if (story.getCharacterProfiles() != null && !story.getCharacterProfiles().isBlank()) {
            return Map.of("content", story.getCharacterProfiles(), "source", "story");
        }

        return Map.of("content", "", "source", "none");
    }    // ===== Asset group character association =====

    @Transactional(readOnly = true)
    public List<CharacterProfile> listByAssetGroup(Long groupId) {
        Long userId = currentUserId();
        StoryAssetGroup group = assetGroupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
        return List.copyOf(group.getCharacters());
    }

    @Transactional
    public void setAssetGroupCharacters(Long groupId, List<Long> characterIds) {
        Long userId = currentUserId();
        StoryAssetGroup group = assetGroupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
        Set<CharacterProfile> profiles = new LinkedHashSet<>();
        for (Long cid : characterIds) {
            CharacterProfile profile = profileRepository.findByIdAndUserId(cid, userId)
                    .orElseThrow(() -> new BusinessException(404, "Character profile not found: " + cid));
            if (!profile.getStory().getId().equals(group.getStory().getId())) {
                throw new BusinessException(400, "Character does not belong to the same story");
            }
            profiles.add(profile);
        }
        group.setCharacters(profiles);
        assetGroupRepository.save(group);
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
