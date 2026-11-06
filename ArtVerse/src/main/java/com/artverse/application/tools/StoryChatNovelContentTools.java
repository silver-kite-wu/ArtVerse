package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.NovelDraftBaseContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.NovelContentArtifactService;
import com.artverse.common.BusinessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StoryChatNovelContentTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService auditService;
    private final NovelContentArtifactService artifactService;
    private com.artverse.application.NovelHistoryAgentService history;
    private com.artverse.application.NativeNovelDraftService nativeDrafts;

    @org.springframework.beans.factory.annotation.Autowired
    public void setNativeDrafts(com.artverse.application.NativeNovelDraftService service) { this.nativeDrafts = java.util.Objects.requireNonNull(service); }

    @org.springframework.beans.factory.annotation.Autowired
    public void setHistory(com.artverse.application.NovelHistoryAgentService service) { this.history = java.util.Objects.requireNonNull(service); }

    @Tool(name = "draft_novel_content", description = "Persist a complete chapter novel text draft as an artifact; it does not change the chapter. Before calling this tool, read every current get_novel_context page and continue through a model turn so the complete current baseline is recorded.", concurrencySafe = false)
    public Map<String, Object> draftNovelContent(@ToolParam(name = "content", description = "Complete chapter prose, never a partial excerpt") String content, RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return auditService.around("draft_novel_content", context.userId(), context.chapterId(), runtimeContext, () -> {
            if (nativeDrafts == null) throw new BusinessException(503, "小说原生草稿服务未配置");
            NovelContentArtifactService.ArtifactView artifact = nativeDrafts.draft(content, context,
                    com.artverse.agent.AgentToolAttempt.require(runtimeContext, "draft_novel_content"));
            Map<String, Object> payload = new LinkedHashMap<>(artifact.payload());
            payload.remove("base_content"); // The editor needs the snapshot; the model already has this context.
            payload.put("artifact_id", artifact.artifactId());
            payload.put("artifact_type", artifact.type());
            payload.put("status", artifact.status());
            payload.put("checksum", artifact.checksum());
            return payload;
        });
    }

    @Tool(name = "draft_novel_restore", description = "Create a chapter review draft from a historical revision, fetched in full by the server. First read the complete current novel context. The author must review the diff before it can change canonical text.", concurrencySafe = false)
    public Map<String,Object> draftNovelRestore(@ToolParam(name="revision_id",description="Historical revision ID of the current chapter") Long id, RuntimeContext runtime) {
        var context = support.resolveContext(runtime);
        return auditService.around("draft_novel_restore", context.userId(), context.chapterId(), runtime, () -> {
            if (history == null) throw new BusinessException(503, "小说草稿服务未配置");
            var artifact = history.restore(id, context, com.artverse.agent.AgentToolAttempt.require(runtime, "draft_novel_restore"));
            return Map.of("artifact_id",artifact.artifactId().toString(),"artifact_type",artifact.type(),"status",artifact.status(),
                    "restore_revision_id",id,"base_version",artifact.payload().get("base_version"),"content_hash",artifact.payload().get("content_hash"));
        });
    }

}
