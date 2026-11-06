package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentToolAttempt;
import com.artverse.application.*;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business commands behind the curated native wrappers; no model metadata or legacy direct writes. */
@Component
@RequiredArgsConstructor
public class MangaStoryboardTools {
    private final AgentToolAuditService audit;
    private final MangaToolSupport support;
    private final StoryboardArtifactService artifacts;

    public Map<String,Object> draftStructuredStoryboard(Object pages, String readRef, RuntimeContext runtime) {
        MangaAgentRuntimeContext ctx = support.resolveContext(runtime);
        return audit.around("draft_structured_storyboard", ctx.userId(), ctx.chapterId(), runtime, () -> {
            var artifact = artifacts.createStructuredDraft(pages, readRef, ctx,
                    AgentToolAttempt.require(runtime, "draft_structured_storyboard"));
            var result = receipt(artifact);
            result.put("saved", false);
            result.put("validated", "VALIDATED".equals(artifact.status())
                    && Boolean.TRUE.equals(artifact.evaluation().get("structure_valid")));
            return result;
        });
    }

    public Map<String,Object> commitStoryboard(String artifactId, RuntimeContext runtime) {
        UUID id;
        try { id = UUID.fromString(artifactId); }
        catch (RuntimeException error) { throw new com.artverse.common.BusinessException(400, "artifact_id must be a UUID"); }
        MangaAgentRuntimeContext ctx = support.resolveContext(runtime);
        return audit.around("commit_storyboard", ctx.userId(), ctx.chapterId(), runtime, () -> {
            StoryboardArtifactService.ArtifactView artifact;
            try { artifact = artifacts.commit(id, ctx, AgentToolAttempt.require(runtime, "commit_storyboard")); }
            catch (AgentUserInputRequiredException waiting) {
                support.requestUserInput(ctx, waiting.request());
                throw waiting;
            }
            var result = receipt(artifact);
            result.put("saved", "COMMITTED".equals(artifact.status()));
            result.put("scenes", artifact.payload().get("scenes"));
            return result;
        });
    }

    private static Map<String,Object> receipt(StoryboardArtifactService.ArtifactView artifact) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("artifact_id", artifact.artifactId().toString());
        result.put("artifact_status", artifact.status()); result.put("status", artifact.status());
        result.put("request_id", artifact.requestId().toString());
        result.put("step_id", artifact.payload().get("step_id"));
        result.put("base_version", artifact.payload().get("base_version"));
        result.put("content_hash", artifact.checksum());
        result.put("scenes_count", ((List<?>) artifact.payload().get("scenes")).size());
        result.put("evaluation", artifact.evaluation());
        return result;
    }
}
