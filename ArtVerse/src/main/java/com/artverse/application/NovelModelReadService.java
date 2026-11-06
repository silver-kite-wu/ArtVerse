package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentRun;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Full current-novel receipts become usable only after the exact native result reaches a model adapter. */
@Service
@RequiredArgsConstructor
public class NovelModelReadService {
    private final MangaAgentRunService runs;
    private final MangaAgentRunRepository repository;
    private final ChapterRepository chapters;
    private final ObjectMapper mapper;

    @Transactional
    public Map<String,Object> readCurrent(MangaAgentRuntimeContext context) {
        return readCurrent(context, null, null, null);
    }

    @Transactional
    public Map<String,Object> readCurrent(MangaAgentRuntimeContext context, Long targetChapter, String cursor, Integer requestedLimit) {
        active(context);
        var chapter = chapter(context, targetChapter == null ? context.chapterId() : targetChapter, false);
        int limit = requestedLimit == null ? 8000 : requestedLimit;
        if (limit < 1 || limit > 8000) throw invalid();
        var baseline = baseline(context, chapter); int offset = 0;
        if (cursor != null) {
            var value = decode(cursor);
            if (!baseline.get("read_ref").equals(value.get("read_ref"))) throw denied();
            offset = position(value.get("offset"));
        }
        String content = text(chapter); if (offset > content.length()) throw invalid();
        int end = Math.min(content.length(), offset + limit);
        var receipt = new LinkedHashMap<>(baseline); receipt.put("from", offset); receipt.put("to", end);
        receipt.put("complete", offset == 0 && end == content.length());
        var result = new LinkedHashMap<String,Object>();
        result.put("story_id", context.storyId()); result.put("chapter_id", chapter.getId());
        result.put("chapter_number", chapter.getChapterNumber() == null ? 0 : chapter.getChapterNumber());
        result.put("chapter_display_name", "第" + chapter.getChapterNumber() + "章");
        result.put("chapter_version", version(chapter)); result.put("novel_content", content.substring(offset, end));
        result.put("content_length", content.length()); result.put("has_more", end < content.length());
        result.put("next_cursor", end < content.length() ? encode(Map.of("read_ref", baseline.get("read_ref"), "offset", end)) : "");
        result.put("historical_target", !context.chapterId().equals(chapter.getId()));
        result.put("read_receipt", receipt);
        return result;
    }

