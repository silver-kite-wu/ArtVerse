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
public class ArtifactDraftAgentTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService audit;
    private final AgentArtifactAccessService artifacts;

    @Tool(name="discard_artifact",description="Discard this run's uncommitted draft before author review starts. This does not reject an author review or change canonical content.",concurrencySafe=false)
    public Map<String,Object> discard(@ToolParam(name="artifact_id",description="Current run draft artifact UUID") String id,
            @ToolParam(name="expected_artifact_version",description="Exact opaque artifact_version from get_artifact or list_artifacts") String expectedVersion,
            RuntimeContext runtime) {
        var context=support.resolveContext(runtime);
        return audit.around("discard_artifact",context.userId(),context.chapterId(),runtime,()->artifacts.discard(context,UUID.fromString(id),expectedVersion,
                com.artverse.agent.AgentToolAttempt.require(runtime, "discard_artifact")));
    }
}
