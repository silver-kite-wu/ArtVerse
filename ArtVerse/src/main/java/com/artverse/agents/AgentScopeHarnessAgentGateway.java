package com.artverse.agents;

import com.artverse.application.MangaAgentToolFactory;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Primary
public class AgentScopeHarnessAgentGateway implements HarnessAgentGateway {

    static final String PROMPT_VERSION = "v1";

    private final Model model;
    private final CompactionConfig compactionConfig;
    private final ArtVerseProperties properties;
    private final MangaAgentToolFactory mangaAgentToolFactory;
    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentSessionIdFactory agentSessionIdFactory;
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public AgentScopeHarnessAgentGateway(
            Model model,
            CompactionConfig compactionConfig,
            ArtVerseProperties properties,
            MangaAgentToolFactory mangaAgentToolFactory,
            AgentWorkspaceService agentWorkspaceService,
            AgentSessionIdFactory agentSessionIdFactory) {
        this.model = model;
        this.compactionConfig = compactionConfig;
        this.properties = properties;
        this.mangaAgentToolFactory = mangaAgentToolFactory;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSessionIdFactory = agentSessionIdFactory;
    }

    @Override
    public Flux<String> streamChat(AgentRunRequest request) {
        return streamEvents(request)
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta)
                .filter(delta -> delta != null && !delta.isEmpty());
    }

    @Override
    public Flux<AgentEvent> streamEvents(AgentRunRequest request) {
        HarnessAgent agent = getOrCreateAgent(request);
        RuntimeContext ctx = buildRuntimeContext(request);
        List<Msg> messages = convertMessages(prepareInputMessages(request));

        return agent.streamEvents(messages, ctx);
    }

    @Override
    public Mono<String> generateText(AgentRunRequest request) {
        HarnessAgent agent = getOrCreateAgent(request);
        RuntimeContext ctx = buildRuntimeContext(request);
        List<Msg> messages = convertMessages(prepareInputMessages(request));

        return agent.call(messages, ctx)
                .map(Msg::getTextContent);
    }

    private HarnessAgent getOrCreateAgent(AgentRunRequest request) {
        Path requestWorkspace = agentWorkspaceService.workspaceFor(request);
        String agentKey = buildAgentCacheKey(request, defaultModelSpec(request.userApiKey()), requestWorkspace);
        return agentCache.computeIfAbsent(agentKey, k -> buildAgent(request, requestWorkspace));
    }

    private HarnessAgent buildAgent(AgentRunRequest request, Path requestWorkspace) {
        AgentModelSpec modelSpec = request.modelSpec() != null
                ? request.modelSpec()
                : defaultModelSpec(request.userApiKey());
        Model effectiveModel = resolveModel(request.userApiKey(), modelSpec);
        HarnessAgent agent = HarnessAgent.builder()
                .name("artverse-story-" + request.storyId())
                .sysPrompt(systemPromptFor(request.taskType()))
                .model(effectiveModel)
                .workspace(requestWorkspace)
                .compaction(compactionConfig)
                .enablePendingToolRecovery(true)
                .disableShellTool()
                .disableFilesystemTools()
                .hook(new AgentScopeHitlSuspendHook())
                .build();
        if (request.taskType() == AgentTaskType.MANGA_DIRECTOR) {
            agent.getToolkit().registerTool(mangaAgentToolFactory.create(
                    String.valueOf(request.variables().getOrDefault("coze_api_key", "")),
                    request.chapterId(),
                    parseUserIdForTool(request.userId())
            ));
        }
        return agent;
    }

    static Long parseUserIdForTool(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (Exception e) {
            throw new BusinessException(400, "Invalid agent user id");
        }
    }

    private String systemPromptFor(AgentTaskType taskType) {
        if (taskType == AgentTaskType.MANGA_DIRECTOR) {
            return """
                    You are ArtVerse Manga Director, a business workflow agent for manga creation.
                    Always answer users in concise Chinese.
                    The current chapter source text is stored in the database field chapters.novel_content and is synced into KNOWLEDGE.md before each run.
                    Use ArtVerse business tools such as get_chapter_context to inspect source content, storyboard scenes, image status, and chapter metadata.
                    Do not use shell, execute, filesystem listing, or source-code search to find story or chapter content.
                    """;
        }
        return "You are an AI assistant that helps users create novel and manga content.";
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
            return new AgentModelSpec(
                    "deepseek",
                    properties.getDeepseek().getBaseUrl(),
                    properties.getDeepseek().getModel(),
                    AgentModelSpecFactory.shortHash(userApiKey)
            );
        }
        return new AgentModelSpec(
                "deepseek",
                properties.getDeepseek().getBaseUrl(),
                properties.getDeepseek().getModel(),
                "env"
        );
    }

    static String buildAgentCacheKey(AgentRunRequest request, AgentModelSpec fallbackSpec) {
        return buildAgentCacheKey(request, fallbackSpec, null);
    }

    static String buildAgentCacheKey(AgentRunRequest request, AgentModelSpec fallbackSpec, Path workspace) {
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
                "prompt", PROMPT_VERSION,
                "workspace", workspace == null ? "none" : AgentModelSpecFactory.shortHash(workspace.toAbsolutePath().normalize().toString())
        );
    }

    private static String nullToKey(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private RuntimeContext buildRuntimeContext(AgentRunRequest request) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(agentSessionIdFactory.create(request))
                .userId(request.userId());
        if (request.requestId() != null) {
            builder.put(AgentRunContext.class, new AgentRunContext(request.requestId()));
        }
        return builder.build();
    }

    static RuntimeContext buildRuntimeContextForTest(AgentRunRequest request, AgentSessionIdFactory factory) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(factory.create(request))
                .userId(request.userId());
        if (request.requestId() != null) {
            builder.put(AgentRunContext.class, new AgentRunContext(request.requestId()));
        }
        return builder.build();
    }

    static List<AgentMessage> prepareInputMessages(AgentRunRequest request) {
        List<String> systemMessages = new ArrayList<>();
        List<AgentMessage> inputMessages = new ArrayList<>();

        for (AgentMessage message : request.messages()) {
            if ("system".equalsIgnoreCase(message.role())) {
                systemMessages.add(message.content());
            } else {
                inputMessages.add(message);
            }
        }

        if (systemMessages.isEmpty()) {
            return inputMessages;
        }

        String systemPrompt = String.join("\n\n", systemMessages);
        if (inputMessages.isEmpty()) {
            return List.of(new AgentMessage("user", systemPrompt));
        }

        AgentMessage first = inputMessages.get(0);
        List<AgentMessage> prepared = new ArrayList<>(inputMessages);
        prepared.set(0, new AgentMessage(first.role(), systemPrompt + "\n\n" + first.content()));
        return prepared;
    }

    private List<Msg> convertMessages(List<AgentMessage> messages) {
        return messages.stream()
                .map(m -> Msg.builder()
                        .role(convertRole(m.role()))
                        .textContent(m.content())
                        .build())
                .toList();
    }

    private MsgRole convertRole(String role) {
        return switch (role.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            default -> MsgRole.USER;
        };
    }
}
