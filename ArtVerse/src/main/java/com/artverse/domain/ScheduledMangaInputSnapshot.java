package com.artverse.domain;

import java.util.List;

/** Immutable, secret-free inputs used to build every image prompt in one scheduled chapter task. */
public record ScheduledMangaInputSnapshot(
        int schemaVersion,
        Long storyId,
        Long chapterId,
        Long chapterVersion,
        String mangaStyle,
        String colorMode,
        String characterProfiles,
        Long assetGroupId,
        String chapterReference,
        String storyReference,
        List<String> referenceImageKeys,
        List<String> scenes,
        List<Integer> missingImageNumbers,
        String imageSize,
        String modelName,
        Long modelConfigId,
        String promptVersion
) {
    public ScheduledMangaInputSnapshot {
        referenceImageKeys = List.copyOf(referenceImageKeys);
        scenes = List.copyOf(scenes);
        missingImageNumbers = List.copyOf(missingImageNumbers);
    }
}
