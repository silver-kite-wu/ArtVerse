package com.artverse.agents;

import com.artverse.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class AgentWorkspaceService {

    private final @Qualifier("agentScopeWorkspace") Path workspaceRoot;

    public Path workspaceFor(AgentRunRequest request) {
        return workspaceFor(request.userId(), request.storyId(), request.conversationId());
    }

    public Path workspaceFor(String userId, Long storyId) {
        return workspaceFor(userId, storyId, null);
    }

    public Path workspaceFor(String userId, Long storyId, Object conversationId) {
        Path workspace = workspaceRoot
                .resolve("users")
                .resolve(AgentSessionIdFactory.safeSegment(userId))
                .resolve("stories")
                .resolve(AgentSessionIdFactory.safeSegment(storyId));
        if (conversationId != null) {
            workspace = workspace
                    .resolve("conversations")
                    .resolve(AgentSessionIdFactory.safeSegment(conversationId));
        }
        workspace = workspace.normalize();
        ensureWithinRoot(workspace);
        initialize(workspace);
        return workspace;
    }

    public void writeKnowledge(String userId, Long storyId, String content) {
        Path workspace = workspaceFor(userId, storyId);
        try {
            Files.writeString(workspace.resolve("KNOWLEDGE.md"), content == null ? "" : content);
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to write AgentScope knowledge file");
        }
    }

    private void initialize(Path workspace) {
        try {
            Files.createDirectories(workspace);
            writeIfAbsent(workspace.resolve("AGENTS.md"), defaultAgentsMd());
            writeIfAbsent(workspace.resolve("KNOWLEDGE.md"), "# Story Knowledge\n\nNo story context has been synced yet.\n");
            writeIfAbsent(workspace.resolve("MEMORY.md"), "# Long Term Memory\n\n");
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to initialize AgentScope workspace");
        }
    }

    private void ensureWithinRoot(Path workspace) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path target = workspace.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(400, "Invalid AgentScope workspace path");
        }
    }

    private void writeIfAbsent(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, content);
        }
    }

    private String defaultAgentsMd() {
        return """
                # ArtVerse Manga Director

                你是 ArtVerse 的中文 AI 漫画创作助手。

                ## 行为约定
                - 始终使用简洁中文回答。
                - 优先保持故事、角色、服装、伏笔和章节节奏的一致性。
                - 使用工具读取或修改工作流状态，不要假装已经完成工具未执行的动作。
                - 高成本动作前要确认当前章节和用户意图。
                """;
    }
}
