package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.*;
import com.artverse.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportImportService {

    private final StoryRepository storyRepository;
    private final ChapterRepository chapterRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MangaImageRepository mangaImageRepository;
    private final StoryAssetGroupRepository assetGroupRepository;
    private final MediaStorageService mediaStorageService;
    private final ObjectStorageService objectStorageService;
    private final ArtVerseProperties properties;
    private final ObjectMapper objectMapper;

    public byte[] exportStory(Long storyId) {
        Long userId = currentUserId();
        Story story = storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Build manifest
            ObjectNode manifest = objectMapper.createObjectNode();
            manifest.put("format", "ArtVerse.story.export");
            manifest.put("version", 2);
            manifest.put("exported_at", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

            ObjectNode storyNode = manifest.putObject("story");
            storyNode.put("title", story.getTitle());
            storyNode.put("description", story.getDescription());
            storyNode.put("character_profiles", story.getCharacterProfiles());

            // Cover image
            if (story.getCoverImage() != null) {
                addFileToZip(zos, story.getCoverImage(), "assets/cover.png");
                storyNode.put("cover_image", "assets/cover.png");
            }

            // Asset groups
            ArrayNode groupsArray = storyNode.putArray("asset_groups");
            List<StoryAssetGroup> groups = assetGroupRepository.findByStoryIdOrderByIdAsc(storyId);
            for (StoryAssetGroup group : groups) {
                ObjectNode groupNode = groupsArray.addObject();
                groupNode.put("group_key", "group_" + group.getId());
                groupNode.put("name", group.getName());
                groupNode.put("character_profiles", group.getCharacterProfiles());
            }

            // Chapters
            ArrayNode chaptersArray = storyNode.putArray("chapters");
            List<Chapter> chapters = chapterRepository.findByStoryIdOrderByChapterNumberAsc(storyId);
            for (Chapter chapter : chapters) {
                ObjectNode chapterNode = chaptersArray.addObject();
                chapterNode.put("chapter_number", chapter.getChapterNumber());
                chapterNode.put("novel_content", chapter.getNovelContent());
                chapterNode.put("content_source", chapter.getContentSource() != null ? chapter.getContentSource().name().toLowerCase() : null);
                chapterNode.put("scenes_text", chapter.getScenesText());
                chapterNode.put("character_profiles", chapter.getCharacterProfiles());
                chapterNode.put("color_mode", chapter.getColorMode().name().toLowerCase());
                chapterNode.put("image_count", chapter.getImageCount());

                if (chapter.getAssetGroup() != null) {
                    chapterNode.put("asset_group_key", "group_" + chapter.getAssetGroup().getId());
                }

                // Chat messages
                ArrayNode messagesArray = chapterNode.putArray("messages");
                List<ChatMessage> messages = chatMessageRepository.findByChapterIdOrderByCreatedAtAsc(chapter.getId());
                for (ChatMessage msg : messages) {
                    ObjectNode msgNode = messagesArray.addObject();
                    msgNode.put("role", msg.getRole().name().toLowerCase());
                    msgNode.put("content", msg.getContent());
                }

                // Manga images
                ArrayNode imagesArray = chapterNode.putArray("images");
                List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(chapter.getId());
                for (MangaImage img : images) {
                    String assetPath = "assets/chapter_" + chapter.getChapterNumber() + "/panel_" + String.format("%02d", img.getImageNumber()) + ".png";
                    addImageToZip(zos, img, assetPath);
                    ObjectNode imgNode = imagesArray.addObject();
                    imgNode.put("image_number", img.getImageNumber());
                    imgNode.put("image_path", assetPath);
                    imgNode.put("prompt", img.getPrompt());
                }
            }

            // Write manifest
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zos.closeEntry();

        } catch (IOException e) {
            throw new RuntimeException("Failed to export story", e);
        }

        return baos.toByteArray();
    }

    @Transactional
    public Story importStory(byte[] zipData) {
        Map<String, byte[]> zipContents = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // Security: reject absolute paths and ..
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    throw new BusinessException(400, "Invalid zip entry name: " + name);
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                zis.transferTo(baos);
                zipContents.put(name, baos.toByteArray());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(400, "Failed to read zip file");
        }

        byte[] manifestBytes = zipContents.get("manifest.json");
        if (manifestBytes == null) {
            throw new BusinessException(400, "Missing manifest.json");
        }

        JsonNode manifest;
        try {
            manifest = objectMapper.readTree(manifestBytes);
        } catch (Exception e) {
            throw new BusinessException(400, "Invalid manifest.json");
        }

        String format = manifest.path("format").asText();
        if (!"ArtVerse.story.export".equals(format)) {
            throw new BusinessException(400, "Unsupported export format: " + format);
        }
        int version = manifest.path("version").asInt(0);
        if (version > 2) {
            throw new BusinessException(400, "Unsupported export version: " + version);
        }

        JsonNode storyNode = manifest.path("story");

        Story story = new Story();
        story.setTitle(storyNode.path("title").asText("Untitled"));
        story.setDescription(storyNode.path("description").asText(null));
        story.setCharacterProfiles(storyNode.path("character_profiles").asText(null));
        story = storyRepository.save(story);

        // Save cover
        String coverPath = storyNode.path("cover_image").asText(null);
        if (coverPath != null && zipContents.containsKey(coverPath)) {
            String savedCover = saveZipImage(zipContents.get(coverPath), mediaStorageService.getCoversDir(), "cover_" + story.getId());
            story.setCoverImage(savedCover);
            story = storyRepository.save(story);
        }

        // Import asset groups
        Map<String, Long> groupKeyMap = new HashMap<>();
        JsonNode groupsArray = storyNode.path("asset_groups");
        if (groupsArray.isArray()) {
            for (JsonNode groupNode : groupsArray) {
                StoryAssetGroup group = new StoryAssetGroup();
                group.setStory(story);
                group.setName(groupNode.path("name").asText("Unnamed Group"));
                group.setCharacterProfiles(groupNode.path("character_profiles").asText(null));
                group = assetGroupRepository.save(group);
                groupKeyMap.put(groupNode.path("group_key").asText(), group.getId());
            }
        }

        // Import chapters
        JsonNode chaptersArray = storyNode.path("chapters");
        Set<Integer> chapterNumbers = new HashSet<>();
        if (chaptersArray.isArray()) {
            for (JsonNode chapterNode : chaptersArray) {
                int chapterNumber = chapterNode.path("chapter_number").asInt(0);
                if (chapterNumber <= 0) {
                    throw new BusinessException(400, "Invalid chapter number: " + chapterNumber);
                }
                if (!chapterNumbers.add(chapterNumber)) {
                    throw new BusinessException(400, "Duplicate chapter number: " + chapterNumber);
                }

                Chapter chapter = new Chapter();
                chapter.setStory(story);
                chapter.setChapterNumber(chapterNumber);
                chapter.setNovelContent(chapterNode.path("novel_content").asText(null));

                String source = chapterNode.path("content_source").asText(null);
                if (source != null) {
                    chapter.setContentSource(ContentSource.valueOf(source.toUpperCase()));
                }

                chapter.setScenesText(chapterNode.path("scenes_text").asText(null));
                chapter.setCharacterProfiles(chapterNode.path("character_profiles").asText(null));
                chapter.setColorMode(ColorMode.valueOf(chapterNode.path("color_mode").asText("bw").toUpperCase()));
                chapter.setImageCount(chapterNode.path("image_count").asInt(10));

                String groupKey = chapterNode.path("asset_group_key").asText(null);
                if (groupKey != null && groupKeyMap.containsKey(groupKey)) {
                    StoryAssetGroup group = new StoryAssetGroup();
                    group.setId(groupKeyMap.get(groupKey));
                    chapter.setAssetGroup(group);
                }

                chapter = chapterRepository.save(chapter);

                // Import messages
                JsonNode messagesArray = chapterNode.path("messages");
                if (messagesArray.isArray()) {
                    for (JsonNode msgNode : messagesArray) {
                        ChatMessage msg = new ChatMessage();
                        msg.setChapter(chapter);
                        msg.setRole(MessageRole.valueOf(msgNode.path("role").asText("user").toUpperCase()));
                        msg.setContent(msgNode.path("content").asText(""));
                        chatMessageRepository.save(msg);
                    }
                }

                // Import images
                JsonNode imagesArray = chapterNode.path("images");
                if (imagesArray.isArray()) {
                    for (JsonNode imgNode : imagesArray) {
                        String imgPath = imgNode.path("image_path").asText(null);
                        if (imgPath != null && zipContents.containsKey(imgPath)) {
                            String savedPath = saveZipImage(zipContents.get(imgPath),
                                    mediaStorageService.getChapterDir(chapter.getId()),
                                    "panel_" + String.format("%02d", imgNode.path("image_number").asInt()));

                            MangaImage mangaImage = new MangaImage();
                            mangaImage.setChapter(chapter);
                            mangaImage.setImageNumber(imgNode.path("image_number").asInt());
                            mangaImage.setImagePath(savedPath);
                            mangaImage.setPrompt(imgNode.path("prompt").asText(null));
                            mangaImageRepository.save(mangaImage);
                        }
                    }
                }
            }
        }

        // Ensure at least one chapter
        if (chapterRepository.findByStoryIdOrderByChapterNumberAsc(story.getId()).isEmpty()) {
            Chapter chapter = new Chapter();
            chapter.setStory(story);
            chapter.setChapterNumber(1);
            chapterRepository.save(chapter);
        }

        return story;
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    private void addFileToZip(ZipOutputStream zos, String relativePath, String zipEntryName) throws IOException {
        Path path = mediaStorageService.resolveRelativePath(relativePath);
        if (path != null && Files.exists(path)) {
            zos.putNextEntry(new ZipEntry(zipEntryName));
            Files.copy(path, zos);
            zos.closeEntry();
        }
    }

    private void addImageToZip(ZipOutputStream zos, MangaImage img, String zipEntryName) throws IOException {
        if (img.getStorageProvider() == StorageProvider.MINIO && img.getBucket() != null && img.getObjectKey() != null) {
            try (InputStream is = objectStorageService.get(img.getBucket(), img.getObjectKey())) {
                zos.putNextEntry(new ZipEntry(zipEntryName));
                is.transferTo(zos);
                zos.closeEntry();
            }
        } else {
            addFileToZip(zos, img.getImagePath(), zipEntryName);
        }
    }

    private String saveZipImage(byte[] data, Path targetDir, String prefix) {
        try {
            Files.createDirectories(targetDir);
            String filename = prefix + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            Path target = targetDir.resolve(filename);
            Files.write(target, data);
            return mediaStorageService.toRelativePath(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save zip image", e);
        }
    }
}
