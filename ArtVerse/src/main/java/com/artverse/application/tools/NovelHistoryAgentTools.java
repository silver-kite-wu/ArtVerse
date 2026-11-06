package com.artverse.application.tools;

import com.artverse.application.NovelHistoryAgentService;
import com.artverse.application.AgentToolAuditService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class NovelHistoryAgentTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService audit;
    private final NovelHistoryAgentService history;

    @Tool(name="list_novel_revisions",description="List immutable historical novel versions for this chapter. These are historical snapshots, not the current canonical text.",readOnly=true)
    public Map<String,Object> list(@ToolParam(name="cursor",required=false,description="Opaque next_cursor from the preceding page") String cursor,
            @ToolParam(name="limit",required=false,description="Page size from 1 to 50") Integer limit, RuntimeContext runtime) {
        var context=support.resolveContext(runtime);
        return audit.around("list_novel_revisions",context.userId(),context.chapterId(),runtime,()->history.list(context,cursor,limit));
    }

    @Tool(name="get_novel_revision",description="Read one immutable historical revision of this chapter. Follow next_cursor to read all text. Historical text never serves as the current write baseline.",readOnly=true)
    public Map<String,Object> get(@ToolParam(name="revision_id",description="Historical revision ID returned by list_novel_revisions") Long id,
            @ToolParam(name="cursor",required=false,description="Opaque next_cursor from the previous revision page") String cursor,
            @ToolParam(name="limit",required=false,description="Content page size from 1 to 8000 characters") Integer limit, RuntimeContext runtime) {
        var context=support.resolveContext(runtime);
        return audit.around("get_novel_revision",context.userId(),context.chapterId(),runtime,()->history.get(context,id,cursor,limit));
    }
}
