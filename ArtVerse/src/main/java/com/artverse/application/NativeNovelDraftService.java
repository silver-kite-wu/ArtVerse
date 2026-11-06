package com.artverse.application;

import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentToolAttempt;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/** The current read baseline, draft and native ledger evidence commit in one business transaction. */
@Service
@RequiredArgsConstructor
public class NativeNovelDraftService {
    private final NovelModelReadService reads;
    private final NovelContentArtifactService artifacts;
    private final AgentToolEffectService effects;

    @Transactional
    public NovelContentArtifactService.ArtifactView draft(String content, MangaAgentRuntimeContext context, AgentToolAttempt attempt) {
        if (context == null || context.taskType() != AgentTaskType.STORY_CHAT_WRITE || attempt == null
                || !"draft_novel_content".equals(attempt.toolName()))
            throw new BusinessException(403, "TOOL_EFFECT_DENIED: novel draft requires its native write scope");
        attempt.requireScope(context);
        long baseline = reads.requireCurrentBaseline(context);
        var artifact = artifacts.draft(content, context, baseline);
        effects.record(attempt, context, "NOVEL_DRAFT", artifact.artifactId(), artifact.type(), artifact.status(),
                artifact.checksum(), Map.of("base_version", baseline, "content_hash", artifact.payload().get("content_hash")));
        return artifact;
    }
}
