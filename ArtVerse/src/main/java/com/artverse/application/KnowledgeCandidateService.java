package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.CharacterProfile;
import com.artverse.domain.Chapter;
import com.artverse.domain.KnowledgeCandidate;
import com.artverse.domain.KnowledgeCandidateStatus;
import com.artverse.domain.KnowledgeUnitType;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.KnowledgeCandidateRepository;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.StoryRepository;
import com.artverse.persistence.UserRepository;
import com.artverse.application.knowledge.KnowledgeSchema;
import com.artverse.application.knowledge.KnowledgeFactService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class KnowledgeCandidateService {

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AgentToolEffectService toolEffects;

    private final KnowledgeCandidateRepository candidateRepository;
    private final ChapterRepository chapterRepository;
    private final StoryRepository storyRepository;
    private final UserRepository userRepository;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeSchema schema;
    private final KnowledgeFactService facts;

    public record CandidateView(
            Long id,
            String sourceType,
            String sourceId,
            String knowledgeType,
            String title,
            String body,
            String summary,
            Map<String, Object> structuredData,
            Integer importance,
            Integer effectiveFromChapter,
            Integer effectiveToChapter,
            String status,
            Long approvedKnowledgeUnitId,
            String rejectionReason,
            OffsetDateTime createdAt,
            OffsetDateTime reviewedAt,
            Long version,Integer schemaVersion,Long targetUnitId,Integer baseVersion,
            Map<String,Object> sourceSnapshot,List<KnowledgeSchema.Issue> validationIssues,Map<String,Object> currentSource,String currentSourceHash,boolean sourceStale
    ) {
    }

    @Transactional(readOnly = true)
    public List<CandidateView> list(Long storyId, Long userId, String status) {
        requireStory(storyId, userId);
        List<KnowledgeCandidate> candidates = status == null || status.isBlank()
                ? candidateRepository.findByStoryIdAndUserIdOrderByCreatedAtDesc(storyId, userId)
                : candidateRepository.findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
                        storyId, userId, parseStatus(status));
        return candidates.stream().map(this::toView).toList();
    }

    @Transactional
    public CandidateView proposeCharacterProfile(CharacterProfile profile, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        Story story = profile.getStory();
        Map<String, Object> structured = Map.of("character_id",profile.getId());
        String body = profile.getDescription() == null ? "" : profile.getDescription();
        CandidateView proposed=propose(user, story, "CHARACTER_PROFILE", String.valueOf(profile.getId()),
                KnowledgeUnitType.CHARACTER_CARD, profile.getName(), body,
                body.length() <= 300 ? body : body.substring(0, 300), structured, 5, null, null);
        KnowledgeCandidate candidate=candidateRepository.findById(proposed.id()).orElseThrow();
        Map<String,Object> snapshot=Map.of("name",profile.getName(),"description",body);
        candidate.setSourceSnapshot(schema.write(snapshot));candidate.setSourceHash(schema.hash(snapshot));
        var roots=jdbcTemplate.queryForList("SELECT id,version FROM knowledge_units WHERE character_profile_id=?",profile.getId());
        if(!roots.isEmpty()) {candidate.setTargetUnitId(((Number)roots.getFirst().get("id")).longValue());candidate.setBaseVersion(((Number)roots.getFirst().get("version")).intValue());}
        candidateRepository.flush();return toView(candidate);
    }

    @Transactional
    public CandidateView proposeStorySettings(Story story,Long user,String action) {
        var snapshot=SettingsProposalService.storySnapshot(story);
        var result=propose(userRepository.getReferenceById(user),story,"STORY_SETTINGS",action,KnowledgeUnitType.WORLDVIEW,
                story.getTitle(),schema.write(snapshot),"作品设定变更，待审核",Map.of("subtype","RULE"),3,null,null);
        var candidate=candidateRepository.findById(result.id()).orElseThrow();
        candidate.setSourceSnapshot(schema.write(snapshot));candidate.setSourceHash(schema.hash(snapshot));candidateRepository.flush();return toView(candidate);
    }

    /** Independent conversation proposal: source ID includes this exact action, never an extraction batch. */
    @Transactional
    public CandidateView appendAiProposal(Long storyId,Long chapterId,Long userId,String action,
            KnowledgeService.UnitInput input,Long target,Integer baseVersion,String expectedSourceHash,String evidence) {
        Story story=requireStory(storyId,userId);
        var locked=chapterRepository.findByIdAndUserIdForUpdate(chapterId,userId).orElseThrow(()->new BusinessException(404,"章节不存在"));
        if(!locked.getStory().getId().equals(storyId))throw new BusinessException(403,"候选来源不属于当前作品");
        var source=chapterSnapshot(locked);
        if(!schema.hash(source).equals(expectedSourceHash))throw new BusinessException(409,"章节来源已变化，请重新读取");
        if(evidence==null||evidence.isBlank()||evidence.length()>4000)throw new BusinessException(400,"需要不超过 4000 字符的来源证据");
        var type=parseKnowledgeType(input.type());var data=schema.normalize(type,input.structuredData());data.put("source_evidence",evidence);
        schema.requireValid(type,input.title(),input.body(),data);facts.validateReferences(storyId,data);
        if(input.title().length()>255||input.body().length()>12000||Objects.toString(input.summary(),"").length()>1000||schema.write(data).length()>16000)throw new BusinessException(400,"候选内容过长");
        if(input.importance()!=null&&(input.importance()<1||input.importance()>5))throw new BusinessException(400,"重要度应为 1 到 5");
        if(input.effectiveFromChapter()!=null&&input.effectiveToChapter()!=null&&input.effectiveFromChapter()>input.effectiveToChapter())throw new BusinessException(400,"章节范围无效");
        if(target==null&&baseVersion!=null)throw new BusinessException(400,"新建候选不能携带目标版本");
        if(target!=null) {
            var targets=jdbcTemplate.queryForList("SELECT type,version FROM knowledge_units WHERE id=? AND story_id=? FOR UPDATE",target,storyId);
            if(targets.size()!=1||!type.name().equals(targets.getFirst().get("type")))throw new BusinessException(400,"目标不属于当前作品同类知识");
            if(baseVersion==null||((Number)targets.getFirst().get("version")).intValue()!=baseVersion)throw new BusinessException(409,"正式知识基准已变化");
        }
        var result=propose(userRepository.getReferenceById(userId),story,"AI_PROPOSAL",action,type,input.title(),input.body(),input.summary(),data,input.importance()==null?3:input.importance(),input.effectiveFromChapter(),input.effectiveToChapter());
        var candidate=candidateRepository.findById(result.id()).orElseThrow();candidate.setChapter(locked);candidate.setTargetUnitId(target);candidate.setBaseVersion(baseVersion);
        candidate.setSourceSnapshot(schema.write(source));candidate.setSourceHash(schema.hash(source));candidateRepository.flush();return toView(candidate);
    }

    /** Freeze the exact model material on the durable action before calling the provider. */
    @Transactional
    public ExtractionBatch beginChapterExtraction(Long chapterId, Long userId, AgentOutboxService.OutboxEvent event) {
        ObjectNode payload = lockExtractionEvent(event, chapterId, userId);
        Chapter chapter = chapterRepository.findByIdAndUserIdForUpdate(chapterId, userId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        if (payload.path("story_id").asLong(-1) != chapter.getStory().getId())
            throw new BusinessException(403, "提取动作与作品归属不一致");
        JsonNode previous = payload.get("knowledge_extraction");
        if (previous != null) {
            ExtractionBatch batch = readBatch(event.id(), previous);
            if (!Objects.equals(batch.source().chapterId(), chapterId) || !Objects.equals(batch.source().userId(), userId)
                    || !Objects.equals(batch.source().storyId(), chapter.getStory().getId()))
                throw new BusinessException(403, "提取动作与输入快照归属不一致");
            if (!batch.completed()) requireCurrentSource(chapter, batch.source(), batch.sourceHash());
            return batch;
        }
        ExtractionSource source = ExtractionSource.from(chapter);
        source.material(); // Reject oversized input before replacing candidates or charging a model request.
        String sourceHash = schema.hash(source.snapshot());
        ObjectNode frozen = objectMapper.createObjectNode();
        frozen.set("source", objectMapper.valueToTree(source));
        frozen.put("source_hash", sourceHash).put("status", "PREPARED");
        payload.set("knowledge_extraction", frozen);
        saveExtractionPayload(event, payload);
        return new ExtractionBatch(event.id(), source, sourceHash, false, List.of());
    }

    @Transactional
    public List<CandidateView> replaceChapterExtraction(
            AgentOutboxService.OutboxEvent event, ExtractionBatch batch, List<ExtractedCandidate> extractedCandidates) {
        ExtractionSource source = batch.source();
        ObjectNode payload = lockExtractionEvent(event, source.chapterId(), source.userId());
        ExtractionBatch frozen = readBatch(event.id(), payload.path("knowledge_extraction"));
        if (batch.eventId() != event.id() || !frozen.source().equals(source)
                || !frozen.sourceHash().equals(batch.sourceHash()))
            throw new BusinessException(409, "提取动作的输入快照已变化");
        if (frozen.completed()) return extractionResults(frozen);
        Chapter chapter = chapterRepository.findByIdAndUserIdForUpdate(source.chapterId(), source.userId())
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        requireCurrentSource(chapter, source, batch.sourceHash());
        Long userId = source.userId();
        Story story = chapter.getStory();
        User user = story.getUser();
        String sourcePrefix = source.chapterId() + ":";
        candidateRepository.findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
                        story.getId(), userId, KnowledgeCandidateStatus.PENDING)
                .stream()
                .filter(candidate -> "CHAPTER_EXTRACTION".equals(candidate.getSourceType()))
                .filter(candidate -> candidate.getSourceId() != null
                        && candidate.getSourceId().startsWith(sourcePrefix))
                .forEach(candidate -> candidate.setStatus(KnowledgeCandidateStatus.SUPERSEDED));
        candidateRepository.flush();

        List<ExtractedCandidate> safeCandidates = extractedCandidates == null
                ? List.of()
                : extractedCandidates.stream().limit(20).toList();
        java.util.ArrayList<CandidateView> results = new java.util.ArrayList<>();
        for (int index = 0; index < safeCandidates.size(); index++) {
            ExtractedCandidate input = safeCandidates.get(index);
            if (input == null || input.title() == null || input.title().isBlank()
                    || input.body() == null || input.body().isBlank()) {
                continue;
            }
            KnowledgeUnitType type = parseKnowledgeType(input.knowledgeType());
            String body = clip(input.body().trim(), 12_000);
            String summary = input.summary() == null || input.summary().isBlank()
                    ? clip(body, 300)
                    : clip(input.summary().trim(), 500);
            CandidateView created = propose(user, story, "CHAPTER_EXTRACTION",
                    sourcePrefix + event.id() + ":" + index, type, clip(input.title().trim(), 255), body, summary,
                    input.structuredData() == null ? Map.of() : input.structuredData(),
                    Math.max(1, Math.min(5, input.importance() == null ? 3 : input.importance())),
                    input.effectiveFromChapter() == null
                            ? chapter.getChapterNumber()
                            : input.effectiveFromChapter(),
                    input.effectiveToChapter());
            KnowledgeCandidate candidate=candidateRepository.findById(created.id()).orElseThrow();candidate.setChapter(chapter);
            candidate.setSourceSnapshot(schema.write(source.snapshot()));candidate.setSourceHash(batch.sourceHash());
            Map<String,Object> structured=new LinkedHashMap<>(readMap(candidate.getStructuredData()));
            structured.put("reveal_chapter_id",chapter.getId());candidate.setStructuredData(schema.write(structured));
            candidateRepository.flush();results.add(toView(candidate));
        }
        ObjectNode receipt = (ObjectNode) payload.get("knowledge_extraction");
        receipt.put("status", "COMPLETED");
        receipt.set("candidate_ids", objectMapper.valueToTree(results.stream().map(CandidateView::id).toList()));
        saveExtractionPayload(event, payload);
        return List.copyOf(results);
    }

    private void saveExtractionPayload(AgentOutboxService.OutboxEvent event, ObjectNode payload) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_outbox_events SET payload=?::jsonb WHERE id=? AND status='PROCESSING'
                  AND fencing_token=? AND owner_instance_id IS NOT NULL AND lease_until>clock_timestamp()
                """, schema.write(payload), event.id(), event.fencingToken());
        if (updated != 1) throw new BusinessException(409, "提取动作已失去执行权");
    }

    @Transactional(readOnly = true)
    public List<CandidateView> extractionResults(ExtractionBatch batch) {
        if (!batch.completed()) throw new BusinessException(409, "提取动作尚未完成");
        List<CandidateView> results = new ArrayList<>();
        for (Long id : batch.candidateIds()) results.add(candidateRepository.findByIdAndStoryIdAndUserId(
                id, batch.source().storyId(), batch.source().userId()).map(this::toView)
                .orElseThrow(() -> new BusinessException(409, "提取结果回执对应的候选已删除")));
        return List.copyOf(results);
    }

    private ObjectNode lockExtractionEvent(AgentOutboxService.OutboxEvent event, Long chapterId, Long userId) {
        var rows = jdbcTemplate.queryForList("""
                SELECT payload::text FROM agent_outbox_events
                WHERE id=? AND aggregate_type='CHAPTER' AND aggregate_id=?
                  AND event_type IN ('CHAPTER_CONTENT_CHANGED','STORYBOARD_CHANGED')
                  AND status='PROCESSING' AND owner_instance_id IS NOT NULL
                  AND fencing_token=? AND lease_until>clock_timestamp() FOR UPDATE
                """, event.id(), chapterId.toString(), event.fencingToken());
        if (rows.size() != 1) throw new BusinessException(409, "提取动作已失去执行权");
        try {
            ObjectNode payload = (ObjectNode) objectMapper.readTree(rows.getFirst().get("payload").toString());
            if (payload.path("user_id").asLong(-1) != userId || payload.path("chapter_id").asLong(-1) != chapterId)
                throw new BusinessException(403, "提取动作与章节归属不一致");
            return payload;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BusinessException(409, "提取动作数据损坏");
        }
    }

    private ExtractionBatch readBatch(long eventId, JsonNode node) {
        try {
            ExtractionSource source = objectMapper.treeToValue(node.path("source"), ExtractionSource.class);
            String hash = node.path("source_hash").asText();
            if (source == null || !schema.hash(source.snapshot()).equals(hash)
                    || !("PREPARED".equals(node.path("status").asText()) || "COMPLETED".equals(node.path("status").asText())))
                throw new BusinessException(409, "提取动作的来源快照损坏");
            List<Long> ids = new ArrayList<>();
            node.path("candidate_ids").forEach(id -> ids.add(id.longValue()));
            return new ExtractionBatch(eventId, source, hash, "COMPLETED".equals(node.path("status").asText()), ids);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BusinessException(409, "提取动作的来源快照损坏");
        }
    }

    private void requireCurrentSource(Chapter chapter, ExtractionSource source, String sourceHash) {
        if (!ExtractionSource.from(chapter).equals(source) || !schema.hash(source.snapshot()).equals(sourceHash))
            throw new BusinessException(409, "提取期间章节来源已变化，保留原待审批次，请使用最新章节重新提取");
    }

    public record ExtractionBatch(long eventId, ExtractionSource source, String sourceHash,
                                  boolean completed, List<Long> candidateIds) {
        public ExtractionBatch { candidateIds = List.copyOf(candidateIds); }
    }

    public record ExtractionSource(Long chapterId, Long userId, Long storyId, Integer chapterNumber,
                                   Long chapterVersion, String novel, String storyboard) {
        static ExtractionSource from(Chapter chapter) {
            return new ExtractionSource(chapter.getId(), chapter.getStory().getUser().getId(),
                    chapter.getStory().getId(), chapter.getChapterNumber(), chapter.getVersion(),
                    Objects.toString(chapter.getNovelContent(), ""), Objects.toString(chapter.getScenesText(), ""));
        }
        public Map<String,Object> snapshot() {
            return Map.of("chapter_id", chapterId, "chapter_number", chapterNumber, "chapter_version", chapterVersion,
                    "novel", novel, "storyboard", storyboard, "complete", true);
        }
        public String material() {
            if (novel.isBlank() && storyboard.isBlank()) return "";
            String material = "NOVEL_CONTENT:\n" + novel + "\n\nSTORYBOARD:\n" + storyboard;
            if (material.length() > 48_000) throw new BusinessException(413, "章节来源超过 48000 字符，保留原待审批次，请缩小材料后重新提取");
            return material;
        }
    }

    @Transactional
    public CandidateView approve(Long storyId, Long candidateId, Long userId) {
        return approve(storyId,candidateId,userId,null);
    }
    @Transactional
    public CandidateView approve(Long storyId, Long candidateId, Long userId,Long expectedVersion) {
        KnowledgeCandidate candidate = requirePending(storyId, candidateId, userId);
        checkVersion(candidate,expectedVersion);
        checkSource(candidate,userId);
        Map<String,Object> data=schema.normalize(candidate.getKnowledgeType(),readMap(candidate.getStructuredData()));
        schema.requireValid(candidate.getKnowledgeType(),candidate.getTitle(),candidate.getBody(),data);
        KnowledgeService.UnitInput input=new KnowledgeService.UnitInput(candidate.getKnowledgeType().name(),candidate.getTitle(),candidate.getBody(),candidate.getSummary(),data,candidate.getImportance(),candidate.getEffectiveFromChapter(),candidate.getEffectiveToChapter());
        KnowledgeService.UnitView unit;
        if ("CHARACTER_PROFILE".equals(candidate.getSourceType())) {
            try {
                unit = knowledgeService.approveCharacterSnapshot(storyId,
                        Long.valueOf(candidate.getSourceId()), userId,input,candidate.getBaseVersion());
            } catch (NumberFormatException error) {
                throw new BusinessException(409, "Candidate source is no longer valid");
            }
        } else {
            unit = candidate.getTargetUnitId()==null ? knowledgeService.create(storyId,userId,input)
                : knowledgeService.updateVersioned(storyId,candidate.getTargetUnitId(),userId,candidate.getBaseVersion(),input);
        }
        facts.sourceForCandidate(storyId,unit.id(),candidate);
        candidate.setApprovedKnowledgeUnitId(unit.id());
        candidate.setStatus(KnowledgeCandidateStatus.APPROVED);
        candidate.setReviewedBy(userId);
        candidate.setReviewedAt(OffsetDateTime.now());
        candidate.setRejectionReason(null);
        candidateRepository.saveAndFlush(candidate);
        publishDecision(candidate, "KNOWLEDGE_CANDIDATE_APPROVED");
        return toView(candidate);
    }

    @Transactional(readOnly=true)
    public CandidateView detail(Long storyId,Long candidateId,Long userId) {
        requireStory(storyId,userId);
        return toView(candidateRepository.findByIdAndStoryIdAndUserId(candidateId,storyId,userId).orElseThrow(()->new BusinessException(404,"候选不存在")));
    }
    @Transactional
    public CandidateView edit(Long storyId,Long candidateId,Long userId,Long expectedVersion,KnowledgeService.UnitInput input,Long targetId,Integer baseVersion) {
        KnowledgeCandidate candidate=requirePending(storyId,candidateId,userId);checkVersion(candidate,expectedVersion);
        if(expectedVersion==null) throw new BusinessException(400,"编辑候选必须提交版本号");
        KnowledgeUnitType type=parseKnowledgeType(input.type());
        Map<String,Object> data=schema.normalize(type,input.structuredData());
        facts.validateReferences(storyId,data);
        if("CHARACTER_PROFILE".equals(candidate.getSourceType()) && type!=KnowledgeUnitType.CHARACTER_CARD) throw new BusinessException(400,"人物资料候选不能改为其他类型");
        if(input.importance()!=null&&(input.importance()<1||input.importance()>5)) throw new BusinessException(400,"重要度应为 1 到 5");
        if(input.effectiveFromChapter()!=null&&input.effectiveToChapter()!=null&&input.effectiveFromChapter()>input.effectiveToChapter()) throw new BusinessException(400,"章节范围无效");
        if(targetId!=null) {
            var targets=jdbcTemplate.queryForList("SELECT type,version FROM knowledge_units WHERE id=? AND story_id=?",targetId,storyId);
            if(targets.isEmpty()||!targets.getFirst().get("type").equals(type.name())) throw new BusinessException(400,"更新目标应是当前作品同分类知识");
            if(baseVersion==null||((Number)targets.getFirst().get("version")).intValue()!=baseVersion) throw new BusinessException(409,"目标知识已变化，请重新比较");
        }
        candidate.setKnowledgeType(type);candidate.setTitle(input.title());candidate.setBody(input.body());candidate.setSummary(input.summary()==null?"":input.summary());
        candidate.setStructuredData(schema.write(data));candidate.setImportance(input.importance()==null?3:input.importance());
        candidate.setEffectiveFromChapter(input.effectiveFromChapter());candidate.setEffectiveToChapter(input.effectiveToChapter());
        candidate.setTargetUnitId(targetId);candidate.setBaseVersion(baseVersion);candidate.setSchemaVersion(2);
        candidate.setProposedHash(schema.hash(java.util.Arrays.asList(candidate.getSourceType(),candidate.getSourceId(),type.name(),input.title(),input.body(),input.summary(),data,candidate.getImportance(),input.effectiveFromChapter(),input.effectiveToChapter(),targetId,baseVersion)));
        return toView(candidateRepository.saveAndFlush(candidate));
    }
    @Transactional
    public CandidateView acknowledgeSource(Long storyId,Long candidateId,Long userId,Long expectedVersion,String expectedSourceHash) {
        KnowledgeCandidate candidate=requirePending(storyId,candidateId,userId);checkVersion(candidate,expectedVersion);
        if(expectedVersion==null) throw new BusinessException(400,"请先打开候选详情");
        Map<String,Object> current=currentSource(candidate);
        if(!schema.hash(current).equals(expectedSourceHash)) throw new BusinessException(409,"来源再次变化，请重新核对");
        candidate.setSourceSnapshot(schema.write(current));candidate.setSourceHash(schema.hash(current));
        return toView(candidateRepository.saveAndFlush(candidate));
    }
    private void checkVersion(KnowledgeCandidate candidate,Long expected) {
        if(expected!=null&&!expected.equals(candidate.getVersion())) throw new BusinessException(409,"候选已更新，请重新查看后确认");
    }
    private Map<String,Object> chapterSnapshot(Chapter chapter) {
        return ExtractionSource.from(chapter).snapshot();
    }
    private void checkSource(KnowledgeCandidate candidate,Long userId) {
        if(candidate.getSourceHash()==null) throw new BusinessException(409,"旧候选缺少来源快照，请重新提取或核对来源后另存为正式知识");
        if(candidate.getChapter()!=null) jdbcTemplate.queryForList("SELECT id FROM chapters WHERE id=? AND story_id=? FOR UPDATE",candidate.getChapter().getId(),candidate.getStory().getId());
        else if("CHARACTER_PROFILE".equals(candidate.getSourceType()))jdbcTemplate.queryForList("SELECT id FROM character_profiles WHERE id=? AND story_id=? FOR UPDATE",Long.valueOf(candidate.getSourceId()),candidate.getStory().getId());
        else if("STORY_SETTINGS".equals(candidate.getSourceType()))jdbcTemplate.queryForList("SELECT id FROM stories WHERE id=? AND user_id=? FOR UPDATE",candidate.getStory().getId(),userId);
        Map<String,Object> current=currentSource(candidate);
        if(!schema.hash(current).equals(candidate.getSourceHash())) throw new BusinessException(409,"来源内容已变更，请重新提取并比较，不能直接批准旧快照");
    }
    private Map<String,Object> currentSource(KnowledgeCandidate candidate) {
        if(candidate.getChapter()!=null) {
            var rows=jdbcTemplate.queryForList("SELECT chapter_number,version,novel_content,scenes_text FROM chapters WHERE id=? AND story_id=?",candidate.getChapter().getId(),candidate.getStory().getId());
            if(rows.isEmpty())throw new BusinessException(409,"章节来源已删除");var current=rows.getFirst();
            return Map.of("chapter_id",candidate.getChapter().getId(),"chapter_number",current.get("chapter_number"),"chapter_version",current.get("version"),"novel",Objects.toString(current.get("novel_content"),""),"storyboard",Objects.toString(current.get("scenes_text"),""),"complete",true);
        }
        else if("STORY_SETTINGS".equals(candidate.getSourceType())) {
            var rows=jdbcTemplate.queryForList("SELECT title,coalesce(description,'') AS description,manga_style FROM stories WHERE id=?",candidate.getStory().getId());
            if(rows.isEmpty())throw new BusinessException(409,"作品来源已删除");return rows.getFirst();
        }
        else if("CHARACTER_PROFILE".equals(candidate.getSourceType())) {
            var profiles=jdbcTemplate.queryForList("SELECT name,description FROM character_profiles WHERE id=? AND story_id=?",Long.valueOf(candidate.getSourceId()),candidate.getStory().getId());
            if(profiles.isEmpty()) throw new BusinessException(409,"人物来源已删除");
            return Map.of("name",profiles.getFirst().get("name"),"description",Objects.toString(profiles.getFirst().get("description"),""));
        } else return schema.read(candidate.getSourceSnapshot());
    }

    @Transactional
    public CandidateView reject(Long storyId, Long candidateId, Long userId, String reason) {
        KnowledgeCandidate candidate = requirePending(storyId, candidateId, userId);
        candidate.setStatus(KnowledgeCandidateStatus.REJECTED);
        candidate.setReviewedBy(userId);
        candidate.setReviewedAt(OffsetDateTime.now());
        candidate.setRejectionReason(reason == null || reason.isBlank() ? null : clip(reason, 500));
        candidateRepository.saveAndFlush(candidate);
        publishDecision(candidate, "KNOWLEDGE_CANDIDATE_REJECTED");
        return toView(candidate);
    }

    private CandidateView propose(User user, Story story, String sourceType, String sourceId,
                                  KnowledgeUnitType type, String title, String body, String summary,
                                  Map<String, Object> structuredData, int importance,
                                  Integer effectiveFrom, Integer effectiveTo) {
        String structured = schema.write(schema.normalize(type,structuredData));
        String proposedHash = schema.hash(java.util.Arrays.asList(sourceType,sourceId,type.name(),title,body,summary,schema.read(structured),importance,effectiveFrom,effectiveTo));
        return candidateRepository.findFirstByStoryIdAndProposedHashAndStatus(
                        story.getId(), proposedHash, KnowledgeCandidateStatus.PENDING)
                .map(this::toView)
                .orElseGet(() -> {
                    candidateRepository.findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
                                    story.getId(), user.getId(), KnowledgeCandidateStatus.PENDING)
                            .stream()
                            .filter(existing -> sourceType.equals(existing.getSourceType())
                                    && sourceId.equals(existing.getSourceId()))
                            .forEach(existing -> existing.setStatus(KnowledgeCandidateStatus.SUPERSEDED));
                    KnowledgeCandidate candidate = new KnowledgeCandidate();
                    candidate.setUser(user);
                    candidate.setStory(story);
                    candidate.setSourceType(sourceType);
                    candidate.setSourceId(sourceId);
                    candidate.setKnowledgeType(type);
                    candidate.setTitle(title == null ? "" : title);
                    candidate.setBody(body == null ? "" : body);
                    candidate.setSummary(summary == null ? "" : summary);
                    candidate.setStructuredData(structured);
                    candidate.setImportance(importance);
                    candidate.setEffectiveFromChapter(effectiveFrom);
                    candidate.setEffectiveToChapter(effectiveTo);
                    candidate.setProposedHash(proposedHash);
                    KnowledgeCandidate saved = candidateRepository.save(candidate);
                    publishDecision(saved, "KNOWLEDGE_CANDIDATE_CREATED");
                    return toView(saved);
                });
    }

    private KnowledgeCandidate requirePending(Long storyId, Long candidateId, Long userId) {
        requireStory(storyId, userId);
        KnowledgeCandidate candidate = candidateRepository.lockForReview(
                        candidateId, storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Knowledge candidate not found"));
        if (candidate.getStatus() != KnowledgeCandidateStatus.PENDING) {
            throw new BusinessException(409, "Knowledge candidate is already decided");
        }
        return candidate;
    }

    private Story requireStory(Long storyId, Long userId) {
        return storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
    }

    private void publishDecision(KnowledgeCandidate candidate, String eventType) {
        jdbcTemplate.update("""
                INSERT INTO agent_outbox_events(aggregate_type, aggregate_id, event_type, payload)
                VALUES ('KNOWLEDGE_CANDIDATE', ?, ?, CAST(? AS jsonb))
                """, String.valueOf(candidate.getId()), eventType, writeJson(Map.of(
                "candidate_id", candidate.getId(),
                "story_id", candidate.getStory().getId(),
                "status", candidate.getStatus().name())));
        if("AI_PROPOSAL".equals(candidate.getSourceType()) && java.util.Set.of(KnowledgeCandidateStatus.APPROVED,KnowledgeCandidateStatus.REJECTED).contains(candidate.getStatus())) {
            var rows=jdbcTemplate.queryForList("SELECT request_id FROM agent_usage_ledger WHERE user_id=? AND usage_kind='TOOL_CALL' AND metadata->'domain_effect'->>'artifact_id'=?",candidate.getUser().getId(),candidate.getSourceId());
            if(!rows.isEmpty()) {
                if(toolEffects==null)throw new BusinessException(409,"缺少候选领域回执服务");
                toolEffects.recordAuthorReview(candidate.getUser().getId(),candidate.getChapter().getId(),(java.util.UUID)rows.getFirst().get("request_id"),java.util.UUID.fromString(candidate.getSourceId()),Map.of("artifact_id",candidate.getSourceId(),"status",candidate.getStatus()==KnowledgeCandidateStatus.APPROVED?"COMMITTED":"REJECTED","candidate_id",candidate.getId().toString(),"candidate_version",candidate.getVersion().toString()));
            }
        }
    }

    private CandidateView toView(KnowledgeCandidate candidate) {
        Map<String,Object> current;
        try {current=currentSource(candidate);} catch(BusinessException removed) {current=Map.of("unavailable",true);}
        String currentHash=schema.hash(current);
        return new CandidateView(candidate.getId(), candidate.getSourceType(), candidate.getSourceId(),
                candidate.getKnowledgeType().name(), candidate.getTitle(), candidate.getBody(),
                candidate.getSummary(), schema.normalize(candidate.getKnowledgeType(),readMap(candidate.getStructuredData())), candidate.getImportance(),
                candidate.getEffectiveFromChapter(), candidate.getEffectiveToChapter(),
                candidate.getStatus().name(), candidate.getApprovedKnowledgeUnitId(),
                candidate.getRejectionReason(), candidate.getCreatedAt(), candidate.getReviewedAt(),candidate.getVersion(),candidate.getSchemaVersion(),candidate.getTargetUnitId(),candidate.getBaseVersion(),
                schema.read(candidate.getSourceSnapshot()),schema.issues(candidate.getKnowledgeType(),candidate.getTitle(),candidate.getBody(),readMap(candidate.getStructuredData())),current,currentHash,!currentHash.equals(candidate.getSourceHash()));
    }

    private KnowledgeCandidateStatus parseStatus(String status) {
        try {
            return KnowledgeCandidateStatus.valueOf(status.trim().toUpperCase());
        } catch (Exception error) {
            throw new BusinessException(400, "Invalid knowledge candidate status");
        }
    }

    private KnowledgeUnitType parseKnowledgeType(String type) {
        try {
            return KnowledgeUnitType.valueOf(type == null ? "" : type.trim().toUpperCase());
        } catch (Exception error) {
            throw new BusinessException(400, "Invalid extracted knowledge type");
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception error) {
            throw new BusinessException(400,"候选结构数据损坏，请修复 JSON 后重试");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new BusinessException(400, "Invalid knowledge candidate data");
        }
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder output = new StringBuilder();
            for (byte value : digest.digest()) output.append(String.format("%02x", value));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record ExtractedCandidate(
            String knowledgeType,
            String title,
            String body,
            String summary,
            Map<String, Object> structuredData,
            Integer importance,
            Integer effectiveFromChapter,
            Integer effectiveToChapter
    ) {
    }
}
