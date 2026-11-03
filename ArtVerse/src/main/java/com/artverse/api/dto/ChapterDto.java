package com.artverse.api.dto;

import com.artverse.domain.Chapter;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.ContentSource;
import com.artverse.domain.MangaImage;
import com.artverse.domain.MessageRole;

import java.time.OffsetDateTime;
import java.util.List;

public record ChapterDto(
        Long id,
        Long storyId,
        Integer chapterNumber,
        Long version,
        String novelContent,
        String contentSource,
        String scenesText,
        String characterProfiles,
        String refImage,
        String colorMode,
        Integer imageCount,
        OffsetDateTime createdAt,
        List<ChatMessageDto> messages,
        List<MangaImageDto> images
) {
    public static ChapterDto from(Chapter c) {
        List<ChatMessageDto> msgs = safeMessages(c);
        List<MangaImageDto> imgs = safeImages(c);
        return new ChapterDto(
                c.getId(),
                safeStoryId(c),
                c.getChapterNumber(),
                c.getVersion(),
                c.getNovelContent(),
                c.getContentSource() != null ? c.getContentSource().name().toLowerCase() : null,
                c.getScenesText(),
                c.getCharacterProfiles(),
                c.getRefImage(),
                c.getColorMode().name().toLowerCase(),
                c.getImageCount(),
                c.getCreatedAt(),
                msgs,
                imgs
        );
    }

    private static Long safeStoryId(Chapter c) {
        try {
            return c.getStory().getId();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ChatMessageDto> safeMessages(Chapter c) {
        try {
            List<ChatMessage> messages = c.getMessages();
            return messages.stream()
                    .filter(message -> !isLegacyImportedOriginalMirror(c, message))
                    .map(ChatMessageDto::from)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isLegacyImportedOriginalMirror(Chapter c, ChatMessage message) {
        if (c.getContentSource() != ContentSource.IMPORT || message == null) {
            return false;
        }
        return message.getRole() == MessageRole.USER
                && normalizeText(message.getContent()).equals(normalizeText(c.getNovelContent()))
                && !normalizeText(c.getNovelContent()).isBlank();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<MangaImageDto> safeImages(Chapter c) {
        try {
            return c.getImages().stream().map(MangaImageDto::from).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public record ChatMessageDto(Long id, String role, String content, String completionStatus, OffsetDateTime createdAt) {
        public static ChatMessageDto from(ChatMessage m) {
            String status = m.getCompletionStatus() == null ? "complete" : m.getCompletionStatus().name().toLowerCase();
            return new ChatMessageDto(m.getId(), m.getRole().name().toLowerCase(), m.getContent(), status, m.getCreatedAt());
        }
    }

    public record MangaImageDto(Long id, Integer imageNumber, String imagePath, String storageProvider,
                                String bucket, String objectKey, String contentType, Long sizeBytes,
                                String prompt, OffsetDateTime createdAt, String imageVersion) {
        public static MangaImageDto from(MangaImage m) {
            return new MangaImageDto(m.getId(), m.getImageNumber(), m.getImagePath(),
                    m.getStorageProvider().name().toLowerCase(), m.getBucket(), m.getObjectKey(),
                    m.getContentType(), m.getSizeBytes(), m.getPrompt(), m.getCreatedAt(), m.imageVersion());
        }
    }
}
