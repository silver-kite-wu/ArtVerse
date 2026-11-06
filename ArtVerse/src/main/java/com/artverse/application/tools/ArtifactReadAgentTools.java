package com.artverse.application.tools;

import com.artverse.application.AgentArtifactAccessService;
import com.artverse.application.AgentToolAuditService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ArtifactReadAgentTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService audit;
    private final AgentArtifactAccessService artifacts;

    @Tool(name="get_artifact", description="Read an artifact from this chapter and conversation. Content is paged; follow next_cursor. Internal execution credentials are excluded.", readOnly=true)
    public Map<String,Object> get(@ToolParam(name="artifact_id",description="Artifact UUID from a persisted tool result") String id,
            @ToolParam(name="cursor",required=false,description="Opaque next_cursor from the previous content page") String cursor,
            @ToolParam(name="limit",required=false,description="Content page size, 1-8000 characters") Integer limit, RuntimeContext runtime) {
        var context=support.resolveContext(runtime);
        return audit.around("get_artifact",context.userId(),context.chapterId(),runtime,()->artifacts.get(context,UUID.fromString(id),cursor,limit));
    }

    @Tool(name="list_artifacts", description="List artifact summaries from the current run. Follow next_cursor with the same filters.", readOnly=true)
    public Map<String,Object> list(@ToolParam(name="type",required=false,description="NOVEL_CONTENT_DRAFT or STORYBOARD_DRAFT") String type,
            @ToolParam(name="status",required=false,description="Optional persisted artifact status") String status,
            @ToolParam(name="cursor",required=false,description="Opaque next_cursor from the previous list page") String cursor,
            @ToolParam(name="limit",required=false,description="Page size from 1 to 50") Integer limit, RuntimeContext runtime) {
        var context=support.resolveContext(runtime);
        return audit.around("list_artifacts",context.userId(),context.chapterId(),runtime,()->artifacts.list(context,type,status,cursor,limit));
    }
}
