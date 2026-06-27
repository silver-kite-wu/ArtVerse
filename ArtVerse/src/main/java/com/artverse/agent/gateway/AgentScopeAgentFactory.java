package com.artverse.agent.gateway;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceService;
import com.artverse.agent.MangaAgentPromptProvider;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.SceneService;
import com.artverse.application.StructuredStoryboardService;
import com.artverse.application.tools.MangaContextTools;
import com.artverse.application.tools.MangaHitlTools;
import com.artverse.application.tools.MangaStoryboardTools;
import com.artverse.application.tools.MangaToolSupport;
import com.artverse.config.ArtVerseProperties;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeAgentFactory {

    private static final String CONTEXT_TOOLS = "context-tools";
    private static final String STORYBOARD_TOOLS = "storyboard-tools";
    private static final String HITL_TOOLS = "hitl-tools";

    private final Model model;
    private final CompactionConfig compactionConfig;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ArtVerseProperties properties;
    private final AgentWorkspaceService agentWorkspaceService;
    private final MangaAgentPromptProvider promptProvider;
    private final MangaImageRepository mangaImageRepository;
    private final SceneService sceneService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final AgentToolAuditService agentToolAuditService;
    private final AgentRunToolStatus agentRunToolStatus;
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public HarnessAgent getOrCreate(AgentRunRequest request) {
        Path requestWorkspace = agentWorkspaceService.workspaceFor(request);
        String agentKey = buildAgentCacheKey(request, defaultModelSpec(request.userApiKey()), requestWorkspace,
                promptProvider.promptVersionFor(request.taskType()));
        return agentCache.computeIfAbsent(agentKey, k -> buildAgent(request, requestWorkspace));
    }

    private HarnessAgent buildAgent(AgentRunRequest request, Path requestWorkspace) {
        AgentModelSpec modelSpec = request.modelSpec() != null
                ? request.modelSpec()
                : defaultModelSpec(request.userApiKey());
        Model effectiveModel = resolveModel(request.userApiKey(), modelSpec);
        HarnessAgent agent = HarnessAgent.builder()
                .name("artverse-story-" + request.storyId())
                .sysPrompt(promptProvider.promptFor(request.taskType()))
                .model(effectiveModel)
                .workspace(requestWorkspace)
                .compaction(compactionConfig)
                .enablePendingToolRecovery(true)
                .disableShellTool()
                .disableFilesystemTools()
                .middleware(new AgentScopeHitlSuspendMiddleware())
                .build();
        if (request.taskType() == AgentTaskType.MANGA_DIRECTOR) {
            configureMangaDirectorTools(agent.getToolkit());
        }
        return agent;
    }

    private void configureMangaDirectorTools(Toolkit toolkit) {
        MangaToolSupport support = new MangaToolSupport(agentRunToolStatus);
        toolkit.createToolGroup(
                CONTEXT_TOOLS,
                "Read-only manga chapter, story, storyboard, and image context tools.",
                true
        );
        toolkit.createToolGroup(
                STORYBOARD_TOOLS,
                "Storyboard generation and storyboard persistence tools.",
                true
        );
        toolkit.createToolGroup(
                HITL_TOOLS,
                "Human-in-the-loop tools for asking the user to choose or confirm.",
                true
        );
        toolkit.registration().tool(new MangaContextTools(
                mangaImageRepository, sceneService, chapterAccessService, agentToolAuditService, support
        )).group(CONTEXT_TOOLS).apply();
        toolkit.registration().tool(new MangaStoryboardTools(
                sceneService, structuredStoryboardService, chapterAccessService,
                generationGuardService, agentToolAuditService, support
        )).group(STORYBOARD_TOOLS).apply();
        toolkit.registration().tool(new MangaHitlTools(agentToolAuditService, support))
                .group(HITL_TOOLS)
                .apply();
        toolkit.setActiveGroups(List.of(CONTEXT_TOOLS, STORYBOARD_TOOLS, HITL_TOOLS));
        toolkit.registerMetaTool();
    }

    private Model resolveModel(String userApiKey, AgentModelSpec modelSpec) {
        if (userApiKey != null && !userApiKey.isBlank()) {
            log.info("Using user-provided DeepSeek API key for model");
            return OpenAIChatModel.builder()
                    .apiKey(userApiKey)
                    .modelName(modelSpec.model())
                    .baseUrl(modelSpec.baseUrl())
                    .stream(true)
                    .build();
        }
        return model;
    }

    private AgentModelSpec defaultModelSpec(String userApiKey) {
        if (userApiKey != null && !userApiKey.isBlank()) {
            return agentModelSpecFactory.deepSeek(userApiKey);
        }
        return agentModelSpecFactory.deepSeek(null);
    }

    static String buildAgentCacheKey(AgentRunRequest request, AgentModelSpec fallbackSpec, Path workspace,
                                     String promptVersion) {
        AgentModelSpec spec = request.modelSpec() != null ? request.modelSpec() : fallbackSpec;
        return String.join(":",
                "user", nullToKey(request.userId()),
                "story", String.valueOf(request.storyId()),
                "chapter", String.valueOf(request.chapterId()),
                "conversation", nullToKey(request.conversationId() == null ? null : request.conversationId().toString()),
                "task", request.taskType().name(),
                "provider", nullToKey(spec.provider()),
                "model", nullToKey(spec.model()),
                "baseUrl", AgentModelSpecFactory.shortHash(spec.baseUrl()),
                "key", nullToKey(spec.apiKeyHash()),
                "prompt", nullToKey(promptVersion),
                "workspace", workspace == null ? "none" : AgentModelSpecFactory.shortHash(workspace.toAbsolutePath().normalize().toString())
        );
    }

    private static String nullToKey(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
