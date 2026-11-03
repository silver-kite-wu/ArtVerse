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

@Service
@RequiredArgsConstructor
public class NovelDraftReviewService {
    private final StoryChatPreparationService preparation;
    private final MangaAgentRunRepository runs;
    private final MangaAgentRunService runService;
    private final NovelContentArtifactService artifacts;

    @Transactional
    public NovelContentArtifactService.CommitView decide(Long userId, Long chapterId, UUID conversationId,
            UUID requestId, UUID artifactId, String decision, String content, Long baseVersion, String expectedContentHash) {
        var scope = preparation.resolve(userId, chapterId, conversationId);
        var run = runs.findForUpdate(scope.id(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        // Old runs may have completed or failed after producing a valid draft.
        // The artifact and chapter version remain authoritative for explicit review.
        if (!Set.of(MangaAgentRunStatus.WAITING_USER, MangaAgentRunStatus.SUCCEEDED, MangaAgentRunStatus.FAILED)
                .contains(run.getStatus())) {
            throw new BusinessException(409, "任务仍在执行或已停止，请刷新任务状态后重试");
        }
        var result = artifacts.review(userId, chapterId, requestId, artifactId, decision, content, baseVersion, expectedContentHash);
        if (run.getStatus() == MangaAgentRunStatus.WAITING_USER) {
            String reply = "discard".equals(decision) ? "已放弃本次修改，章节正文未改变。" : "已按审阅结果保存小说原文。";
            preparation.saveMessage(scope, MessageRole.ASSISTANT, reply, requestId, "{}");
            runService.markSucceeded(scope.reference(), requestId, reply);
        }
        return result;
    }
}