    @Transactional
    public void recordReadResult(MangaAgentRuntimeContext context, String callId, Map<String,Object> nativeResult) {
        if (callId == null || callId.isBlank()) throw denied();
        var run = active(context); var result = data(nativeResult); var receipt = map(result.get("read_receipt"));
        if (!(receipt.get("chapter_id") instanceof Number chapterId) || chapterId.longValue() <= 0) throw denied();
        var chapter = chapter(context, chapterId.longValue(), false);
        var expected = baseline(context, chapter); String content = text(chapter);
        int from = position(receipt.get("from")), to = position(receipt.get("to"));
        if (from > to || to > content.length() || to - from > 8000) throw denied();
        for (var field : expected.entrySet()) if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(receipt.get(field.getKey())))) throw denied();
        if (!Objects.equals(result.get("novel_content"), content.substring(from, to))) throw denied();
        var attributes = mapJson(run.getRunAttributesJson()); var reads = map(attributes.get("novel_reads"));
        String ref = String.valueOf(expected.get("read_ref")); var read = map(reads.get(ref));
        if (read.isEmpty()) read.putAll(expected);
        var calls = map(read.get("calls"));
        var formed = Map.of("result_hash", evidenceHash(result), "phase", "RESULT_FORMED", "from", from, "to", to);
        if (calls.containsKey(callId) && !json(formed).equals(json(calls.get(callId)))) throw denied();
        calls.put(callId, formed); read.put("calls", calls); reads.put(ref, read);
        attributes.put("novel_reads", reads); run.setRunAttributesJson(json(attributes)); repository.save(run);
    }

    @Transactional
    public void validateModelReadInput(MangaAgentRuntimeContext context, List<StoryboardArtifactService.ModelReadInput> inputs) {
        var run = active(context); var attributes = mapJson(run.getRunAttributesJson());
        var reads = map(attributes.get("novel_reads"));
        // Native history can contain reads from earlier requests or steps. They remain visible
        // to the model but are not receipts for this attempt, so there is nothing to validate.
        if (!verifyInputs(context, reads, inputs, null, null)) return;
        attributes.put("novel_reads", reads);
        run.setRunAttributesJson(json(attributes));
        repository.save(run);
    }

    @Transactional
    public void recordModelReadInput(MangaAgentRuntimeContext context, UUID attempt, String inputHash,
                                     List<StoryboardArtifactService.ModelReadInput> inputs) {
        if (attempt == null || inputHash == null || !inputHash.matches("[a-f0-9]{64}")) throw denied();
        var run = active(context); var attributes = mapJson(run.getRunAttributesJson());
        var reads = map(attributes.get("novel_reads"));
        if (!verifyInputs(context, reads, inputs, attempt, inputHash)) return;
        attributes.put("novel_reads", reads); run.setRunAttributesJson(json(attributes)); repository.save(run);
    }

    /** Called inside restore's write transaction; current chapter and receipt must still agree. */
    @Transactional
    public long requireCurrentBaseline(MangaAgentRuntimeContext context) {
        var run = active(context); var chapter = chapter(context, context.chapterId(), true);
        var expected = baseline(context, chapter);
        var read = map(map(mapJson(run.getRunAttributesJson()).get("novel_reads")).get(expected.get("read_ref")));
        for (var field : expected.entrySet()) if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(read.get(field.getKey())))) throw incomplete();
        var handoffs = map(read.get("model_inputs"));
        var ranges = handoffs.values().stream().map(this::map)
                .filter(value -> "HANDED_TO_MODEL_ADAPTER".equals(value.get("phase")))
                .sorted(Comparator.comparingInt(value -> position(value.get("from")))).toList();
        if (ranges.isEmpty()) throw incomplete();
        int covered = 0;
        for (var range : ranges) {
            int from = position(range.get("from")), to = position(range.get("to"));
            if (from > covered) throw incomplete(); covered = Math.max(covered, to);
        }
        if (covered != text(chapter).length()) throw incomplete();
        return version(chapter);
    }

    private boolean verifyInputs(MangaAgentRuntimeContext context, Map<String,Object> reads,
            List<StoryboardArtifactService.ModelReadInput> inputs, UUID attempt, String inputHash) {
        boolean found = false;
        for (var input : inputs) {
            var result = data(input.result()); var receipt = map(result.get("read_receipt"));
            if (!context.requestId().toString().equals(receipt.get("request_id")) || !context.stepId().equals(receipt.get("step_id"))) continue;
            if (!context.conversationId().toString().equals(receipt.get("conversation_id"))) throw denied();
            if (!(receipt.get("chapter_id") instanceof Number chapterId) || chapterId.longValue() <= 0) throw denied();
            var chapter = chapter(context, chapterId.longValue(), false);
            var expected = baseline(context, chapter); String content = text(chapter);
            for (var field : expected.entrySet())
                if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(receipt.get(field.getKey())))) throw denied();
            int from = position(receipt.get("from")), to = position(receipt.get("to"));
            if (from > to || to > content.length() || to - from > 8000
                    || !Objects.equals(result.get("novel_content"), content.substring(from, to))) throw denied();
            String ref = Objects.toString(expected.get("read_ref"), ""); var read = map(reads.get(ref));
            if (read.isEmpty()) read.putAll(expected);
            for (var field : expected.entrySet())
                if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(read.get(field.getKey())))) throw denied();
            var calls = map(read.get("calls"));
            var formed = map(calls.get(input.callId()));
            var current = Map.of("result_hash", evidenceHash(result), "phase", "RESULT_FORMED", "from", from, "to", to);
            if (formed.isEmpty()) {
                calls.put(input.callId(), current);
                read.put("calls", calls);
                reads.put(ref, read);
            } else if (!json(formed).equals(json(current))) throw denied();
            found = true;
            if (attempt == null) continue;
            var handoffs = map(read.get("model_inputs"));
            var handoff = Map.of("phase", "HANDED_TO_MODEL_ADAPTER", "input_hash", inputHash, "call_id", input.callId(),
                    "from", formed.get("from"), "to", formed.get("to"));
            String key = attempt + ":" + input.callId();
            if (handoffs.containsKey(key) && !json(handoffs.get(key)).equals(json(handoff))) throw denied();
            handoffs.put(key, handoff); read.put("model_inputs", handoffs); reads.put(ref, read);
        }
        return found;
    }

    private MangaAgentRun active(MangaAgentRuntimeContext context) {
        runs.lockAutomationExecution(context);
        var run = repository.findForUpdate(context.userId(), context.chapterId(), context.requestId()).orElseThrow(NovelModelReadService::denied);
        runs.requireToolExecution(context, mapJson(run.getRunAttributesJson()).get("review_result") instanceof Map<?,?>);
        return run;
    }
    private Chapter chapter(MangaAgentRuntimeContext context, Long target, boolean lock) {
        if (target == null || target <= 0) throw invalid();
        var chapter = (lock ? chapters.findByIdAndUserIdForUpdate(target, context.userId())
                : chapters.findByIdAndUserId(target, context.userId())).orElseThrow(NovelModelReadService::denied);
        if (!context.storyId().equals(chapter.getStory().getId())) throw denied(); return chapter;
    }
    private Map<String,Object> baseline(MangaAgentRuntimeContext context, Chapter chapter) {
        var value = new LinkedHashMap<String,Object>();
        value.put("schema", "novel-read-v1"); value.put("request_id", context.requestId().toString());
        value.put("conversation_id", context.conversationId().toString()); value.put("step_id", context.stepId());
        value.put("story_id", context.storyId()); value.put("chapter_id", chapter.getId());
        value.put("chapter_version", version(chapter)); value.put("content_hash", ToolIdempotencyService.sha256(text(chapter)));
        value.put("content_length", text(chapter).length());
        value.put("read_ref", ToolIdempotencyService.sha256(context.userId() + ":" + json(value))); return value;
    }
    private long version(Chapter chapter) { return chapter.getVersion() == null ? 0L : chapter.getVersion(); }
    private String text(Chapter chapter) { return Objects.toString(chapter.getNovelContent(), ""); }
    private Map<String,Object> data(Map<String,Object> value) { return value.get("data") instanceof Map<?,?> ? map(value.get("data")) : value; }
    private Map<String,Object> map(Object value) { var result = new LinkedHashMap<String,Object>(); if (value instanceof Map<?,?> source) source.forEach((key,item) -> result.put(String.valueOf(key), item)); return result; }
    private Map<String,Object> mapJson(String value) { try { return value == null ? new LinkedHashMap<>() : mapper.readValue(value, new TypeReference<LinkedHashMap<String,Object>>() {}); } catch (Exception e) { throw denied(); } }
    private String json(Object value) { try { return mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String hash(Object value) { return ToolIdempotencyService.sha256(json(value)); }
    private String evidenceHash(Map<String,Object> result) {
        var content = map(result);
        for (String field : List.of("success", "errorCode", "retryable", "auditId", "resultHash")) content.remove(field);
        return hash(content);
    }
    private int position(Object value) { if (!(value instanceof Number number) || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE || number.doubleValue() != number.longValue()) throw invalid(); return number.intValue(); }
    private String encode(Object value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(json(value).getBytes(StandardCharsets.UTF_8)); }
    private Map<String,Object> decode(String value) { try { if (value.length() > 2048) throw invalid(); return mapJson(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)); } catch (RuntimeException error) { throw invalid(); } }
    private static BusinessException invalid() { return new BusinessException(400, "小说目标章节或分页参数无效"); }
    private static BusinessException denied() { return new BusinessException(409, "READ_RECEIPT_UNAVAILABLE: novel native result or scope differs"); }
    private static BusinessException incomplete() { return new BusinessException(409, "INCOMPLETE_READ: read the complete current novel context and continue through a model before restoring a revision"); }
}
