package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.SceneService;
import com.artverse.application.StoryboardArtifactService;
import com.artverse.domain.Chapter;
import com.artverse.application.ToolIdempotencyService;
import com.artverse.common.BusinessException;
import com.artverse.persistence.MangaImageRepository;
import com.artverse.application.tools.MangaContextTools.Section;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/** Transactional chapter snapshot reader behind the annotation-only native tool facade. */
@Service
@RequiredArgsConstructor
public class MangaChapterContextReader {

    private final MangaImageRepository mangaImageRepository;
    private final SceneService sceneService;
    private final ChapterAccessService chapterAccessService;
    private final MangaToolSupport support;
    private final StoryboardArtifactService artifacts;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String,Object> read(Section section, String cursor, Integer limit, String readRef,
                                   RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        Section selected = section == null ? Section.overview : section;
        if (limit != null && (limit < 1 || limit > 8000)) throw new BusinessException(400, "Page limit must be 1-8000");
        Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
        String source = chapter.novelContentOrJoinedMessages();
        String storyboard = Objects.toString(chapter.getScenesText(), "");
        Map<String,Object> baseline = artifacts.readReceipt(context, chapter, selected.name(), 0, 0, "");
        String ref = Objects.toString(baseline.get("read_ref"));
        if (readRef != null && !readRef.isBlank() && !ref.equals(readRef)) throw stale();
        int start = cursor == null || cursor.isBlank() ? 0 : offset(cursor, selected, ref);
        if (selected == Section.overview && start != 0) throw new BusinessException(400, "Overview does not accept a cursor");
        String full = selected == Section.storyboard ? storyboard : source;
        if (start > full.length() || start > 0 && start < full.length()
                && Character.isLowSurrogate(full.charAt(start)) && Character.isHighSurrogate(full.charAt(start - 1)))
            throw new BusinessException(400, "Invalid page offset");
        int count = selected == Section.overview ? 1200 : limit == null ? 8000 : limit;
        int end = pageEnd(full, start, count);
        String text = full.substring(start, end);
        Map<String,Object> receipt = artifacts.readReceipt(context, chapter, selected.name(), start, end, text);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("success", true); result.put("section", selected.name()); result.put("read_ref", ref);
        result.put("story_id", chapter.getStory().getId()); result.put("chapter_id", chapter.getId());
        result.put("chapter_version", chapter.getVersion() == null ? 0L : chapter.getVersion());
        result.put("source_hash", receipt.get("source_hash")); result.put("source_length", source.length());
        result.put("storyboard_hash", receipt.get("storyboard_hash")); result.put("storyboard_length", storyboard.length());
        result.put("offset_unit", "UTF16_CHARACTERS"); result.put("start", start); result.put("end", end);
        result.put("truncated", end < full.length()); result.put("complete", start == 0 && end == full.length());
        result.put("next_cursor", selected != Section.overview && end < full.length() ? cursor(ref, selected, end) : "");
        result.put("read_receipt", receipt);
        if (selected == Section.overview) {
            result.put("story_title", Objects.toString(chapter.getStory().getTitle(), ""));
            result.put("chapter_number", chapter.getChapterNumber()); result.put("chapter_display_name", support.chapterDisplayName(chapter));
            result.put("image_count", chapter.getImageCount()); result.put("color_mode", chapter.getColorMode().name().toLowerCase());
            result.put("manga_style", Objects.toString(chapter.getStory().getMangaStyle(), ""));
            result.put("has_source_content", !source.isBlank()); result.put("source_excerpt", text);
            result.put("source_excerpt_truncated", end < source.length()); result.put("source_excerpt_total_length", source.length());
            result.put("source_read_hint", "Read section=source and section=storyboard with this read_ref; follow each next_cursor to completion.");
            result.put("scenes_count", sceneService.parseScenesText(storyboard).size());
            var images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(context.chapterId());
            result.put("generated_images", images.stream().map(image -> {
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("id", image.getId()); item.put("image_number", image.getImageNumber());
                item.put("image_version", image.getVersion()); item.put("has_generated_image", image.getImagePath() != null && !image.getImagePath().isBlank());
                return item;
            }).toList());
            String imageSnapshot = images.stream().map(image -> image.getId() + ":" + image.getImageNumber() + ":"
                    + image.getVersion() + ":" + ToolIdempotencyService.sha256(Objects.toString(image.getImagePath(), "")))
                    .collect(java.util.stream.Collectors.joining("\n"));
            result.put("images_collection_hash", ToolIdempotencyService.sha256(imageSnapshot));
            result.put("images_snapshot_semantics", "collection_hash_and_per_image_versions; independent_of_chapter_version");
        } else result.put(selected == Section.source ? "source_text" : "storyboard_text", text);
        return result;
    }

    public static int pageEnd(String text, int start, int limit) {
        int end = Math.min(text.length(), start + limit);
        if (end > start && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end)))
            end = end - 1 == start ? end + 1 : end - 1;
        return end;
    }
    public static String cursor(String ref, Section section, int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((ref + ":" + section + ":" + offset).getBytes(StandardCharsets.UTF_8));
    }
    private static int offset(String cursor, Section section, String ref) {
        try {
            if (cursor.length() > 256) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (fields.length != 3 || !ref.equals(fields[0]) || !section.name().equals(fields[1])) throw stale();
            int offset = Integer.parseInt(fields[2]);
            if (offset < 0) throw new IllegalArgumentException();
            return offset;
        } catch (IllegalArgumentException invalid) { throw new BusinessException(400, "Invalid chapter page cursor"); }
    }
    private static BusinessException stale() { return new BusinessException(409, "STALE_VERSION: restart chapter reading with a new read_ref"); }
}
