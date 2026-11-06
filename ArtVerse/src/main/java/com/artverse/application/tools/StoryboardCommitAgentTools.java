package com.artverse.application.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StoryboardCommitAgentTools {
    private final MangaStoryboardTools commands;

    @Tool(name = "commit_storyboard", description = "Commit the exact validated storyboard artifact. Overwriting requires author approval for this artifact.", concurrencySafe = false)
    public Map<String, Object> commitStoryboard(
            @ToolParam(name = "artifact_id", description = "UUID of the validated draft from this run") String artifactId,
            RuntimeContext context) {
        return commands.commitStoryboard(artifactId, context);
    }
}
