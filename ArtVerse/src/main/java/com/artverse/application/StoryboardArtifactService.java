package com.artverse.application;

import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentToolAttempt;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaAgentRunArtifactRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.artverse.application.tools.MangaChapterContextReader;
import com.artverse.application.tools.MangaContextTools.Section;

import java.time.OffsetDateTime;
import java.util.*;

/** Artifact-specific storyboard drafts and author decisions, serialized with run cancellation. */
@Service
@RequiredArgsConstructor
public class StoryboardArtifactService {
    private static final String ARTIFACT_TYPE = "STORYBOARD_DRAFT";
    private static final String SCHEMA = "2";
    private static final String CONFIRM_PREFIX = "storyboard-overwrite:";
    private static final int MAX_DRAFT_ATTEMPTS = 3;
    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunArtifactRepository artifactRepository;
    private final ChapterAccessService chapterAccessService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final SceneService sceneService;
    private final ObjectMapper objectMapper;
    private final AgentRunLeaseService leaseService;
    private final ChapterRepository chapterRepository;
    private final MangaAgentRunService runService;
    private AgentToolEffectService effects;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEffects(AgentToolEffectService service) { this.effects = Objects.requireNonNull(service); }

    public record ArtifactView(UUID artifactId, UUID requestId, String type, String status,
                               String schemaVersion, Map<String,Object> payload,
                               Map<String,Object> evaluation, String checksum) {}

    /** Serial lower bound from the current chapter, observed ranges and exact-step artifacts. */
    public record ReadPlan(String readRef, int remainingReadCalls, boolean reusableValidatedDraft,
                           boolean committedForStep, boolean overwriteConfirmationRequired,
                           int minimumModelCalls, int minimumToolCalls) {}

    public record ModelReadInput(String callId, Map<String,Object> result) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReadPlan remainingReadPlan(MangaAgentRuntimeContext context) {
        return previewModelReadPlan(context, List.of());
    }

