package com.artverse.agent.gateway;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceService;
import com.artverse.agent.ArtVerseSkillRepository;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.agent.MangaAgentPromptProvider;
import com.artverse.agent.PostgresAgentWorkspaceStore;
import com.artverse.application.ArtVerseSkillRegistry;
import com.artverse.application.MangaAgentRunService;
import com.artverse.common.BusinessException;
import com.artverse.agent.gateway.tools.AgentToolConfigurationRegistry;
import com.artverse.config.ArtVerseProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeAgentFactory {

    private final CompactionConfig compactionConfig;
    private final ArtVerseProperties properties;
    private final AgentWorkspaceService agentWorkspaceService;
    private final MangaAgentPromptProvider promptProvider;
    private final AgentStateStore agentStateStore;
    private final AgentToolConfigurationRegistry toolConfigurationRegistry;
    private final ArtVerseSkillRegistry skillRegistry;
    private final ArtVerseSkillRepository skillRepository;
    private final MangaAgentRunService runService;
    private final PostgresAgentWorkspaceStore workspaceStore;
    private final AgentScopeSystemPromptMiddleware systemPromptMiddleware = new AgentScopeSystemPromptMiddleware();
    private Cache<String, HarnessAgent> agents;

    @PostConstruct
    void initializeCache() {
        agents = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.getAgent().getAgentCacheMaxSize()))
                .expireAfterAccess(Duration.ofMinutes(
                        Math.max(1, properties.getAgent().getAgentCacheExpireAfterMinutes())))
                .removalListener((String key, HarnessAgent agent, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (agent != null) {
                        agent.close();
                    }
                })
                .build();
    }

    public HarnessAgent getOrCreate(AgentRunRequest request) {
        if (request.modelSpec() == null) {
            throw new BusinessException(400,
                    "LLM provider configuration is missing. Please configure it in Settings.");
        }
        Path requestWorkspace = agentWorkspaceService.workspaceFor(request);
        BusinessSkillSelection selection = skillRegistry.resolveSelection(request);
        String promptVersion = promptProvider.promptVersionFor(request.taskType());
        if (request.requestId() != null && request.chapterId() != null) {
            runService.recordSkillSelection(Long.valueOf(request.userId()), request.chapterId(), request.requestId(),
                    selection, promptVersion);
            runService.recordStepSkillSelection(Long.valueOf(request.userId()), request.chapterId(),
                    request.requestId(), String.valueOf(request.variables().getOrDefault("step_id", "")), selection);
        }
        String agentKey = buildAgentCacheKey(request, requestWorkspace, promptVersion, selection.cacheKey());
        return agents.get(agentKey, key -> buildAgent(request, requestWorkspace, selection));
    }

    private HarnessAgent buildAgent(AgentRunRequest request, Path requestWorkspace,
                                    BusinessSkillSelection selection) {
        Model effectiveModel = resolveModel(request.llmApiKey(), request.modelSpec());
        HarnessAgent.Builder builder = HarnessAgent.builder()
                // AgentScope 2.0 keeps per-session state in RuntimeContext. The agent
                // itself is intentionally shared and must not encode tenant identity.
                .name("artverse-" + request.taskType().sessionSuffix())
                .sysPrompt(promptProvider.promptFor(request.taskType()))
                .model(effectiveModel)
                .workspace(requestWorkspace)
                .abstractFilesystem(new RemoteFilesystem(
                        workspaceStore, AgentWorkspaceService::namespaceFor))
                .compaction(compactionConfig)
                .stateStore(agentStateStore)
                .maxIters(maxIters(request.taskType()))
                .maxContextTokens(properties.getAgent().getMaxInputTokens())
                .enablePendingToolRecovery(true)
                .middleware(systemPromptMiddleware)
                .disableShellTool()
                .disableFilesystemTools();
        if (!selection.isEmpty()) {
            builder.skillRepository(skillRepository)
                    .disableDynamicSkills()
                    .disableDefaultWorkspaceSkills()
                    .enableSkills(selection.skillKeys().toArray(String[]::new));
        }
        if (request.taskType().subagentDeclarations().size() > properties.getAgent().getMaxSubagents()) {
            throw new IllegalStateException("Task declares more subagents than the configured hard limit");
        }
        for (var declaration : request.taskType().subagentDeclarations()) {
            builder.subagent(declaration);
        }
        HarnessAgent agent = builder.build();
        toolConfigurationRegistry.configure(agent.getToolkit(), request.taskType());
        return agent;
    }

    private Model resolveModel(String llmApiKey, AgentModelSpec modelSpec) {
        if (hasApiKey(llmApiKey)) {
            log.info("Using user-provided {} API key for model: {}", modelSpec.provider(), modelSpec.model());
            return buildChatModel(llmApiKey, modelSpec);
        }
        throw new BusinessException(400,
                "LLM provider API key is missing. Please configure it in Settings.");
    }

    /**
     * Build a chat model for the given provider.
     * All currently supported providers (deepseek, openai, openroute) use the
     * OpenAI-compatible protocol. Non-compatible providers can be added here.
     */
    private OpenAIChatModel buildChatModel(String llmApiKey, AgentModelSpec modelSpec) {
        return OpenAIChatModel.builder()
                .apiKey(llmApiKey)
                .modelName(modelSpec.model())
                .baseUrl(modelSpec.baseUrl())
                .stream(true)
                .nativeStructuredOutput(true)
                .nativeStructuredOutputWithTools(true)
                .build();
    }

    private static boolean hasApiKey(String key) {
        return key != null && !key.isBlank();
    }

    private int maxIters(AgentTaskType taskType) {
        ArtVerseProperties.Agent agent = properties.getAgent();
        return switch (taskType) {
            case MANGA_ROUTER -> agent.getRouterMaxModelCalls();
            case MANGA_CONVERSATION, MANGA_CREATIVE -> agent.getConversationMaxModelCalls();
            case STORY_CHAT_READ -> agent.getConversationMaxModelCalls();
            case STORY_CHAT_WRITE -> agent.getDirectorMaxModelCalls();
            case MANGA_STORYBOARD -> agent.getStoryboardMaxModelCalls();
            case MANGA_REVIEW -> 3;
            case MANGA_DIRECTOR -> agent.getDirectorMaxModelCalls();
            case KNOWLEDGE_EXTRACTION -> 2;
            default -> 4;
        };
    }

    static String buildAgentCacheKey(AgentRunRequest request, Path workspace,
                                     String promptVersion, String skillVersion) {
        AgentModelSpec spec = request.modelSpec();
        return String.join(":",
                "task", request.taskType().name(),
                "provider", nullToKey(spec.provider()),
                "model", nullToKey(spec.model()),
                "baseUrl", AgentModelSpecFactory.shortHash(spec.baseUrl()),
                "key", nullToKey(spec.apiKeyHash()),
                "prompt", nullToKey(promptVersion),
                "skill", nullToKey(skillVersion),
                // The workspace is a stable logical root. User, story and conversation
                // isolation is supplied by RuntimeContext/RemoteFilesystem at call time.
                "workspace", workspace == null ? "none" : AgentModelSpecFactory.shortHash(workspace.toAbsolutePath().normalize().toString())
        );
    }

    private static String nullToKey(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
