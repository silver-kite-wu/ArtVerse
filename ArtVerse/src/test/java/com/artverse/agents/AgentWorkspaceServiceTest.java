package com.artverse.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesUserStoryWorkspace() {
        AgentWorkspaceService service = new AgentWorkspaceService(tempDir);

        Path workspace = service.workspaceFor("user-1", 42L);

        assertThat(workspace).isEqualTo(tempDir.resolve("users").resolve("user-1").resolve("stories").resolve("42").normalize());
        assertThat(Files.exists(workspace.resolve("AGENTS.md"))).isTrue();
        assertThat(Files.exists(workspace.resolve("KNOWLEDGE.md"))).isTrue();
        assertThat(Files.exists(workspace.resolve("MEMORY.md"))).isTrue();
    }

    @Test
    void sanitizesPathSegmentsAndDoesNotLeakApiKey() {
        AgentWorkspaceService service = new AgentWorkspaceService(tempDir);
        AgentRunRequest request = new AgentRunRequest(
                "user/../../secret",
                7L,
                9L,
                AgentTaskType.CHAT,
                List.of(new AgentMessage("user", "hello")),
                Map.of(),
                new AgentModelSpec("deepseek", "https://api.deepseek.com", "deepseek-chat", "hash-only"),
                "sk-secret"
        );

        Path workspace = service.workspaceFor(request);

        String path = workspace.toString();
        assertThat(path).contains("users");
        assertThat(path).contains("stories");
        assertThat(path).doesNotContain("sk-secret");
        assertThat(workspace.toAbsolutePath().normalize().toString())
                .startsWith(tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void writesKnowledgeFile() throws Exception {
        AgentWorkspaceService service = new AgentWorkspaceService(tempDir);

        service.writeKnowledge("user-1", 42L, "# Story Knowledge\n\nSynced.");

        Path knowledge = tempDir.resolve("users").resolve("user-1").resolve("stories").resolve("42").resolve("KNOWLEDGE.md");
        assertThat(Files.readString(knowledge)).contains("Synced.");
    }
}