    /** Preview the exact frozen input without recording an adapter handoff or mutating the run. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReadPlan previewModelReadPlan(MangaAgentRuntimeContext context, List<ModelReadInput> inputs) {
        MangaAgentRun run = activeRun(context, true, false);
        Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
        requireChapter(chapter, context.storyId(), context.userId());
        Map<String,Object> baseline = readBaseline(context, chapter);
        String ref = baseline.get("read_ref").toString();
        var reads = new LinkedHashMap<>(map(readMap(run.getRunAttributesJson()).get("storyboard_reads")));
        applyModelReadInputs(context, reads, inputs, null, null);
        MangaAgentRunArtifact reusable = null;
        for (MangaAgentRunArtifact artifact : artifactRepository.findByRunIdOrderByCreatedAtAsc(run.getId())) {
            if (!ARTIFACT_TYPE.equals(artifact.getArtifactType()) || !SCHEMA.equals(artifact.getSchemaVersion())
                    || !Objects.equals(artifact.getChecksum(), checksum(artifact.getPayload()))) continue;
            Map<String,Object> payload = readMap(artifact.getPayload());
            if (!Objects.equals(context.stepId(), payload.get("step_id"))
                    || !Objects.equals(context.requestId().toString(), payload.get("request_id"))) continue;
            if ("COMMITTED".equals(artifact.getStatus())) return new ReadPlan(ref, 0, false, true, false, 1, 0);
            if ("VALIDATED".equals(artifact.getStatus()) && ref.equals(payload.get("read_ref"))
                    && number(payload.get("base_version")) == version(chapter)
                    && Objects.equals(baseline.get("source_hash"), payload.get("source_hash"))
                    && Objects.equals(baseline.get("storyboard_hash"), payload.get("storyboard_hash"))
                    && Boolean.TRUE.equals(readMap(artifact.getEvaluation()).get("structure_valid"))) reusable = artifact;
        }
        boolean needsConfirmation = !storyboard(chapter).isBlank();
        if (reusable != null) {
            Map<String,Object> evaluation = readMap(reusable.getEvaluation());
            if (evaluation.get("authorization_consumed_at") == null
                    && authorized(map(evaluation.get("overwrite_authorization")), reusable, context.userId(), readMap(reusable.getPayload())))
                needsConfirmation = false;
            int tools = 1 + (needsConfirmation ? 1 : 0);
            return new ReadPlan(ref, 0, true, false, needsConfirmation, tools + 1, tools);
        }
        Map<String,Object> read = map(reads.get(ref));
        if (!read.isEmpty()) for (var field : baseline.entrySet()) {
            if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(read.get(field.getKey())))) throw stale();
        }
        int remaining = unreadPages(source(chapter), "source", read)
                + unreadPages(storyboard(chapter), "storyboard", read);
        if (map(read.get("model_inputs")).isEmpty()) remaining = Math.max(1, remaining);
        int tools = remaining + 2 + (needsConfirmation ? 1 : 0);
        return new ReadPlan(ref, remaining, false, false, needsConfirmation, tools + 1, tools);
    }

    private int unreadPages(String full, String section, Map<String,Object> read) {
        Object observed = read.get(section + "_ranges");
        List<List<Integer>> ranges = new ArrayList<>();
        if (observed instanceof List<?> values) for (Object value : values) {
            if (!(value instanceof List<?> range) || range.size() != 2) throw denied();
            int start = Math.toIntExact(number(range.getFirst())), end = Math.toIntExact(number(range.get(1)));
            if (start < 0 || end < start || end > full.length()) throw denied();
            ranges.add(List.of(start, end));
        }
        ranges.sort(Comparator.comparingInt(range -> range.getFirst()));
        var reachable = new TreeSet<Integer>(); reachable.add(0);
        for (Object value : map(read.get("calls")).values()) {
            Map<String,Object> call = map(value);
            if (section.equals(call.get("section")) && !Objects.toString(call.get("next_cursor"), "").isEmpty()
                    && wasHandedOff(read, call)) reachable.add(Math.toIntExact(number(call.get("end"))));
        }
        int calls = 0, missing;
        while ((missing = firstMissing(ranges, full.length())) < full.length()) {
            int start = reachable.floor(missing), end = MangaChapterContextReader.pageEnd(full, start, 8000);
            ranges.add(List.of(start, end));
            ranges.sort(Comparator.comparingInt(range -> range.getFirst()));
            reachable.add(end);
            calls++;
        }
        return calls;
    }

    private int firstMissing(List<List<Integer>> ranges, int length) {
        int offset = 0;
        for (List<Integer> range : ranges) {
            if (range.getFirst() > offset) break;
            offset = Math.max(offset, range.get(1));
        }
        return Math.min(offset, length);
    }

    private boolean wasHandedOff(Map<String,Object> read, Map<String,Object> call) {
        String id = Objects.toString(call.get("call_id"), "");
        return map(read.get("model_inputs")).values().stream().map(StoryboardArtifactService::map)
                .anyMatch(input -> input.get("call_ids") instanceof List<?> ids && ids.contains(id));
    }

    /** Pure receipt projection; a result event alone is not a model-input handoff. */
    public Map<String,Object> readReceipt(MangaAgentRuntimeContext context, Chapter chapter, String section,
                                          int start, int end, String deliveredText) {
        requireChapter(chapter, context.storyId(), context.userId());
        if (!Objects.equals(chapter.getId(), context.chapterId())) throw denied();
        Map<String,Object> receipt = new LinkedHashMap<>(readBaseline(context, chapter));
        receipt.put("section", section); receipt.put("start", start); receipt.put("end", end);
        receipt.put("slice_hash", ToolIdempotencyService.sha256(deliveredText));
        return Map.copyOf(receipt);
    }

