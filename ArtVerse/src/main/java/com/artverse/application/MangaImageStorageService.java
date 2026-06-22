package com.artverse.application;

import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.domain.StorageProvider;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.MangaImageRepository;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaImageStorageService {

    private final MangaImageRepository mangaImageRepository;
    private final ObjectStorageService objectStorageService;
    private final MediaStorageService mediaStorageService;
    private final ArtVerseProperties properties;

    public ReferenceImages prepareReferenceImages(Long storyId, Long chapterId, String chapterRefImage,
                                                   Long assetGroupId, String storyRefImage) {
        List<Path> refs = computeEffectiveRefImages(storyId, chapterId, chapterRefImage, assetGroupId, storyRefImage);
        List<Path> tempRefs = new ArrayList<>();
        List<Path> materialized = materializeMinioRefs(refs, tempRefs);
        return new ReferenceImages(materialized, tempRefs);
    }

    public Optional<MangaImage> findPanel(Long chapterId, int imageNumber) {
        return mangaImageRepository.findByChapterIdAndImageNumber(chapterId, imageNumber);
    }

    public MangaImage saveGeneratedPanel(Chapter chapter, Long storyId, int imageNumber, Path generatedFile,
                                         String prompt) {
        String filename = mediaStorageService.generateUniqueFilename("panel_" + String.format("%02d", imageNumber), ".png");
        String objectKey = "stories/" + storyId + "/chapters/" + chapter.getId() + "/panels/" + filename;
        StoredObject stored = objectStorageService.putPng(objectKey, generatedFile, "image/png");

        MangaImage mangaImage = mangaImageRepository
                .findByChapterIdAndImageNumber(chapter.getId(), imageNumber)
                .orElseGet(() -> {
                    MangaImage m = new MangaImage();
                    m.setChapter(chapter);
                    m.setImageNumber(imageNumber);
                    return m;
                });
        applyStoredObject(mangaImage, stored, prompt);
        return mangaImageRepository.saveAndFlush(mangaImage);
    }

    public MangaImage replaceGeneratedPanel(Chapter chapter, int imageNumber, Path generatedFile, String prompt) {
        String filename = mediaStorageService.generateUniqueFilename("panel_" + String.format("%02d", imageNumber), ".png");
        String objectKey = "stories/" + chapter.getStory().getId() + "/chapters/" + chapter.getId() + "/panels/" + filename;
        StoredObject stored = objectStorageService.putPng(objectKey, generatedFile, "image/png");

        MangaImage mangaImage = mangaImageRepository
                .findByChapterIdAndImageNumber(chapter.getId(), imageNumber)
                .orElseGet(() -> {
                    MangaImage m = new MangaImage();
                    m.setChapter(chapter);
                    m.setImageNumber(imageNumber);
                    return m;
                });

        String oldBucket = mangaImage.getBucket();
        String oldObjectKey = mangaImage.getObjectKey();
        applyStoredObject(mangaImage, stored, prompt);
        MangaImage saved = mangaImageRepository.save(mangaImage);
        cleanupOldObject(oldBucket, oldObjectKey);
        return saved;
    }

    public void cleanupTempFile(Path tempFile) {
        if (tempFile == null) return;
        try {
            Files.deleteIfExists(tempFile);
            Path parent = tempFile.getParent();
            if (parent != null) Files.deleteIfExists(parent);
        } catch (Exception ignored) {
        }
    }

    public void cleanupTempFiles(List<Path> tempFiles) {
        if (tempFiles == null) return;
        for (Path tempFile : tempFiles) {
            cleanupTempFile(tempFile);
        }
    }

    private void applyStoredObject(MangaImage mangaImage, StoredObject stored, String prompt) {
        mangaImage.setImagePath(stored.objectKey());
        mangaImage.setStorageProvider(StorageProvider.MINIO);
        mangaImage.setBucket(stored.bucket());
        mangaImage.setObjectKey(stored.objectKey());
        mangaImage.setContentType(stored.contentType());
        mangaImage.setSizeBytes(stored.sizeBytes());
        mangaImage.setPrompt(prompt);
    }

    private void cleanupOldObject(String bucket, String objectKey) {
        if (bucket != null && objectKey != null) {
            try {
                objectStorageService.deleteBestEffort(bucket, objectKey);
            } catch (Exception e) {
                log.warn("Failed to delete old MinIO object {}/{}: {}", bucket, objectKey, e.getMessage());
            }
        }
    }

    private List<Path> computeEffectiveRefImages(Long storyId, Long chapterId, String chapterRefImage,
                                                 Long assetGroupId, String storyRefImage) {
        List<Path> refs = new ArrayList<>();

        addMinioRefs(refs, "stories/" + storyId + "/chapters/" + chapterId + "/ref_images/");

        if (refs.isEmpty() && chapterRefImage != null && !chapterRefImage.isBlank()) {
            Path ref = mediaStorageService.resolveRelativePath(chapterRefImage);
            if (ref != null && Files.exists(ref)) refs.add(ref);
        }

        if (refs.isEmpty() && assetGroupId != null) {
            addMinioRefs(refs, "stories/" + storyId + "/asset_groups/" + assetGroupId + "/ref_images/");
        }

        if (refs.isEmpty()) {
            addMinioRefs(refs, "stories/" + storyId + "/ref_images/");
        }

        if (refs.isEmpty() && storyRefImage != null && !storyRefImage.isBlank()) {
            Path ref = mediaStorageService.resolveRelativePath(storyRefImage);
            if (ref != null && Files.exists(ref)) refs.add(ref);
        }

        return refs;
    }

    private void addMinioRefs(List<Path> refs, String prefix) {
        objectStorageService.list(properties.getMinio().getBucket(), prefix, 4).stream()
                .map(stored -> Path.of(stored.objectKey()))
                .filter(this::isImageFile)
                .limit(4L - refs.size())
                .forEach(refs::add);
    }

    private List<Path> materializeMinioRefs(List<Path> refs, List<Path> tempRefs) {
        List<Path> materialized = new ArrayList<>();
        for (Path ref : refs) {
            if (Files.exists(ref)) {
                materialized.add(ref);
            } else {
                Path temp = downloadRefObject(ref.toString().replace('\\', '/'));
                tempRefs.add(temp);
                materialized.add(temp);
            }
        }
        return materialized;
    }

    private Path downloadRefObject(String objectKey) {
        try (var in = objectStorageService.get(properties.getMinio().getBucket(), objectKey)) {
            Path temp = Files.createTempFile("artverse-ref-", suffixFor(objectKey));
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download ref image: " + e.getMessage(), e);
        }
    }

    private String suffixFor(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".webp")) return ".webp";
        return ".png";
    }

    private boolean isImageFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    public record ReferenceImages(List<Path> requestRefs, List<Path> tempRefs) {
        public boolean hasRefs() {
            return !requestRefs.isEmpty();
        }
    }
}
