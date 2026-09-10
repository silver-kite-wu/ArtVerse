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
        return knowledgeService.update(storyId, unitId, user().getId(), input(body));
    }

    @DeleteMapping("/stories/{storyId}/knowledge/{unitId}")
    public ResponseEntity<Void> archive(@PathVariable Long storyId, @PathVariable Long unitId) {
        knowledgeService.archive(storyId, unitId, user().getId()); return ResponseEntity.noContent().build();
    }

    @GetMapping("/stories/{storyId}/knowledge/{unitId}/jobs")
    public List<KnowledgeService.IndexJobView> jobs(@PathVariable Long storyId, @PathVariable Long unitId) { return knowledgeService.jobs(storyId, unitId, user().getId()); }

    @GetMapping("/stories/{storyId}/knowledge/{unitId}/revisions")
    public List<KnowledgeService.RevisionView> revisions(@PathVariable Long storyId, @PathVariable Long unitId) { return knowledgeService.revisions(storyId, unitId, user().getId()); }

    @PostMapping("/stories/{storyId}/knowledge/{unitId}/jobs/{jobId}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long storyId, @PathVariable Long unitId, @PathVariable Long jobId) {
        knowledgeService.retry(storyId, unitId, jobId, user().getId()); return ResponseEntity.accepted().build();
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
            @PathVariable Long candidateId) {
        return knowledgeCandidateService.approve(storyId, candidateId, user().getId());
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
        return new KnowledgeService.UnitInput(str(body, "type"), str(body, "title"), str(body, "body"), str(body, "summary"),
                structured instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(), integer(body, "importance"), integer(body, "effective_from_chapter"), integer(body, "effective_to_chapter"));
    }
    private static String str(Map<String, Object> body, String key) { Object value = body.get(key); return value == null ? "" : String.valueOf(value); }
    private static Integer integer(Map<String, Object> body, String key) { Object value = body.get(key); return value instanceof Number n ? n.intValue() : value == null || String.valueOf(value).isBlank() ? null : Integer.valueOf(String.valueOf(value)); }
    private static Long longValue(Map<String, Object> body, String key) { Integer value = integer(body, key); return value == null ? null : value.longValue(); }
}
