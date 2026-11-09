package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.MessageRole;
import com.artverse.persistence.MangaAgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NovelDraftReviewService {
    private final StoryChatPreparationService preparation;
    private final MangaAgentRunRepository runs;
    private final MangaAgentRunService runService;
    private final NovelContentArtifactService artifacts;
    private AgentToolEffectService effects;

    @org.springframework.beans.factory.annotation.Autowired
    public void setEffects(AgentToolEffectService service) { effects = java.util.Objects.requireNonNull(service); }

    @Transactional
    public NovelContentArtifactService.CommitView decide(Long userId, Long chapterId, UUID conversationId,
            UUID requestId, UUID artifactId, String decision, String content, Long baseVersion, String expectedContentHash) {
        return decideInternal(userId, chapterId, conversationId, requestId, artifactId, decision, content,
                baseVersion, expectedContentHash, false, false);
    }

    /** 完全访问权限下由服务端自动确认草稿，审核记录标记为 AI 自动审批。 */
    @Transactional
    public NovelContentArtifactService.CommitView autoConfirm(Long userId, Long chapterId, UUID conversationId,
            UUID requestId, UUID artifactId, String content, Long baseVersion, String expectedContentHash) {
        return decideInternal(userId, chapterId, conversationId, requestId, artifactId, "confirm", content,
                baseVersion, expectedContentHash, false, true);
    }

    @Transactional
    public NovelContentArtifactService.CommitView decideForContinuation(Long userId, Long chapterId, UUID conversationId,
            UUID requestId, UUID artifactId, String decision, String content, Long baseVersion, String expectedContentHash) {
        return decideInternal(userId, chapterId, conversationId, requestId, artifactId, decision, content,
                baseVersion, expectedContentHash, true, false);
    }

    private NovelContentArtifactService.CommitView decideInternal(Long userId, Long chapterId, UUID conversationId,
            UUID requestId, UUID artifactId, String decision, String content, Long baseVersion, String expectedContentHash,
            boolean continueAgent, boolean automatic) {
        var scope = preparation.resolve(userId, chapterId, conversationId);
        runService.lockAutomationAuthorDecision(userId, chapterId, requestId);
        var run = runs.findForUpdate(scope.id(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        // Old runs may have completed or failed after producing a valid draft.
        // The artifact and chapter version remain authoritative for explicit review.
        if (!Set.of(MangaAgentRunStatus.WAITING_USER, MangaAgentRunStatus.SUCCEEDED, MangaAgentRunStatus.FAILED)
                .contains(run.getStatus())) {
            throw new BusinessException(409, "任务仍在执行或已停止，请刷新任务状态后重试");
        }
        var result = artifacts.review(userId, chapterId, requestId, artifactId, decision, content, baseVersion, expectedContentHash);
        var receipt = artifacts.reviewReceipt(userId, chapterId, requestId, artifactId);
        if (effects != null) {
            if (automatic) effects.recordAiAutoReview(userId, chapterId, requestId, artifactId, receipt);
            else effects.recordAuthorReview(userId, chapterId, requestId, artifactId, receipt);
        }
        project(scope, run, requestId, receipt, continueAgent, automatic);
        if (!continueAgent && run.getStatus() == MangaAgentRunStatus.WAITING_USER) {
            String reply = "discard".equals(decision) ? "已放弃本次修改，章节正文未改变。"
                    : automatic ? "已按「完全访问」权限自动确认修改并写入正文。" : "已按审阅结果保存小说原文。";
            preparation.saveMessage(scope, MessageRole.ASSISTANT, reply, requestId, "{}");
            runService.markSucceeded(scope.reference(), requestId, reply);
        }
        return result;
    }

    /** Rebuild only a run projection from immutable domain evidence, without replaying the decision. */
    @Transactional
    public Map<String, Object> reconcile(Long userId, Long chapterId, UUID conversationId,
                                         UUID requestId, UUID artifactId, boolean continueAgent) {
        var scope = preparation.resolve(userId, chapterId, conversationId);
        runService.lockAutomationReviewReceipt(userId, chapterId, requestId);
        var run = runs.findForUpdate(scope.id(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        var receipt = artifacts.reviewReceipt(userId, chapterId, requestId, artifactId);
        if (effects != null) effects.recordAuthorReview(userId, chapterId, requestId, artifactId, receipt);
        project(scope, run, requestId, receipt, continueAgent, false);
        return receipt;
    }

    private void project(StoryChatPreparationService.Scope scope, com.artverse.domain.MangaAgentRun run,
                         UUID requestId, Map<String, Object> receipt, boolean continueAgent, boolean automatic) {
        if (runService.recordReviewReceipt(scope.reference(), requestId, receipt, continueAgent)) {
            Map<String, Object> data = new java.util.LinkedHashMap<>(receipt);
            data.put("reviewer", automatic ? "AI_AUTO" : "AUTHOR");
            runService.appendEvent(run, "run_event", Map.of(
                    "type", "review_saved", "phase", "review", "label",
                    "COMMITTED".equals(receipt.get("status"))
                            ? automatic ? "AI 自动审批已确认，正文已保存" : "人工审核已确认，正文已保存"
                            : automatic ? "AI 自动审批已放弃，正文未改变" : "人工审核已放弃，正文未改变",
                    "status", "succeeded", "data", data));
        }
    }
}
