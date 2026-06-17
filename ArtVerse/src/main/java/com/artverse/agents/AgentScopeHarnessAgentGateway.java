package com.artverse.agents;

import com.artverse.application.MangaAgentToolFactory;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private final Model model;
    private final Path workspace;
    private final CompactionConfig compactionConfig;
    private final ArtVerseProperties properties;
    private final MangaAgentToolFactory mangaAgentToolFactory;
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public AgentScopeHarnessAgentGateway(
            Model model,
            @Qualifier("agentScopeWorkspace") Path workspace,
            CompactionConfig compactionConfig,
            ArtVerseProperties properties,
            MangaAgentToolFactory mangaAgentToolFactory) {
        this.model = model;
        this.workspace = workspace;
        this.compactionConfig = compactionConfig;
        this.properties = properties;
        this.mangaAgentToolFactory = mangaAgentToolFactory;
    }

    @Override
    public Flux<String> streamChat(AgentRunRequest request) {
        HarnessAgent agent = getOrCreateAgent(request);
        RuntimeContext ctx = buildRuntimeContext(request);
        List<Msg> messages = convertMessages(prepareInputMessages(request));

        return agent.streamEvents(messages, ctx)
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta)
                .filter(delta -> delta != null && !delta.isEmpty());
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
        String keySource = (request.userApiKey() != null && !request.userApiKey().isBlank()) ? "user" : "env";
        String agentKey = "story-" + request.storyId() + "-chapter-" + request.chapterId()
                + "-" + request.taskType().name().toLowerCase() + "-" + keySource;
        return agentCache.computeIfAbsent(agentKey, k -> buildAgent(request));
    }

    private HarnessAgent buildAgent(AgentRunRequest request) {
        Model effectiveModel = resolveModel(request.userApiKey());
        HarnessAgent agent = HarnessAgent.builder()
                .name("artverse-story-" + request.storyId())
                .sysPrompt(systemPromptFor(request.taskType()))
                .model(effectiveModel)
                .workspace(workspace)
                .compaction(compactionConfig)
                .build();
        if (request.taskType() == AgentTaskType.MANGA_DIRECTOR) {
            agent.getToolkit().registerTool(mangaAgentToolFactory.create(
                    String.valueOf(request.variables().getOrDefault("coze_api_key", "")),
                    request.chapterId()
            ));
        }
        return agent;
    }

    private String systemPromptFor(AgentTaskType taskType) {
        if (taskType == AgentTaskType.MANGA_DIRECTOR) {
            return "你是 ArtVerse 的 AI 漫画导演智能体，负责通过工具协助用户完成漫画创作流程。";
        }
        return "你是一个帮助用户创作小说和漫画内容的 AI 助手。";
    }

    private Model resolveModel(String userApiKey) {
        if (userApiKey != null && !userApiKey.isBlank()) {
            log.info("Using user-provided DeepSeek API key for model");
            return OpenAIChatModel.builder()
                    .apiKey(userApiKey)
                    .modelName(properties.getDeepseek().getModel())
                    .baseUrl(properties.getDeepseek().getBaseUrl())
                    .stream(true)
                    .build();
        }
        return model;
    }

    private RuntimeContext buildRuntimeContext(AgentRunRequest request) {
        return RuntimeContext.builder()
                .sessionId("story-" + request.storyId() + "-chapter-" + request.chapterId()
                        + "-" + request.taskType().name().toLowerCase())
                .userId(request.userId())
                .build();
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
