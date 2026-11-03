package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.ai.GeneratedImage;
import com.artverse.ai.Image2Client;
import com.artverse.ai.ImageGenerationRequest;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.ImageGenRecord;
import com.artverse.domain.ImageGenStatus;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.AiConversationType;
import com.artverse.domain.User;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ImageGenRecordRepository;
import com.artverse.persistence.UserRepository;
import com.artverse.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenService {

    private static final int MAX_REF_IMAGES = 3;
    private static final Duration MAX_RUNNING_DURATION = Duration.ofMinutes(12);

    private final ImageGenRecordRepository recordRepository;
    private final UserRepository userRepository;
    private final Image2Client image2Client;
    private final MediaStorageService mediaStorageService;
    private final ObjectStorageService objectStorageService;
    private final ArtVerseProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final AiConversationService aiConversationService;

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;

    @Transactional
    public Map<String, Object> submit(String prompt, List<String> referenceImagesBase64, UserProviderConfig imageConfig, String sizeOverride, UUID conversationId) {
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(401, "User not found"));
        if (referenceImagesBase64 != null && referenceImagesBase64.size() > MAX_REF_IMAGES) {
            throw new BusinessException(400, "Maximum " + MAX_REF_IMAGES + " reference images allowed");
        }

        ImageGenRecord record = new ImageGenRecord();
        record.setUser(user);
        MangaAgentConversation conversation = conversationId == null
                ? aiConversationService.activeImageConversation(user)
                : aiConversationService.require(user, conversationId);
        if (conversation.getConversationType() != AiConversationType.IMAGE_GEN) throw new BusinessException(400, "Conversation is not an image generation conversation");
        record.setConversation(conversation);
        record.setPrompt(prompt);
        record.setModel(imageConfig.primaryModel().isBlank() ? properties.getImage().getModel() : imageConfig.primaryModel());
        record.setSize(sizeOverride == null || sizeOverride.isBlank() ? properties.getImage().getSize() : sizeOverride);
        record.setStatus(ImageGenStatus.RUNNING);
        record = recordRepository.save(record);
        aiConversationService.autoTitle(conversation, prompt);
        aiConversationService.touch(conversation);

        Long recordId = record.getId();
        List<String> references = referenceImagesBase64 == null ? List.of() : List.copyOf(referenceImagesBase64);
        executor.submit(() -> transactionTemplate.executeWithoutResult(
                status -> generateInBackground(recordId, references, imageConfig)
        ));
        return recordToMap(record);
    }

    void generateInBackground(Long recordId, List<String> referenceImagesBase64, UserProviderConfig imageConfig) {
        ImageGenRecord record = recordRepository.findById(recordId).orElse(null);
        if (record == null || record.getIsDeleted() || record.getStatus() != ImageGenStatus.RUNNING) return;

        List<Path> refFiles = new ArrayList<>();
        try {
            for (String b64 : referenceImagesBase64) {
                if (b64 == null || b64.isBlank()) continue;
                byte[] data = mediaStorageService.decodeBase64Image(b64);
                mediaStorageService.validateImageBytes(data, properties.getUpload().getMaxImageBytes());
                Path tmp = Files.createTempFile("artverse-ref-", ".png");
                mediaStorageService.savePng(data, tmp);
                refFiles.add(tmp);
            }

            GeneratedImage generated = image2Client.generate(new ImageGenerationRequest(
                    record.getPrompt(), record.getModel(), record.getSize(),
                    refFiles.isEmpty() ? null : refFiles, null
            ), imageConfig).block();
            if (generated == null) throw new BusinessException(502, "Image generation returned no result");

            String objectKey = "image_gen/" + record.getUser().getId() + "/"
                    + mediaStorageService.generateUniqueFilename("gen", generated.extension());
            try (var content = generated.openStream()) {
                objectStorageService.put(objectKey, content, generated.sizeBytes(), generated.contentType());
            }

            record.setImagePath(objectKey);
            record.setStatus(ImageGenStatus.SUCCEEDED);
            record.setFailureReason(null);
            record.setCompletedAt(OffsetDateTime.now());
            recordRepository.save(record);
        } catch (Exception e) {
            log.warn("Image generation task {} failed", recordId, e);
            record.setStatus(ImageGenStatus.FAILED);
            record.setFailureReason(errorMessage(e));
            record.setCompletedAt(OffsetDateTime.now());
            recordRepository.save(record);
        } finally {
            for (Path refFile : refFiles) {
                try { Files.deleteIfExists(refFile); } catch (Exception ignored) {}
            }
        }
    }

    @Transactional
    public Map<String, Object> listHistory(int page, int size, UUID conversationId) {
        Long userId = StpUtil.getLoginIdAsLong();
        OffsetDateTime now = OffsetDateTime.now();
        recordRepository.markExpiredRunningAsFailed(userId, now.minus(MAX_RUNNING_DURATION), now,
                "Image generation was interrupted before completion. Please try again.");
        Page<ImageGenRecord> result = conversationId == null
                ? recordRepository.findByUserId(userId, PageRequest.of(page, size))
                : recordRepository.findByUserIdAndConversationUuid(userId, conversationId, PageRequest.of(page, size));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", result.getContent().stream().map(this::recordToMap).toList());
        response.put("total_pages", result.getTotalPages());
        response.put("total_elements", result.getTotalElements());
        return response;
    }

    @Transactional
    public void delete(Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        ImageGenRecord record = recordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Record not found"));
        if (!record.getUser().getId().equals(userId)) throw new BusinessException(403, "Access denied");
        record.setIsDeleted(true);
        recordRepository.save(record);
    }

    private Map<String, Object> recordToMap(ImageGenRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", record.getId());
        result.put("conversation_id", record.getConversation()==null?null:record.getConversation().getConversationUuid().toString());
        result.put("request_id",record.getRootRequestId());
        result.put("prompt", record.getPrompt());
        result.put("image_url", record.getImagePath());
        result.put("model", record.getModel());
        result.put("size", record.getSize());
        result.put("status", record.getStatus().name());
        if(record.getRequestId()!=null&&"UNRESOLVED".equals(record.getAttemptDecision())&&"NEEDS_REVIEW".equals(record.getOutcome()))result.put("status","NEEDS_REVIEW");
        result.put("failure_reason", record.getFailureReason());
        result.put("created_at", record.getCreatedAt().toString());
        result.put("completed_at", record.getCompletedAt() == null ? null : record.getCompletedAt().toString());
        return result;
    }

    private String errorMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && (cause.getMessage() == null || cause.getMessage().isBlank())) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Image generation failed" : message;
    }
}
