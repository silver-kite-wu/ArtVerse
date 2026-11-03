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

@Service
@RequiredArgsConstructor
public class KnowledgeCandidateService {

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
    public List<CandidateView> replaceChapterExtraction(
            Long chapterId,
            Long userId,
            List<ExtractedCandidate> extractedCandidates) {
        Chapter chapter = chapterRepository.findByIdForIdempotencyAndUserId(chapterId, userId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        Story story = chapter.getStory();
        User user = story.getUser();
        String sourcePrefix = chapterId + ":";
        candidateRepository.findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
                        story.getId(), userId, KnowledgeCandidateStatus.PENDING)
                .stream()
                .filter(candidate -> "CHAPTER_EXTRACTION".equals(candidate.getSourceType()))
                .filter(candidate -> candidate.getSourceId() != null
                        && candidate.getSourceId().startsWith(sourcePrefix))
                .forEach(candidate -> candidate.setStatus(KnowledgeCandidateStatus.SUPERSEDED));

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
                    sourcePrefix + index, type, clip(input.title().trim(), 255), body, summary,
                    input.structuredData() == null ? Map.of() : input.structuredData(),
                    Math.max(1, Math.min(5, input.importance() == null ? 3 : input.importance())),
                    input.effectiveFromChapter() == null
                            ? chapter.getChapterNumber()
                            : input.effectiveFromChapter(),
                    input.effectiveToChapter());
            KnowledgeCandidate candidate=candidateRepository.findById(created.id()).orElseThrow();candidate.setChapter(chapter);
            Map<String,Object> snapshot=chapterSnapshot(chapter);
            candidate.setSourceSnapshot(schema.write(snapshot));candidate.setSourceHash(schema.hash(snapshot));
            Map<String,Object> structured=new LinkedHashMap<>(readMap(candidate.getStructuredData()));
            structured.put("reveal_chapter_id",chapter.getId());candidate.setStructuredData(schema.write(structured));
            candidateRepository.flush();results.add(toView(candidate));
        }
        return List.copyOf(results);
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
        publishDecision(candidate, "KNOWLEDGE_CANDIDATE_APPROVED");
        return toView(candidateRepository.saveAndFlush(candidate));
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
        return Map.of("chapter_id",chapter.getId(),"novel",Objects.toString(chapter.getNovelContent(),""),"storyboard",Objects.toString(chapter.getScenesText(),""));
    }
    private void checkSource(KnowledgeCandidate candidate,Long userId) {
        if(candidate.getSourceHash()==null) throw new BusinessException(409,"旧候选缺少来源快照，请重新提取或核对来源后另存为正式知识");
        Map<String,Object> current=currentSource(candidate);
        if(!schema.hash(current).equals(candidate.getSourceHash())) throw new BusinessException(409,"来源内容已变更，请重新提取并比较，不能直接批准旧快照");
    }
    private Map<String,Object> currentSource(KnowledgeCandidate candidate) {
        if(candidate.getChapter()!=null) return chapterSnapshot(candidate.getChapter());
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
        publishDecision(candidate, "KNOWLEDGE_CANDIDATE_REJECTED");
        return toView(candidateRepository.save(candidate));
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
