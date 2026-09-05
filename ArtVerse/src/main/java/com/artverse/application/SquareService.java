package com.artverse.application;

import com.artverse.api.dto.SquareDtos;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.domain.Story;
import com.artverse.persistence.StoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SquareService {
    private static final Set<String> FORMATS = Set.of("all", "novel", "manga");
    private final StoryRepository storyRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public SquareDtos.StoryListResponse listPublishedStories(int page, int size, String search, String format) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(400, "Invalid page or size");
        String resolvedFormat = format == null || format.isBlank() ? "manga" : format.toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(resolvedFormat)) throw new BusinessException(400, "Invalid format");
        String keyword = search == null ? "" : search.trim();
        String pattern = keyword.isEmpty() ? null : "%" + keyword + "%";
        String union = contentUnion(resolvedFormat);
        // 搜索参数不能放进 SQL 的 CONCAT('%', ?, '%')：连接串使用 stringtype=unspecified，
        // pgjdbc 以未定类型发送参数，而 PostgreSQL 的 concat() 是 VARIADIC "any" 函数，
        // 无法推断参数类型，会报 "could not determine data type of parameter" 并返回 500。
        // 改为在 Java 侧拼出 %关键词%，让参数落在 LOWER(:pattern) 这一可推断为 text 的位置。
        String filter = pattern == null ? "" : " WHERE LOWER(title) LIKE LOWER(:pattern) OR LOWER(description) LIKE LOWER(:pattern)";
        Query pageQuery = entityManager.createNativeQuery("SELECT * FROM (" + union + ") entries" + filter + " ORDER BY published_at DESC NULLS LAST, id DESC, format ASC");
        Query countQuery = entityManager.createNativeQuery("SELECT COUNT(*) FROM (" + union + ") entries" + filter);
        if (pattern != null) { pageQuery.setParameter("pattern", pattern); countQuery.setParameter("pattern", pattern); }
        pageQuery.setFirstResult(page * size);
        pageQuery.setMaxResults(size);
        @SuppressWarnings("unchecked") List<Object[]> rows = pageQuery.getResultList();
        long total = ((Number) countQuery.getSingleResult()).longValue();
        List<SquareDtos.StoryCard> cards = rows.stream().map(this::card).toList();
        Map<String, Long> facets = new LinkedHashMap<>();
        facets.put("all", countFor("all", keyword));
        facets.put("novel", countFor("novel", keyword));
        facets.put("manga", countFor("manga", keyword));
        return new SquareDtos.StoryListResponse(cards, (int) Math.ceil((double) total / size), total, facets);
    }

    @Transactional(readOnly = true)
    public SquareDtos.StoryDetail getPublishedStoryDetail(Long id, String format) {
        String resolvedFormat = format == null || format.isBlank() ? "manga" : format.toLowerCase(Locale.ROOT);
        if (!Set.of("novel", "manga").contains(resolvedFormat)) throw new BusinessException(400, "Invalid format");
        Story story = storyRepository.findById(id).orElseThrow(() -> new BusinessException(404, "Story not found or not published"));
        if (!isPublishedAndReadable(story, resolvedFormat)) throw new BusinessException(404, "Story not found or not published");
        List<String> available = new ArrayList<>();
        if (isPublishedAndReadable(story, "novel")) available.add("novel");
        if (isPublishedAndReadable(story, "manga")) available.add("manga");
        List<SquareDtos.ChapterItem> chapters = story.getChapters().stream()
                .filter(chapter -> readable(chapter, resolvedFormat))
                .sorted(Comparator.comparingInt(chapter -> Optional.ofNullable(chapter.getDisplayOrder()).orElse(chapter.getChapterNumber())))
                .map(chapter -> chapter(resolvedFormat, chapter)).toList();
        OffsetDateTime publishedAt = resolvedFormat.equals("novel") ? story.getNovelPublishedAt() : story.getPublishedAt();
        return new SquareDtos.StoryDetail(story.getId(), resolvedFormat, story.getTitle(), safe(story.getDescription()),
                safe(story.getCoverImage()), safe(story.getMangaStyle()), publishedAt == null ? null : publishedAt.toString(), available, chapters);
    }

    private String contentUnion(String format) {
        String novel = "SELECT s.id, 'novel' AS format, s.title, COALESCE(s.description, '') AS description, COALESCE(s.cover_image, '') AS cover_url, COALESCE(s.manga_style, '') AS manga_style, s.novel_published_at AS published_at, COUNT(c.id) AS chapter_count, COALESCE(SUM(LENGTH(TRIM(c.novel_content))), 0) AS content_count FROM stories s JOIN chapters c ON c.story_id = s.id AND c.novel_is_published = true AND LENGTH(TRIM(COALESCE(c.novel_content, ''))) > 0 WHERE s.novel_is_published = true GROUP BY s.id";
        String manga = "SELECT s.id, 'manga' AS format, s.title, COALESCE(s.description, '') AS description, COALESCE(s.cover_image, '') AS cover_url, COALESCE(s.manga_style, '') AS manga_style, s.published_at AS published_at, COUNT(DISTINCT c.id) AS chapter_count, COUNT(mi.id) AS content_count FROM stories s JOIN chapters c ON c.story_id = s.id AND c.is_published = true JOIN manga_images mi ON mi.chapter_id = c.id WHERE s.is_published = true GROUP BY s.id";
        return switch (format) { case "novel" -> novel; case "manga" -> manga; default -> novel + " UNION ALL " + manga; };
    }

    private long countFor(String format, String search) {
        String union = contentUnion(format);
        String filter = search.isEmpty() ? "" : " WHERE LOWER(title) LIKE LOWER(:pattern) OR LOWER(description) LIKE LOWER(:pattern)";
        Query query = entityManager.createNativeQuery("SELECT COUNT(*) FROM (" + union + ") entries" + filter);
        if (!search.isEmpty()) query.setParameter("pattern", "%" + search + "%");
        return ((Number) query.getSingleResult()).longValue();
    }

    private SquareDtos.StoryCard card(Object[] row) {
        return new SquareDtos.StoryCard(((Number) row[0]).longValue(), (String) row[1], (String) row[2], (String) row[3],
                (String) row[4], (String) row[5], row[6] == null ? null : row[6].toString(), ((Number) row[7]).longValue(), ((Number) row[8]).longValue());
    }

    private boolean isPublishedAndReadable(Story story, String format) {
        return (format.equals("novel") ? Boolean.TRUE.equals(story.getNovelIsPublished()) : Boolean.TRUE.equals(story.getIsPublished()))
                && story.getChapters().stream().anyMatch(chapter -> readable(chapter, format));
    }
    private boolean readable(Chapter chapter, String format) { return format.equals("novel") ? Boolean.TRUE.equals(chapter.getNovelIsPublished()) && chapter.getNovelContent() != null && !chapter.getNovelContent().isBlank() : Boolean.TRUE.equals(chapter.getIsPublished()) && chapter.getImages() != null && !chapter.getImages().isEmpty(); }
    private SquareDtos.ChapterItem chapter(String format, Chapter chapter) {
        List<SquareDtos.ImageItem> images = format.equals("manga") ? chapter.getImages().stream().sorted(Comparator.comparingInt(MangaImage::getImageNumber)).map(image -> new SquareDtos.ImageItem(image.getId(), image.getImageNumber(), image.getImagePath())).toList() : List.of();
        String content = format.equals("novel") ? chapter.getNovelContent().trim() : null;
        return new SquareDtos.ChapterItem(chapter.getId(), chapter.getChapterNumber(), Optional.ofNullable(chapter.getDisplayTitle()).orElse("第 " + chapter.getChapterNumber() + " 章"), content, content == null ? images.size() : content.length(), images);
    }
    private String safe(String value) { return value == null ? "" : value; }
}