    /** Formed after a matching successful ToolResultEnd; coverage is granted only at adapter handoff. */
    @Transactional
    public void recordReadResult(MangaAgentRuntimeContext context, String callId, Map<String,Object> toolResult) {
        if (callId == null || callId.isBlank() || callId.length() > 256) throw denied();
        Map<String,Object> result = toolResult.containsKey("data") ? map(toolResult.get("data")) : toolResult;
        Map<String,Object> receipt = map(result.get("read_receipt"));
        MangaAgentRun run = activeRun(context, false);
        Chapter chapter = lockedChapter(context.chapterId(), context.userId(), context.storyId());
        Map<String,Object> baseline = readBaseline(context, chapter);
        for (var field : baseline.entrySet()) if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(receipt.get(field.getKey())))) throw stale();
        String section = Objects.toString(receipt.get("section"), "");
        String key = switch (section) {
            case "overview" -> "source_excerpt";
            case "source" -> "source_text";
            case "storyboard" -> "storyboard_text";
            default -> throw denied();
        };
        if (!(result.get(key) instanceof String actual)) throw denied();
        String full = "storyboard".equals(section) ? storyboard(chapter) : source(chapter);
        int start = Math.toIntExact(number(receipt.get("start"))), end = Math.toIntExact(number(receipt.get("end")));
        if (start < 0 || end < start || end > full.length() || !actual.equals(full.substring(start, end))
                || !ToolIdempotencyService.sha256(actual).equals(receipt.get("slice_hash"))
                || "overview".equals(section) && (start != 0 || end > 1200)) throw denied();
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var reads = new LinkedHashMap<>(map(attributes.get("storyboard_reads")));
        String ref = (String) baseline.get("read_ref");
        var read = new LinkedHashMap<>(map(reads.get(ref)));
        if (read.isEmpty()) read.putAll(baseline);
        else for (var field : baseline.entrySet()) if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(read.get(field.getKey())))) throw denied();
        var calls = new LinkedHashMap<>(map(read.get("calls")));
        String next = Objects.toString(result.get("next_cursor"), "");
        if (!next.isEmpty() && ("overview".equals(section) || end >= full.length()
                || !next.equals(MangaChapterContextReader.cursor(ref, Section.valueOf(section), end)))) throw denied();
        Map<String,Object> observation = Map.of("call_id", callId, "section", section, "start", start, "end", end,
                "slice_hash", receipt.get("slice_hash"), "result_hash", checksum(writeJson(toolResult)),
                "next_cursor", next, "phase", "RESULT_FORMED");
        if (calls.containsKey(callId) && !writeJson(calls.get(callId)).equals(writeJson(observation))) {
            // Compare canonical maps: jsonb reorders object fields.
            if (!checksum(writeJson(calls.get(callId))).equals(checksum(writeJson(observation)))) throw denied();
        }
        calls.put(callId, observation); read.put("calls", calls);
        String ranges = "storyboard".equals(section) ? "formed_storyboard_ranges" : "formed_source_ranges";
        read.put(ranges, mergeRanges(read.get(ranges), start, end));
        reads.put(ref, read);
        attributes.put("storyboard_reads", reads);
        run.setRunAttributesJson(writeJson(attributes));
        runRepository.save(run);
    }

    /** Validate exactly the detached native ToolResultBlocks before calling a model adapter. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void validateModelReadInput(MangaAgentRuntimeContext context, List<ModelReadInput> inputs) {
        MangaAgentRun run = activeRun(context, false, false);
        var reads = new LinkedHashMap<>(map(readMap(run.getRunAttributesJson()).get("storyboard_reads")));
        applyModelReadInputs(context, reads, inputs, null, null);
    }

    /** Called only after the reserved native adapter invocation accepted the exact frozen input. */
    @Transactional
    public void recordModelReadInput(MangaAgentRuntimeContext context, UUID attempt, String inputHash, List<ModelReadInput> inputs) {
        if (attempt == null || inputHash == null || !inputHash.matches("[a-f0-9]{64}")) throw denied();
        MangaAgentRun run = activeRun(context, false);
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var reads = new LinkedHashMap<>(map(attributes.get("storyboard_reads")));
        if (!applyModelReadInputs(context, reads, inputs, attempt, inputHash)) return;
        attributes.put("storyboard_reads", reads); run.setRunAttributesJson(writeJson(attributes)); runRepository.save(run);
    }

    private boolean applyModelReadInputs(MangaAgentRuntimeContext context, Map<String,Object> reads,
                                         List<ModelReadInput> inputs, UUID attempt, String inputHash) {
        Map<String,List<String>> grouped = new LinkedHashMap<>();
        for (ModelReadInput input : inputs) {
            Map<String,Object> result = input.result().containsKey("data") ? map(input.result().get("data")) : input.result();
            Map<String,Object> receipt = map(result.get("read_receipt"));
            // Native conversation history may legitimately contain earlier requests or Director steps.
            if (!context.requestId().toString().equals(receipt.get("request_id"))
                    || !Objects.equals(context.stepId(), receipt.get("step_id"))) continue;
            if (!context.conversationId().toString().equals(receipt.get("conversation_id"))) throw denied();
            String ref = Objects.toString(receipt.get("read_ref"), "");
            var read = new LinkedHashMap<>(map(reads.get(ref)));
            Map<String,Object> formed = map(map(read.get("calls")).get(input.callId()));
            if (formed.isEmpty() || !Objects.equals(formed.get("result_hash"), checksum(writeJson(input.result()))))
                throw new BusinessException(409, "READ_RECEIPT_UNAVAILABLE: model input does not match a formed native result");
            String ranges = "storyboard".equals(formed.get("section")) ? "storyboard_ranges" : "source_ranges";
            read.put(ranges, mergeRanges(read.get(ranges), Math.toIntExact(number(formed.get("start"))), Math.toIntExact(number(formed.get("end")))));
            reads.put(ref, read); grouped.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(input.callId());
        }
        grouped.forEach((ref, ids) -> {
            var read = new LinkedHashMap<>(map(reads.get(ref)));
            var handoffs = new LinkedHashMap<>(map(read.get("model_inputs")));
            String key = attempt == null ? "pending-preview" : attempt.toString();
            Map<String,Object> handoff = Map.of("phase", attempt == null ? "PREVIEW" : "HANDED_TO_MODEL_ADAPTER",
                    "input_hash", inputHash == null ? "pending-preview" : inputHash, "call_ids", ids.stream().distinct().sorted().toList());
            if (handoffs.containsKey(key) && !checksum(writeJson(map(handoffs.get(key)))).equals(checksum(writeJson(handoff)))) throw denied();
            handoffs.put(key, handoff); read.put("model_inputs", handoffs); reads.put(ref, read);
        });
        return !grouped.isEmpty();
    }

    @Transactional
    public ArtifactView createStructuredDraft(Object pages, String readRef, MangaAgentRuntimeContext context) {
        return createStructuredDraft(pages, readRef, context, null);
    }

    @Transactional
    public ArtifactView createStructuredDraft(Object pages, String readRef, MangaAgentRuntimeContext context, AgentToolAttempt attempt) {
        MangaAgentRun run = activeRun(context, true);
        Chapter chapter = lockedChapter(context.chapterId(), context.userId(), context.storyId());
        Map<String,Object> read = map(map(readMap(run.getRunAttributesJson()).get("storyboard_reads")).get(readRef));
        if (read.isEmpty()) throw new BusinessException(409, "Read the chapter context before creating a storyboard draft");
        Map<String,Object> current = readBaseline(context, chapter);
        if (!Objects.equals(readRef, current.get("read_ref"))) throw stale();
        for (var field : current.entrySet()) if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(read.get(field.getKey())))) throw denied();
        if (map(read.get("model_inputs")).isEmpty() || !covered(read.get("source_ranges"), source(chapter).length())
                || !covered(read.get("storyboard_ranges"), storyboard(chapter).length()))
            throw new BusinessException(409, "INCOMPLETE_READ: read every source and storyboard page using the same read_ref before drafting");
        List<String> scenes = structuredStoryboardService.normalize(pages, chapter.getImageCount());
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages); payload.put("scenes", scenes);
        payload.put("chapter_version", version(chapter)); payload.put("base_version", version(chapter));
        payload.put("chapter_id", chapter.getId()); payload.put("story_id", context.storyId());
        payload.put("step_id", context.stepId()); payload.put("request_id", context.requestId().toString());
        payload.put("read_ref", read.get("read_ref"));
        payload.put("source_hash", read.get("source_hash")); payload.put("storyboard_hash", read.get("storyboard_hash"));
        String payloadJson = writeJson(payload), checksum = checksum(payloadJson);
        var previous = artifactRepository.findFirstByRunIdAndChecksumOrderByCreatedAtDesc(run.getId(), checksum)
                .filter(artifact -> !"DISCARDED".equals(artifact.getStatus()));
        if (previous.isPresent()) return linked(toView(previous.get()), context, attempt, "STORYBOARD_DRAFT");
        long attempts = artifactRepository.countByRunIdAndArtifactType(run.getId(), ARTIFACT_TYPE);
        if (attempts >= MAX_DRAFT_ATTEMPTS) throw new BusinessException(409, "Storyboard rewrite limit reached");
        MangaAgentRunArtifact artifact = new MangaAgentRunArtifact();
        artifact.setRun(run); artifact.setArtifactType(ARTIFACT_TYPE); artifact.setStatus("VALIDATED");
        artifact.setSchemaVersion(SCHEMA); artifact.setPayload(payloadJson); artifact.setChecksum(checksum);
        artifact.setEvaluation(writeJson(Map.of("evaluator", "storyboard-structure-v2", "structure_valid", true,
                "expert_review_status", "NOT_REVIEWED", "issues", List.of(), "page_count", scenes.size(), "rewrite", attempts)));
        return linked(toView(artifactRepository.save(artifact)), context, attempt, "STORYBOARD_DRAFT");
    }

    @Transactional
    public ArtifactView commit(UUID artifactId, MangaAgentRuntimeContext context) {
        return commit(artifactId, context, null);
    }

    @Transactional
    public ArtifactView commit(UUID artifactId, MangaAgentRuntimeContext context, AgentToolAttempt attempt) {
        MangaAgentRun run = activeRun(context, true);
        MangaAgentRunArtifact artifact = scopedArtifact(run, artifactId, context.userId(), context.chapterId(), context.stepId());
        if ("COMMITTED".equals(artifact.getStatus())) return linked(toView(artifact), context, attempt, "STORYBOARD_COMMIT");
        if (!"VALIDATED".equals(artifact.getStatus())) throw new BusinessException(409, "Storyboard draft is not ready to commit");
        Map<String,Object> payload = readMap(artifact.getPayload());
        Chapter chapter = lockedChapter(context.chapterId(), context.userId(), context.storyId());
        requireDraftSource(payload, chapter);
        Map<String,Object> evaluation = new LinkedHashMap<>(readMap(artifact.getEvaluation()));
        if (!Boolean.TRUE.equals(evaluation.get("structure_valid"))) throw new BusinessException(409, "Storyboard structure is not valid");
        if (chapter.getScenesText() != null && !chapter.getScenesText().isBlank()) {
            Map<String,Object> authorization = map(evaluation.get("overwrite_authorization"));
            if (!authorized(authorization, artifact, context.userId(), payload)) {
                throw new AgentUserInputRequiredException(overwriteQuestion(artifact));
            }
            evaluation.put("authorization_consumed_at", OffsetDateTime.now().toString());
        }
        List<String> scenes = objectMapper.convertValue(payload.get("scenes"), new TypeReference<>() {});
        sceneService.updateScenes(chapter.getId(), scenes);
        chapterRepository.flush();
        evaluation.put("committed_version", version(chapter));
        artifact.setEvaluation(writeJson(evaluation)); artifact.setStatus("COMMITTED");
        // The run lock also serializes competing drafts/commits in this parent run.
        artifactRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(other -> !Objects.equals(other.getId(), artifact.getId()))
                .filter(other -> ARTIFACT_TYPE.equals(other.getArtifactType()) && "VALIDATED".equals(other.getStatus()))
                .forEach(other -> other.setStatus("SUPERSEDED"));
        return linked(toView(artifactRepository.save(artifact)), context, attempt, "STORYBOARD_COMMIT");
    }

    private ArtifactView linked(ArtifactView artifact, MangaAgentRuntimeContext context, AgentToolAttempt attempt, String kind) {
        if (attempt != null) {
            if (effects == null) throw new BusinessException(503, "工具领域回执服务未配置");
            effects.record(attempt, context, kind, artifact.artifactId(), artifact.type(), artifact.status(), artifact.checksum(),
                    Map.of("base_version", artifact.payload().get("base_version"), "evaluation", artifact.evaluation()));
        }
        return artifact;
    }

    /** Called only by the authenticated author-decision path, never registered as a model tool. */
    @Transactional
    public void authorizeOverwrite(Long userId, Long chapterId, UUID requestId, UUID conversationId) {
        runService.lockAutomationAuthorDecision(userId, chapterId, requestId);
        MangaAgentRun run = runRepository.findForUpdate(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (run.getStatus() != MangaAgentRunStatus.WAITING_USER || run.getConversation() == null
                || !Objects.equals(conversationId, run.getConversation().getConversationUuid())) throw denied();
        AgentUserInputRequest waiting;
        try { waiting = objectMapper.readValue(run.getUserInputRequestJson(), AgentUserInputRequest.class); }
        catch (Exception error) { throw new BusinessException(409, "No valid storyboard confirmation is pending"); }
        if (!"MUTATION_CONFIRMATION".equals(waiting.purpose()) || waiting.reason() == null
                || !waiting.reason().startsWith(CONFIRM_PREFIX)) throw denied();
        String[] binding = waiting.reason().substring(CONFIRM_PREFIX.length()).split(":", -1);
        if (binding.length != 2) throw denied();
        UUID artifactId;
        try { artifactId = UUID.fromString(binding[0]); } catch (IllegalArgumentException error) { throw denied(); }
        MangaAgentRunArtifact artifact = scopedArtifact(run, artifactId, userId, chapterId, null);
        if (!Objects.equals(binding[1], artifact.getChecksum()) || !"VALIDATED".equals(artifact.getStatus())) throw denied();
        Map<String,Object> payload = readMap(artifact.getPayload());
        Chapter chapter = lockedChapter(chapterId, userId, run.getStory().getId());
        requireDraftSource(payload, chapter);
        Map<String,Object> evaluation = new LinkedHashMap<>(readMap(artifact.getEvaluation()));
        evaluation.put("overwrite_authorization", Map.of("actor_id", userId,
                "artifact_id", artifactId.toString(), "content_hash", artifact.getChecksum(),
                "request_id", requestId.toString(), "step_id", payload.get("step_id"),
                "base_version", payload.get("base_version"), "expires_at", OffsetDateTime.now().plusMinutes(30).toString()));
        artifact.setEvaluation(writeJson(evaluation));
        artifactRepository.save(artifact);
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> list(Long userId, Long chapterId, UUID requestId) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        return artifactRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream().map(this::toView).toList();
    }

    private Map<String,Object> readBaseline(MangaAgentRuntimeContext context, Chapter chapter) {
        if (context == null || context.userId() == null || context.requestId() == null || context.conversationId() == null
                || context.stepId() == null || context.stepId().isBlank()) throw denied();
        String source = source(chapter), storyboard = storyboard(chapter);
        Map<String,Object> baseline = new LinkedHashMap<>();
        baseline.put("receipt_schema", 2); baseline.put("request_id", context.requestId().toString());
        baseline.put("conversation_id", context.conversationId().toString()); baseline.put("step_id", context.stepId());
        baseline.put("story_id", context.storyId()); baseline.put("chapter_id", chapter.getId());
        baseline.put("chapter_version", version(chapter));
        baseline.put("source_hash", ToolIdempotencyService.sha256(source)); baseline.put("source_length", source.length());
        baseline.put("storyboard_hash", ToolIdempotencyService.sha256(storyboard)); baseline.put("storyboard_length", storyboard.length());
        baseline.put("read_ref", ToolIdempotencyService.sha256(context.userId() + ":" + checksum(writeJson(baseline))));
        return baseline;
    }

    private static String source(Chapter chapter) { return chapter.novelContentOrJoinedMessages(); }
    private static String storyboard(Chapter chapter) { return Objects.toString(chapter.getScenesText(), ""); }

    private void requireDraftSource(Map<String,Object> payload, Chapter chapter) {
        if (number(payload.get("base_version")) != version(chapter)
                || !Objects.equals(payload.get("source_hash"), ToolIdempotencyService.sha256(source(chapter)))
                || !Objects.equals(payload.get("storyboard_hash"), ToolIdempotencyService.sha256(storyboard(chapter)))) throw stale();
    }

    private List<List<Integer>> mergeRanges(Object previous, int start, int end) {
        List<List<Integer>> ranges = new ArrayList<>();
        if (previous instanceof List<?> old) for (Object value : old) {
            if (!(value instanceof List<?> range) || range.size() != 2) throw denied();
            ranges.add(List.of(Math.toIntExact(number(range.get(0))), Math.toIntExact(number(range.get(1)))));
        }
        ranges.add(List.of(start, end));
        ranges.sort(Comparator.comparingInt(range -> range.getFirst()));
        List<List<Integer>> merged = new ArrayList<>();
        for (List<Integer> range : ranges) {
            if (merged.isEmpty() || range.getFirst() > merged.getLast().get(1)) merged.add(range);
            else {
                List<Integer> last = merged.removeLast();
                merged.add(List.of(last.getFirst(), Math.max(last.get(1), range.get(1))));
            }
        }
        return List.copyOf(merged);
    }

    private static boolean covered(Object value, int length) {
        if (length == 0) return true;
        if (!(value instanceof List<?> ranges) || ranges.size() != 1 || !(ranges.getFirst() instanceof List<?> range)
                || range.size() != 2) return false;
        return number(range.getFirst()) == 0 && number(range.get(1)) == length;
    }

    private MangaAgentRun activeRun(MangaAgentRuntimeContext context, boolean write) {
        return activeRun(context, write, true);
    }

    private MangaAgentRun activeRun(MangaAgentRuntimeContext context, boolean write, boolean lock) {
        if (context == null || context.userId() == null || context.chapterId() == null || context.storyId() == null
                || context.requestId() == null || context.conversationId() == null
                || context.stepId() == null || context.stepId().isBlank()) throw denied();
        if (write && context.taskType() != AgentTaskType.MANGA_STORYBOARD) throw denied();
        // The parent execution is locked before the child/root, artifact and chapter rows.
        // Read-only planning deliberately does not acquire these execution locks.
        if (lock) runService.lockAutomationExecution(context);
        MangaAgentRun run = (lock ? runRepository.findForUpdate(context.userId(), context.chapterId(), context.requestId())
                : runRepository.findByUserIdAndChapterIdAndRequestId(context.userId(), context.chapterId(), context.requestId()))
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (run.getStory() == null || !Objects.equals(context.storyId(), run.getStory().getId())
                || run.getConversation() == null || !Objects.equals(context.conversationId(), run.getConversation().getConversationUuid()))
            throw denied();
        if (run.getStatus() != MangaAgentRunStatus.RUNNING || context.fencingToken() == null || context.fencingToken() <= 0
                || context.executionOwner() == null || context.executionOwner().isBlank()
                || !Objects.equals(context.executionOwner(), run.getOwnerInstanceId())
                || !leaseService.owns(run, context.fencingToken())) throw new BusinessException(409, "RUN_NOT_ACTIVE");
        return run;
    }

    private MangaAgentRunArtifact scopedArtifact(MangaAgentRun run, UUID id, Long actor, Long chapter, String step) {
        if (id == null) throw new BusinessException(400, "artifact_id is required");
        MangaAgentRunArtifact artifact = artifactRepository.findForCommit(id, actor, chapter)
                .orElseThrow(() -> new BusinessException(404, "Storyboard draft not found"));
        Map<String,Object> payload = readMap(artifact.getPayload());
        if (!Objects.equals(artifact.getRun().getId(), run.getId()) || !ARTIFACT_TYPE.equals(artifact.getArtifactType())
                || !SCHEMA.equals(artifact.getSchemaVersion()) || !Objects.equals(artifact.getChecksum(), checksum(artifact.getPayload()))
                || !Objects.equals(payload.get("request_id"), run.getRequestId().toString())
                || step != null && !Objects.equals(step, payload.get("step_id"))) throw denied();
        return artifact;
    }

    private boolean authorized(Map<String,Object> auth, MangaAgentRunArtifact artifact, Long actor, Map<String,Object> payload) {
        try {
            return number(auth.get("actor_id")) == actor && artifact.getArtifactUuid().toString().equals(auth.get("artifact_id"))
                    && artifact.getChecksum().equals(auth.get("content_hash"))
                    && artifact.getRun().getRequestId().toString().equals(auth.get("request_id"))
                    && Objects.equals(payload.get("step_id"), auth.get("step_id"))
                    && number(auth.get("base_version")) == number(payload.get("base_version"))
                    && OffsetDateTime.parse(String.valueOf(auth.get("expires_at"))).isAfter(OffsetDateTime.now());
        } catch (RuntimeException invalid) { return false; }
    }

    private AgentUserInputRequest overwriteQuestion(MangaAgentRunArtifact artifact) {
        return new AgentUserInputRequest("当前章节已有分镜。是否确认用此草稿覆盖？",
                List.of(new AgentUserInputRequest.Option("confirm", "确认覆盖", "只批准当前草稿及其章节基准", true),
                        new AgentUserInputRequest.Option("cancel", "取消操作", "保留当前分镜", false)),
                false, CONFIRM_PREFIX + artifact.getArtifactUuid() + ":" + artifact.getChecksum(), "MUTATION_CONFIRMATION");
    }

    private Chapter lockedChapter(Long chapterId, Long userId, Long storyId) {
        Chapter chapter = chapterRepository.findByIdAndUserIdForUpdate(chapterId, userId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        requireChapter(chapter, storyId, userId);
        return chapter;
    }

    private void requireChapter(Chapter chapter, Long storyId, Long userId) {
        chapterAccessService.ensureVisible(chapter, userId);
        if (chapter.getStory() == null || !Objects.equals(storyId, chapter.getStory().getId())) throw denied();
    }
    private static long version(Chapter chapter) { return chapter.getVersion() == null ? 0 : chapter.getVersion(); }
    private static long number(Object value) {
        if (!(value instanceof Number number)) throw new BusinessException(409, "Missing artifact version");
        return number.longValue();
    }
    private static BusinessException denied() { return new BusinessException(403, "SCOPE_DENIED: storyboard binding mismatch"); }
    private static BusinessException stale() { return new BusinessException(409, "STALE_VERSION: reread the chapter and regenerate the draft"); }

    private ArtifactView toView(MangaAgentRunArtifact artifact) {
        return new ArtifactView(artifact.getArtifactUuid(), artifact.getRun().getRequestId(), artifact.getArtifactType(),
                artifact.getStatus(), artifact.getSchemaVersion(), readMap(artifact.getPayload()), readMap(artifact.getEvaluation()), artifact.getChecksum());
    }
    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Failed to serialize storyboard artifact", error); }
    }
    private String checksum(String payload) {
        try {
            // PostgreSQL jsonb may reorder keys/whitespace; hash semantic canonical JSON.
            return ToolIdempotencyService.sha256(objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(readMap(payload)));
        } catch (Exception error) { throw new IllegalStateException("Failed to hash storyboard artifact", error); }
    }
    private Map<String,Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception error) { throw new IllegalStateException("Failed to read storyboard artifact", error); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return value instanceof Map<?,?> values ? (Map<String,Object>) values : Map.of(); }
}
