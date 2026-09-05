package com.artverse.config;

import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class AgentScopeConfig {

    private static final Path DEFAULT_WORKSPACE = Paths.get(System.getProperty("user.dir", "."), ".agentscope/workspace");

    @Bean
    public Path agentScopeWorkspace() {
        // Managed workspace contents are stored by AgentScope RemoteFilesystem.
        // This path is logical only and is never written by application code.
        return DEFAULT_WORKSPACE;
    }

    @Bean
    public CompactionConfig defaultCompactionConfig() {
        return CompactionConfig.builder()
                .triggerMessages(30)
                .keepMessages(10)
                .flushBeforeCompact(true)
                .build();
    }
}
