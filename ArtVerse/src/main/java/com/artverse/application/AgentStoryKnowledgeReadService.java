package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.knowledge.KnowledgeFactService;
import com.artverse.application.knowledge.KnowledgeSchema;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.KnowledgeCandidateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.artverse.application.knowledge.KnowledgeSchema.id;

/** Author-owned, chapter-projected reads. No image discovery, candidate mutation or write-target changes. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AgentStoryKnowledgeReadService {
    private final ChapterAccessService chapters;
    private final ChapterRepository chapterRepository;
    private final KnowledgeFactService facts;
    private final KnowledgeCandidateService candidates;
    private final KnowledgeCandidateRepository candidateRepository;
    private final KnowledgeSchema schema;
    private final JdbcTemplate jdbc;
    private static final ObjectMapper CURSORS = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record ReadPage(Long storyId, Long anchorChapterId, Long writeChapterId,
                           List<Map<String,Object>> items, int total, String snapshot,
                           boolean hasMore, String nextCursor, String retrievalMode) {}
    /** JSON fragments are explicitly paginated; concatenate them before interpreting a large detail. */
    public record DetailPage(Long storyId, Long anchorChapterId, Long writeChapterId,
                             Map<String,Object> reference, String snapshot, int start, int end, int totalLength,
                             String offsetUnit, String contentJsonFragment, boolean truncated, String nextCursor) {}

    public DetailPage story(MangaAgentRuntimeContext context, String cursor, Integer limit) {
        var anchor = scope(context, null);
        var story = anchor.getStory();
        var content = map("title", story.getTitle(), "description", text(story.getDescription()),
                "manga_style", story.getMangaStyle(), "scope", "AUTHOR_STORY_SETTINGS", "is_chapter_fact", false);
        content.put("base_version", story.getVersion().toString());
        content.put("knowledge_source_hash",schema.hash(KnowledgeCandidateService.ExtractionSource.from(anchor).snapshot()));
        content.put("characters",jdbc.queryForList("SELECT id::text AS character_id,name,version::text AS base_version FROM character_profiles WHERE story_id=? ORDER BY id",story.getId()));
        return detail(context, anchor, "story", map("story_id", story.getId(), "version_kind", "ENTITY_VERSION"), content, cursor, limit);
    }

    public ReadPage listChapters(MangaAgentRuntimeContext context, String cursor, Integer limit) {
        var anchor = scope(context, null);
        var items = chapterRepository.findByStoryIdAndUserIdOrderByChapterNumberAsc(context.storyId(), context.userId()).stream()
                .sorted(Comparator.comparing(Chapter::getChapterNumber).thenComparing(Chapter::getId))
                .map(ch -> map("chapter_id", ch.getId(), "chapter_number", ch.getChapterNumber(),
                        "title", text(ch.getDisplayTitle()), "chapter_version", ch.getVersion() == null ? 0L : ch.getVersion(),
                        "novel_published", Boolean.TRUE.equals(ch.getNovelIsPublished()),
                        "manga_published", Boolean.TRUE.equals(ch.getIsPublished())))
                .toList();
        return page(context, anchor, "chapters", items, cursor, limit, "STRUCTURED_QUERY");
    }

    public ReadPage search(MangaAgentRuntimeContext context, String query, KnowledgeUnitType type,
                           Long chapterId, Long knowingCharacterId, String cursor, Integer limit) {
        var anchor = scope(context, chapterId);
        String q = text(query).trim();
        if (q.length() > 500) throw new BusinessException(400, "Query is limited to 500 characters");
        requireKnowingCharacter(context, anchor, knowingCharacterId);
        var scopedFacts = visibleFacts(context, anchor, knowingCharacterId);
        var visible = facts.project(context.storyId(), anchor.getId(), knowingCharacterId).stream()
                .filter(row -> type == null || type.name().equals(row.get("type")))
                .filter(row -> q.isBlank() || schema.write(row).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingLong(row -> ((Number)row.get("id")).longValue())).toList();
        var items = visible.stream().map(row -> map("knowledge_id", row.get("id"), "type", row.get("type"),
                "title", excerpt(text(row.get("title")), 300), "summary", excerpt(text(row.get("body")), 500),
                "summary_truncated", text(row.get("body")).length() > 500, "body_length", text(row.get("body")).length(),
                "projection_hash", schema.hash(row), "fact_id", row.get("factId"),
                "fact_revision_ids", scopedFacts.stream()
                        .filter(f -> f.unitId() == ((Number)row.get("id")).longValue()).map(KnowledgeFactService.Fact::revisionId).toList())).toList();
        return page(context, anchor, "knowledge:" + q + ":" + type + ":" + knowingCharacterId, items, cursor, limit, "STRUCTURED_QUERY");
    }

    public DetailPage knowledge(MangaAgentRuntimeContext context, Long knowledgeId, Long chapterId,
                                Long knowingCharacterId, String cursor, Integer limit) {
        var anchor = scope(context, chapterId);
        requireKnowingCharacter(context, anchor, knowingCharacterId);
        if (knowledgeId == null || knowledgeId <= 0) throw new BusinessException(400, "knowledge_id is required");
        var visible = facts.project(context.storyId(), anchor.getId(), knowingCharacterId).stream()
                .filter(row -> knowledgeId.equals(id(row.get("id")))).findFirst()
                .orElseThrow(() -> new BusinessException(404, "Knowledge is unavailable in this chapter/character projection"));
        var content = new LinkedHashMap<>(visible);
        var revisions = visibleFacts(context, anchor, knowingCharacterId).stream().filter(f -> f.unitId() == knowledgeId).toList();
        content.put("source_references", sourceReferences(context, anchor, revisions));
        return detail(context, anchor, "knowledge:" + knowledgeId + ":" + knowingCharacterId,
                map("knowledge_id", knowledgeId, "projection_hash", schema.hash(visible)), content, cursor, limit);
    }

    public DetailPage character(MangaAgentRuntimeContext context, Long characterId, Long chapterId,
                                Long knowingCharacterId, String cursor, Integer limit) {
        var anchor = scope(context, chapterId);
        requireKnowingCharacter(context, anchor, knowingCharacterId);
        if (characterId == null || characterId <= 0) throw new BusinessException(400, "character_id is required");
        var core = facts.project(context.storyId(), anchor.getId(), knowingCharacterId).stream()
                .filter(row -> "CHARACTER_CARD".equals(row.get("type")))
                .filter(row -> Objects.equals(characterId, id(asMap(row.get("structuredData")).get("character_id"))))
                .findFirst().orElseThrow(() -> new BusinessException(404, "Character is not revealed in this projection"));
        var relevant = visibleFacts(context, anchor, knowingCharacterId).stream().filter(f -> involves(f.payload(), characterId)).toList();
        return detail(context, anchor, "character:" + characterId + ":" + knowingCharacterId,
                map("character_id", characterId), map("card", core, "facts", relevant,
                        "source_references", sourceReferences(context, anchor, relevant)), cursor, limit);
    }

    public ReadPage assets(MangaAgentRuntimeContext context, Long characterId, Long chapterId, String cursor, Integer limit) {
        var anchor = scope(context, chapterId);
        var visibleCharacters = new HashSet<Long>();
        facts.project(context.storyId(), anchor.getId(), null).stream().filter(row -> "CHARACTER_CARD".equals(row.get("type")))
                .forEach(row -> { Long value = id(asMap(row.get("structuredData")).get("character_id")); if (value != null) visibleCharacters.add(value); });
        if (characterId != null && !visibleCharacters.contains(characterId)) throw new BusinessException(404, "Character is not revealed in this chapter");
        var items = jdbc.queryForList("""
                SELECT i.id,i.character_profile_id,i.is_cover,i.sort_order,i.caption,i.appearance_version,
                    i.reveal_chapter_id,i.from_chapter_id,i.to_chapter_id,
                    reveal.chapter_number AS reveal_number,start.chapter_number AS from_number,finish.chapter_number AS to_number
                FROM character_reference_images i
                JOIN chapters reveal ON reveal.id=i.reveal_chapter_id AND reveal.story_id=i.story_id
                LEFT JOIN chapters start ON start.id=i.from_chapter_id AND start.story_id=i.story_id
                LEFT JOIN chapters finish ON finish.id=i.to_chapter_id AND finish.story_id=i.story_id
                WHERE i.story_id=? ORDER BY i.id
                """, context.storyId()).stream()
                .filter(row -> visibleCharacters.contains(id(row.get("character_profile_id"))))
                .filter(row -> characterId == null || characterId.equals(id(row.get("character_profile_id"))))
                .filter(row -> ((Number)row.get("reveal_number")).intValue() <= anchor.getChapterNumber())
                .filter(row -> row.get("from_number") == null || ((Number)row.get("from_number")).intValue() <= anchor.getChapterNumber())
                .filter(row -> row.get("to_number") == null || ((Number)row.get("to_number")).intValue() > anchor.getChapterNumber())
                .map(row -> map("asset_id", row.get("id"), "kind", "CHARACTER_REFERENCE_IMAGE",
                        "character_id", row.get("character_profile_id"), "appearance_version", row.get("appearance_version"),
                        "caption", excerpt(text(row.get("caption")), 500), "caption_truncated", text(row.get("caption")).length() > 500,
                        "metadata_hash", schema.hash(row),
                        "is_cover", row.get("is_cover"), "reference_semantics", "PERSISTED_ASSET_ID"))
                .toList();
        return page(context, anchor, "assets:" + characterId, items, cursor, limit, "PERSISTED_METADATA");
    }

    public ReadPage listCandidates(MangaAgentRuntimeContext context, KnowledgeUnitType type, KnowledgeCandidateStatus status,
                                   String cursor, Integer limit) {
        var anchor = scope(context, null);
        var items = scopedCandidates(context).stream().filter(c -> type == null || c.getKnowledgeType() == type)
                .filter(c -> status == null || c.getStatus() == status)
                .sorted(Comparator.comparing(KnowledgeCandidate::getCreatedAt).thenComparing(KnowledgeCandidate::getId))
                .map(c -> {
                    var view = candidates.detail(context.storyId(), c.getId(), context.userId());
                    return map("candidate_id", c.getId(), "version", c.getVersion(), "type", c.getKnowledgeType().name(),
                            "status", c.getStatus().name(), "title", excerpt(c.getTitle(), 300), "source_stale", view.sourceStale(),
                            "source_hash", text(c.getSourceHash()), "proposal_hash", c.getProposedHash(), "is_confirmed_fact", false);
                }).toList();
        return page(context, anchor, "candidates:" + type + ":" + status, items, cursor, limit, "AUTHOR_PROPOSALS");
    }

    public DetailPage candidate(MangaAgentRuntimeContext context, Long candidateId, String cursor, Integer limit) {
        var anchor = scope(context, null);
        var candidate = scopedCandidates(context).stream().filter(c -> Objects.equals(candidateId, c.getId())).findFirst()
                .orElseThrow(() -> new BusinessException(404, "Candidate is unavailable in this chapter"));
        var view = candidates.detail(context.storyId(), candidate.getId(), context.userId());
        var content = map("candidate_id", view.id(), "version", view.version(), "type", view.knowledgeType(),
                "title", view.title(), "body", view.body(), "summary", view.summary(), "structured_data", view.structuredData(),
                "status", view.status(), "is_confirmed_fact", false, "target_unit_id", view.targetUnitId(),
                "base_version", view.baseVersion(), "source_hash", text(candidate.getSourceHash()),
                "current_source_hash", view.currentSourceHash(), "source_stale", view.sourceStale(),
                "source_chapter_id", context.chapterId(), "validation_issues", view.validationIssues());
        // Source snapshots may hold entire manuscripts; return identity/hash rather than duplicating them.
        return detail(context, anchor, "candidate:" + candidateId, map("candidate_id", candidateId, "version", view.version()), content, cursor, limit);
    }

    private List<KnowledgeCandidate> scopedCandidates(MangaAgentRuntimeContext context) {
        return candidateRepository.findByStoryIdAndUserIdOrderByCreatedAtDesc(context.storyId(), context.userId()).stream()
                .filter(c -> c.getChapter() != null && Objects.equals(c.getChapter().getId(), context.chapterId())).toList();
    }

    private Chapter scope(MangaAgentRuntimeContext context, Long requestedChapter) {
        if (context == null || context.userId() == null || context.storyId() == null || context.chapterId() == null
                || context.requestId() == null || context.conversationId() == null) throw new BusinessException(403, "SCOPE_DENIED");
        var current = chapters.requireVisible(context.chapterId(), context.userId());
        if (!Objects.equals(current.getStory().getId(), context.storyId())) throw new BusinessException(403, "SCOPE_DENIED");
        if (requestedChapter == null || requestedChapter.equals(current.getId())) return current;
        var selected = chapters.requireVisible(requestedChapter, context.userId());
        if (!Objects.equals(selected.getStory().getId(), context.storyId())) throw new BusinessException(403, "Cross-story reads are forbidden");
        return selected;
    }

    private void requireKnowingCharacter(MangaAgentRuntimeContext context, Chapter anchor, Long character) {
        if (character == null) return;
        boolean revealed = facts.project(context.storyId(), anchor.getId(), null).stream()
                .filter(row -> "CHARACTER_CARD".equals(row.get("type")))
                .anyMatch(row -> character.equals(id(asMap(row.get("structuredData")).get("character_id"))));
        if (!revealed) throw new BusinessException(404, "Knowing character is not revealed in this chapter");
    }

    private List<KnowledgeFactService.Fact> visibleFacts(MangaAgentRuntimeContext context, Chapter anchor, Long knowing) {
        return facts.all(context.storyId(), anchor.getId(), knowing);
    }

    private List<Map<String,Object>> sourceReferences(MangaAgentRuntimeContext context, Chapter anchor, List<KnowledgeFactService.Fact> revisions) {
        var permitted = new HashSet<Long>(); revisions.forEach(f -> permitted.add(f.revisionId()));
        return jdbc.queryForList("""
                SELECT fs.revision_id,s.id AS source_id,s.source_type,s.chapter_id,s.source_version,s.source_hash
                FROM knowledge_fact_sources fs JOIN knowledge_sources s ON s.id=fs.source_id AND s.story_id=fs.story_id
                JOIN chapters c ON c.id=s.chapter_id AND c.story_id=s.story_id
                WHERE fs.story_id=? AND c.chapter_number<=? ORDER BY fs.revision_id,s.id
                """, context.storyId(), anchor.getChapterNumber()).stream()
                .filter(row -> permitted.contains(((Number)row.get("revision_id")).longValue())).toList();
    }

    private ReadPage page(MangaAgentRuntimeContext context, Chapter anchor, String purpose, List<Map<String,Object>> items,
                          String cursor, Integer requestedLimit, String retrieval) {
        int limit = limit(requestedLimit, 20, 50), offset = offset(context, anchor, purpose, schema.hash(items), cursor);
        if (offset > items.size()) throw new BusinessException(400, "Invalid page offset");
        int end = Math.min(items.size(), offset + limit);
        String snapshot = schema.hash(items);
        return new ReadPage(context.storyId(), anchor.getId(), context.chapterId(), List.copyOf(items.subList(offset, end)), items.size(),
                snapshot, end < items.size(), end < items.size() ? cursor(context, anchor, purpose, snapshot, end) : "", retrieval);
    }

    private DetailPage detail(MangaAgentRuntimeContext context, Chapter anchor, String purpose, Map<String,Object> reference,
                              Object content, String cursor, Integer requestedLimit) {
        String json = schema.write(content), snapshot = ToolIdempotencyService.sha256(json);
        int limit = limit(requestedLimit, 8000, 8000), start = offset(context, anchor, purpose, snapshot, cursor);
        if (start > json.length() || start > 0 && start < json.length() && Character.isLowSurrogate(json.charAt(start)))
            throw new BusinessException(400, "Invalid detail offset");
        int end = Math.min(json.length(), start + limit);
        if (end < json.length() && end > start && Character.isHighSurrogate(json.charAt(end - 1)) && Character.isLowSurrogate(json.charAt(end))) end--;
        if (end == start && end < json.length()) end = Math.min(json.length(), start + 2);
        return new DetailPage(context.storyId(), anchor.getId(), context.chapterId(), reference, snapshot, start, end, json.length(),
                "UTF16_CHARACTERS", json.substring(start, end), end < json.length(), end < json.length() ? cursor(context, anchor, purpose, snapshot, end) : "");
    }

    private int offset(MangaAgentRuntimeContext context, Chapter anchor, String purpose, String snapshot, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        if (cursor.length() > 4096) throw new BusinessException(400, "Invalid cursor");
        try {
            var payload = CURSORS.readValue(Base64.getUrlDecoder().decode(cursor), new TypeReference<Map<String,Object>>() {});
            if (!Objects.equals(binding(context, anchor, purpose), payload.get("binding")) || !snapshot.equals(payload.get("snapshot")))
                throw new BusinessException(409, "READ_SNAPSHOT_CHANGED: restart the same scoped query");
            if (!(payload.get("offset") instanceof Number value)) throw new IllegalArgumentException();
            int offset = Math.toIntExact(value.longValue());
            if (offset <= 0 || value.doubleValue() != offset) throw new IllegalArgumentException();
            return offset;
        } catch (BusinessException error) { throw error; }
        catch (Exception error) { throw new BusinessException(400, "Invalid cursor"); }
    }

    private String cursor(MangaAgentRuntimeContext context, Chapter anchor, String purpose, String snapshot, int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(schema.write(map("binding", binding(context, anchor, purpose),
                "snapshot", snapshot, "offset", offset)).getBytes(StandardCharsets.UTF_8));
    }
    private String binding(MangaAgentRuntimeContext c, Chapter anchor, String purpose) {
        return ToolIdempotencyService.sha256(c.userId() + ":" + c.storyId() + ":" + c.chapterId() + ":" + c.conversationId()
                + ":" + c.requestId() + ":" + anchor.getId() + ":" + purpose);
    }
    private static int limit(Integer value, int fallback, int max) {
        if (value == null) return fallback;
        if (value < 1 || value > max) throw new BusinessException(400, "limit must be between 1 and " + max);
        return value;
    }
    private static boolean involves(Map<String,Object> payload, Long character) {
        var data = KnowledgeFactService.structured(payload); if (data.isEmpty()) data = payload;
        for (String key : List.of("character_id", "source_character_id", "target_character_id")) if (Objects.equals(character, id(data.get(key)))) return true;
        for (String key : List.of("participant_character_ids", "related_character_ids"))
            if (data.get(key) instanceof List<?> ids && ids.stream().anyMatch(value -> Objects.equals(character, id(value)))) return true;
        return false;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> asMap(Object value) {
        return value instanceof Map<?,?> ? (Map<String,Object>)value : Map.of();
    }
    private static String text(Object value) { return Objects.toString(value, ""); }
    private static String excerpt(String text, int length) {
        int end = Math.min(text.length(), length);
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
    private static Map<String,Object> map(Object... pairs) {
        var result = new LinkedHashMap<String,Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i].toString(), pairs[i + 1]);
        return result;
    }
}
