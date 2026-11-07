package com.artverse.application;

import com.artverse.ai.GeneratedImage;
import com.artverse.domain.MangaImage;
import com.artverse.domain.StorageProvider;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.MangaImageRepository;
import com.artverse.persistence.ChapterRepository;
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
    private final ChapterRepository chapterRepository;
    private final ObjectStorageService objectStorageService;
    private final MediaStorageService mediaStorageService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.transaction.support.TransactionTemplate transactions;

    public ReferenceImages prepareReferenceImages(Long storyId, Long chapterId, String chapterRefImage,
                                                   Long assetGroupId, String storyRefImage) {
        List<Path> refs = computeEffectiveRefImages(storyId, chapterId, chapterRefImage, assetGroupId, storyRefImage);
        List<Path> tempRefs = new ArrayList<>();
        List<Path> materialized = materializeRefs(refs, tempRefs);
        return new ReferenceImages(materialized, tempRefs);
    }

    public ReferenceImages prepareReferenceImagesFromSnapshot(List<String> objectKeys) {
        List<Path> refs = objectKeys == null ? List.of() : objectKeys.stream().map(Path::of).toList();
        List<Path> tempRefs = new ArrayList<>();
        return new ReferenceImages(materializeRefs(refs, tempRefs), tempRefs);
    }

    public List<String> previewReferenceImageKeys(Long storyId, Long chapterId, String chapterRefImage,
                                                  Long assetGroupId, String storyRefImage) {
        return computeEffectiveRefImages(storyId, chapterId, chapterRefImage, assetGroupId, storyRefImage).stream()
                .map(path -> path.toString().replace('\\', '/'))
                .toList();
    }

    public Optional<MangaImage> findPanel(Long chapterId, int imageNumber) {
        return mangaImageRepository.findByChapterIdAndImageNumber(chapterId, imageNumber);
    }

    public MangaImage saveGeneratedPanel(Long chapterId, Long storyId, int imageNumber, Path generatedFile,
                                         String prompt) {
        String filename = mediaStorageService.generateUniqueFilename("panel_" + String.format("%02d", imageNumber), ".png");
        String objectKey = "stories/" + storyId + "/chapters/" + chapterId + "/panels/" + filename;
        StoredObject stored = objectStorageService.putPng(objectKey, generatedFile, "image/png");

        return saveUploadedPanel(chapterId, imageNumber, stored, prompt);
    }

    public MangaImage saveGeneratedPanel(Long chapterId, Long storyId, int imageNumber, GeneratedImage generated,
                                         String prompt) {
        String filename = mediaStorageService.generateUniqueFilename("panel_" + String.format("%02d", imageNumber), generated.extension());
        String objectKey = "stories/" + storyId + "/chapters/" + chapterId + "/panels/" + filename;
        StoredObject stored;
        try (var content = generated.openStream()) {
            stored = objectStorageService.put(objectKey, content, generated.sizeBytes(), generated.contentType());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to close generated image content", e);
        }
        return saveUploadedPanel(chapterId, imageNumber, stored, prompt);
    }

    private MangaImage saveUploadedPanel(Long chapterId, int imageNumber, StoredObject stored, String prompt) {
        MangaImage mangaImage = mangaImageRepository
                .findByChapterIdAndImageNumber(chapterId, imageNumber)
                .orElseGet(() -> {
                    MangaImage m = new MangaImage();
                    m.setChapter(chapterRepository.getReferenceById(chapterId));
                    m.setImageNumber(imageNumber);
                    return m;
                });
        String oldBucket = mangaImage.getBucket();
        String oldObjectKey = mangaImage.getObjectKey();
        applyStoredObject(mangaImage, stored, prompt);
        MangaImage saved = mangaImageRepository.saveAndFlush(mangaImage);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { cleanupOldObject(oldBucket,oldObjectKey); }
            });
        } else cleanupOldObject(oldBucket, oldObjectKey);
        return saved;
    }

    /** Scheduled callers retain their own scheduling semantics, but fix the image baseline before model IO. */
    public MangaImage saveGeneratedPanel(Long chapterId, Long storyId, int imageNumber, GeneratedImage generated,
                                        String prompt, Long expectedId, Long expectedVersion) {
        String objectKey="stories/"+storyId+"/chapters/"+chapterId+"/panels/"+
                mediaStorageService.generateUniqueFilename("panel_"+imageNumber,generated.extension());
        StoredObject stored;
        try(var content=generated.openStream()) { stored=objectStorageService.put(objectKey,content,generated.sizeBytes(),generated.contentType()); }
        catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        java.util.function.Supplier<MangaImage> commit=()-> {
            MangaImage current=mangaImageRepository.findByChapterIdAndImageNumber(chapterId,imageNumber).orElse(null);
            if(current==null?expectedId!=null:!java.util.Objects.equals(current.getId(),expectedId)||!java.util.Objects.equals(current.getVersion(),expectedVersion))
                throw new org.springframework.dao.OptimisticLockingFailureException("Scheduled image baseline changed");
            return saveUploadedPanel(chapterId,imageNumber,stored,prompt);
        };
        return transactions==null?commit.get():transactions.execute(status->commit.get());
    }

    public MangaImage replaceGeneratedPanel(Long chapterId, Long storyId, int imageNumber, Path generatedFile,
                                           String prompt) {
        return saveGeneratedPanel(chapterId, storyId, imageNumber, generatedFile, prompt);
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
        mangaImage.setStorageProvider(StorageProvider.OSS);
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
                log.warn("Failed to delete old object storage object {}/{}: {}", bucket, objectKey, e.getMessage());
            }
        }
    }

    private List<Path> computeEffectiveRefImages(Long storyId, Long chapterId, String chapterRefImage,
                                                 Long assetGroupId, String storyRefImage) {
        List<Path> refs = new ArrayList<>();

        addObjectRefs(refs, "stories/" + storyId + "/chapters/" + chapterId + "/ref_images/");

        if (refs.isEmpty() && chapterRefImage != null && !chapterRefImage.isBlank()) {
            Path ref = mediaStorageService.resolveRelativePath(chapterRefImage);
            if (ref != null && Files.exists(ref)) refs.add(ref);
        }

        if (refs.isEmpty() && assetGroupId != null) {
            addObjectRefs(refs, "stories/" + storyId + "/asset_groups/" + assetGroupId + "/ref_images/");
        }

        if (refs.isEmpty()) {
            addObjectRefs(refs, "stories/" + storyId + "/ref_images/");
        }

        if (refs.isEmpty() && storyRefImage != null && !storyRefImage.isBlank()) {
            Path ref = mediaStorageService.resolveRelativePath(storyRefImage);
            if (ref != null && Files.exists(ref)) refs.add(ref);
        }

        return refs;
    }

    private void addObjectRefs(List<Path> refs, String prefix) {
        objectStorageService.list(objectStorageService.defaultBucket(), prefix, 4).stream()
                .map(stored -> Path.of(stored.objectKey()))
                .filter(this::isImageFile)
                .limit(4L - refs.size())
                .forEach(refs::add);
    }

    private List<Path> materializeRefs(List<Path> refs, List<Path> tempRefs) {
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
        try (var in = objectStorageService.get(objectStorageService.defaultBucket(), objectKey)) {
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
