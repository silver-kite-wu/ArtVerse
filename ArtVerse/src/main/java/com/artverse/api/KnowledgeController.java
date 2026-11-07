package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.EmbeddingConfigService;
import com.artverse.application.KnowledgeService;
import com.artverse.application.KnowledgeCandidateService;
import com.artverse.domain.User;
import com.artverse.common.aspect.RateLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeController {
    private final KnowledgeService knowledgeService;
    private final EmbeddingConfigService embeddingConfigService;
    private final CurrentUserService currentUserService;
    private final KnowledgeCandidateService knowledgeCandidateService;

    @GetMapping("/user/embedding-configs")
    public List<EmbeddingConfigService.ConfigInfo> configs() { return embeddingConfigService.list(user()); }

    @PutMapping("/user/embedding-configs")
    public EmbeddingConfigService.ConfigInfo saveEmbeddingConfig(@RequestBody Map<String, Object> body) {
        return embeddingConfigService.save(user(), embeddingDraft(body));
    }

    @PostMapping("/user/embedding-configs/test")
    public EmbeddingConfigService.TestResult test(@RequestBody Map<String, Object> body) {
        return embeddingConfigService.test(user(), longValue(body, "config_id"));
    }

    @PostMapping("/user/embedding-configs/{configId}/activate")
    public EmbeddingConfigService.ConfigInfo activateEmbeddingConfig(@PathVariable Long configId) {
        return embeddingConfigService.activate(user(), configId);
    }

    @PostMapping("/user/embedding-configs/{configId}/deactivate")
    public EmbeddingConfigService.ConfigInfo deactivateEmbeddingConfig(@PathVariable Long configId) {
        return embeddingConfigService.deactivate(user(), configId);
    }

    @PostMapping("/user/embedding-configs/models/discover")
    public Map<String, List<String>> discoverEmbeddingModels(@RequestBody Map<String, Object> body) {
        return Map.of("models", embeddingConfigService.discoverModels(user(), embeddingDraft(body)));
    }

    @GetMapping("/stories/{storyId}/knowledge")
    public List<KnowledgeService.UnitView> list(@PathVariable Long storyId, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return knowledgeService.list(storyId, user().getId(), includeArchived);
    }

    @PostMapping("/stories/{storyId}/knowledge")
    public KnowledgeService.UnitView create(@PathVariable Long storyId, @RequestBody Map<String, Object> body) {
        return knowledgeService.create(storyId, user().getId(), input(body));
    }

    @PutMapping("/stories/{storyId}/knowledge/{unitId}")
    public KnowledgeService.UnitView update(@PathVariable Long storyId, @PathVariable Long unitId, @RequestBody Map<String, Object> body) {
        return body.containsKey("base_version") ? knowledgeService.updateVersioned(storyId,unitId,user().getId(),integer(body,"base_version"),input(body))
            : knowledgeService.update(storyId, unitId, user().getId(), input(body));
    }

    @DeleteMapping("/stories/{storyId}/knowledge/{unitId}")
    public ResponseEntity<Void> archive(@PathVariable Long storyId, @PathVariable Long unitId) {
        knowledgeService.archive(storyId, unitId, user().getId()); return ResponseEntity.noContent().build();
    }

    @PostMapping("/stories/{storyId}/knowledge/rebuild")
    public ResponseEntity<Void> rebuild(@PathVariable Long storyId, @RequestBody Map<String, Object> body) {
        Long configId = longValue(body, "config_id");
        if (configId == null) throw new IllegalArgumentException("config_id is required");
        knowledgeService.rebuild(storyId, user().getId(), configId); return ResponseEntity.accepted().build();
    }

    @GetMapping("/stories/{storyId}/knowledge/preview")
    public KnowledgeService.RecallPreview preview(@PathVariable Long storyId, @RequestParam int chapterNumber, @RequestParam(defaultValue = "") String query) {
        return knowledgeService.preview(storyId, user().getId(), chapterNumber, query);
    }

    @GetMapping("/stories/{storyId}/knowledge/candidates")
    public List<KnowledgeCandidateService.CandidateView> candidates(
            @PathVariable Long storyId,
            @RequestParam(required = false) String status) {
        return knowledgeCandidateService.list(storyId, user().getId(), status);
    }

    @PostMapping("/stories/{storyId}/knowledge/candidates/{candidateId}/approve")
    @RateLimit(windowSeconds = 60, maxRequests = 30, key = "knowledge-candidate-review")
    public KnowledgeCandidateService.CandidateView approveCandidate(
            @PathVariable Long storyId,
            @PathVariable Long candidateId,@RequestBody(required=false) Map<String,Object> body) {
        return knowledgeCandidateService.approve(storyId, candidateId, user().getId(),body==null?null:longValue(body,"version"));
    }

    @GetMapping("/stories/{storyId}/knowledge/candidates/{candidateId}")
    public KnowledgeCandidateService.CandidateView candidate(@PathVariable Long storyId,@PathVariable Long candidateId) {
        return knowledgeCandidateService.detail(storyId,candidateId,user().getId());
    }
    @PutMapping("/stories/{storyId}/knowledge/candidates/{candidateId}")
    public KnowledgeCandidateService.CandidateView editCandidate(@PathVariable Long storyId,@PathVariable Long candidateId,@RequestBody Map<String,Object> body) {
        return knowledgeCandidateService.edit(storyId,candidateId,user().getId(),longValue(body,"version"),input(body),longValue(body,"target_unit_id"),integer(body,"base_version"));
    }
    @PostMapping("/stories/{storyId}/knowledge/candidates/{candidateId}/acknowledge-source")
    public KnowledgeCandidateService.CandidateView acknowledgeSource(@PathVariable Long storyId,@PathVariable Long candidateId,@RequestBody Map<String,Object> body) {
        return knowledgeCandidateService.acknowledgeSource(storyId,candidateId,user().getId(),longValue(body,"version"),str(body,"source_hash"));
    }

    @PostMapping("/stories/{storyId}/knowledge/candidates/{candidateId}/reject")
    @RateLimit(windowSeconds = 60, maxRequests = 30, key = "knowledge-candidate-review")
    public KnowledgeCandidateService.CandidateView rejectCandidate(
            @PathVariable Long storyId,
            @PathVariable Long candidateId,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : str(body, "reason");
        return knowledgeCandidateService.reject(storyId, candidateId, user().getId(), reason);
    }

    private User user() { return currentUserService.requireCurrentUser(); }
    private EmbeddingConfigService.Draft embeddingDraft(Map<String, Object> body) {
        return new EmbeddingConfigService.Draft(longValue(body, "config_id"), str(body, "display_name"), str(body, "base_url"),
                str(body, "api_key"), str(body, "model"), str(body, "custom_headers"));
    }
    @SuppressWarnings("unchecked")
    private KnowledgeService.UnitInput input(Map<String, Object> body) {
        Object structured = body.get("structured_data");
        if(structured!=null && !(structured instanceof Map<?,?>)) throw new com.artverse.common.BusinessException(400,"structured_data 必须是 JSON 对象");
        return new KnowledgeService.UnitInput(str(body, "type"), str(body, "title"), str(body, "body"), str(body, "summary"),
                structured instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(), integer(body, "importance"), integer(body, "effective_from_chapter"), integer(body, "effective_to_chapter"));
    }
    private static String str(Map<String, Object> body, String key) { Object value = body.get(key); return value == null ? "" : String.valueOf(value); }
    private static Integer integer(Map<String, Object> body, String key) { Object value = body.get(key); return value instanceof Number n ? n.intValue() : value == null || String.valueOf(value).isBlank() ? null : Integer.valueOf(String.valueOf(value)); }
    private static Long longValue(Map<String, Object> body, String key) { Object v=body.get(key);return v==null?null:Long.valueOf(v.toString()); }
}
