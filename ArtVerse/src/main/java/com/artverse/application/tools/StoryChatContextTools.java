package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.ChapterAccessService;
import com.artverse.domain.Chapter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class StoryChatContextTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService auditService;
    private final ChapterAccessService chapterAccessService;
    private com.artverse.application.NovelModelReadService novelReads;

    @org.springframework.beans.factory.annotation.Autowired
    public void setNovelReads(com.artverse.application.NovelModelReadService service) { this.novelReads = java.util.Objects.requireNonNull(service); }

    public Map<String, Object> getNovelContext(RuntimeContext runtimeContext) {
        return getNovelContext(null, null, null, runtimeContext);
    }

    @Tool(name = "get_novel_context", description = "Read canonical novel text and version in pages up to 8000 characters. Follow next_cursor until has_more is false before drafting. chapter_id defaults to this chapter; another chapter must belong to this story and cannot provide the current write baseline.", readOnly = true)
    public Map<String, Object> getNovelContext(
            @ToolParam(name="chapter_id",description="Optional explicit chapter resource ID in this story",required=false) Long chapterId,
            @ToolParam(name="cursor",description="Opaque next_cursor from the previous page",required=false) String cursor,
            @ToolParam(name="limit",description="Page size from 1 to 8000 characters",required=false) Integer limit,
            RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return auditService.around("get_novel_context", context.userId(), context.chapterId(), runtimeContext, () -> {
            if (novelReads == null) throw new com.artverse.common.BusinessException(503, "小说读取回执服务未配置");
            return novelReads.readCurrent(context, chapterId, cursor, limit);
        });
    }
}
