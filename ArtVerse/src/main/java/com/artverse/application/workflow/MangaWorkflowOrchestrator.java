package com.artverse.application.workflow;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunEvent;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.ApiKeyService;
import com.artverse.application.CharacterProfileService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.MangaAgentRunService;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaImage;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaWorkflowOrchestrator {

    private static final int EXCERPT_LIMIT = 1800;

    private final MangaAgentConversationService mangaAgentConversationService;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaImageRepository mangaImageRepository;
    private final CharacterProfileService characterProfileService;
    private final MangaWorkflowNodeRegistry nodeRegistry;
    private final MangaIntentClassifierService intentClassifierService;

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                                AgentRunToolStatus.RunState toolState) {
        return runWithToolState(conversation, message, effectiveRequestId, MangaWorkflowRoute.DIRECTOR, toolState);
    }

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message,
                                                UUID effectiveRequestId, MangaWorkflowRoute route,
                                                AgentRunToolStatus.RunState toolState) {
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }
        var cached = mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId);
        if (cached.isPresent()) {
            return Map.of("reply", cached.get().getContent());
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        String deepseekApiKey = requireDeepseekApiKey(user);
        AgentModelSpec modelSpec = agentModelSpecFactory.deepSeek(deepseekApiKey);
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapter.getStory().getId(),
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowLeader(conversation, message, effectiveRequestId, route, deepseekApiKey, modelSpec, toolState)
        );
        return result;
    }

    public Map<String, Object> runWorkflowLeader(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId, MangaWorkflowRoute route,
                                                 String deepseekApiKey,
                                                 AgentModelSpec modelSpec, AgentRunToolStatus.RunState toolState) {
        MangaWorkflowContextSnapshot workflowContext = assembleContext(conversation, message, route);
        MangaWorkflowRoute executionRoute = resolveExecutionRoute(workflowContext, null, null);
        workflowContext = withRoute(workflowContext, executionRoute);
        log.info("Workflow route for request {} -> {}", effectiveRequestId, workflowContext.route());
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState, workflowContext);
        return nodeRegistry.handlerFor(workflowContext.route()).run(context);
    }

    public Map<String, Object> runWorkflowLeader(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId, String deepseekApiKey,
                                                 AgentModelSpec modelSpec, AgentRunToolStatus.RunState toolState) {
        return runWorkflowLeader(conversation, message, effectiveRequestId, MangaWorkflowRoute.DIRECTOR,
                deepseekApiKey, modelSpec, toolState);
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                MangaWorkflowRoute route,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef) {
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        Long chapterId = chapter.getId();
        MangaWorkflowRoute effectiveRoute = route == null ? MangaWorkflowRoute.DIRECTOR : route;
        MangaAgentRun run = mangaAgentRunService.startOrReuse(conversation, effectiveRequestId, message, effectiveRoute);
        runRef.set(run);
        sink.sendStatus(run, "智能体开始处理当前章节", effectiveRequestId);

        if (mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId).isPresent()) {
            Map<String, Object> result = runWithToolState(conversation, message, effectiveRequestId, toolState);
            mangaAgentRunService.markSucceeded(conversation, effectiveRequestId, String.valueOf(result.getOrDefault("reply", "")));
            sink.sendDone(run, String.valueOf(result.getOrDefault("reply", "")), effectiveRequestId);
            return;
        }

        String deepseekApiKey = requireDeepseekApiKey(user);
        AgentModelSpec modelSpec = agentModelSpecFactory.deepSeek(deepseekApiKey);
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapterId,
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowStream(conversation, message, effectiveRequestId, sink, toolState,
                        deepseekApiKey, modelSpec, effectiveRoute, run)
        );

        completeRun(run, sink, chapterId, user, effectiveRequestId, result);
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef) {
        runStreamLeader(conversation, message, effectiveRequestId, MangaWorkflowRoute.DIRECTOR, toolState, sink, runRef);
    }

    public Map<String, Object> runWorkflowStream(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId,
                                                 MangaAgentRunEventPublisher.RunEventSink sink,
                                                 AgentRunToolStatus.RunState toolState,
                                                 String deepseekApiKey, AgentModelSpec modelSpec,
                                                 MangaWorkflowRoute route,
                                                 MangaAgentRun run) {
        MangaWorkflowContextSnapshot workflowContext = assembleContext(conversation, message, route);
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState, workflowContext);
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.ROUTING.name(),
                "running",
                "正在路由当前任务",
                Map.of("route", workflowContext.route().name())
        ));
        MangaWorkflowRoute executionRoute = resolveExecutionRoute(workflowContext, run, sink);
        workflowContext = withRoute(workflowContext, executionRoute);
        if (executionRoute != MangaWorkflowRoute.AUTO) {
            sink.sendRunEvent(run, AgentRunEvent.step(
                    MangaWorkflowNode.ROUTING.name(),
                    "finished",
                    "任务路由已确定",
                    Map.of("route", executionRoute.name())
            ));
        }
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.COLLECTING_CONTEXT.name(),
                "running",
                "正在收集上下文信息",
                Map.of(
                        "storyTitle", workflowContext.storyTitle(),
                        "chapterDisplayName", workflowContext.chapterDisplayName(),
                        "sceneCount", workflowContext.sceneCount(),
                        "imageCount", workflowContext.imageCount(),
                        "warnings", workflowContext.warnings()
                )
        ));
        Map<String, Object> response = nodeRegistry.handlerFor(workflowContext.route())
                .stream(context, new MangaWorkflowStreamContext(run, sink));
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.EVALUATING.name(),
                "running",
                "正在评估生成结果",
                Map.of("degraded", Boolean.TRUE.equals(response.get("agent_final_response_degraded")))
        ));
        return response;
    }

    private MangaWorkflowRoute resolveExecutionRoute(MangaWorkflowContextSnapshot workflowContext,
                                                     MangaAgentRun run,
                                                     MangaAgentRunEventPublisher.RunEventSink sink) {
        if (workflowContext.route() != MangaWorkflowRoute.AUTO) {
            return workflowContext.route();
        }
        if (sink != null && run != null) {
            sink.sendRunEvent(run, AgentRunEvent.step(
                    MangaWorkflowNode.CLASSIFYING_INTENT.name(),
                    "running",
                    "正在识别用户意图",
                    Map.of("requestedRoute", MangaWorkflowRoute.AUTO.name())
            ));
        }
        MangaIntentResult intent = intentClassifierService.classify(
                workflowContext.conversationSummary(),
                workflowContext
        );
        if (sink != null && run != null) {
            sink.sendRunEvent(run, new AgentRunEvent(
                    "intent_classified",
                    "routing",
                    "用户意图识别完成",
                    null,
                    intent.requiresConfirmation() ? "waiting" : "finished",
                    null,
                    Map.of(
                            "selectedRoute", intent.route().name(),
                            "intent", intent.intent(),
                            "confidence", intent.confidence(),
                            "reason", intent.reason(),
                            "requiresConfirmation", intent.requiresConfirmation()
                    ),
                    java.time.OffsetDateTime.now()
            ));
        }
        if (intent.requiresConfirmation()) {
            throw new AgentUserInputRequiredException(intentConfirmationRequest(intent));
        }
        return intent.route() == MangaWorkflowRoute.AUTO ? MangaWorkflowRoute.CHAT : intent.route();
    }

    private AgentUserInputRequest intentConfirmationRequest(MangaIntentResult intent) {
        return new AgentUserInputRequest(
                "我还不能稳定判断你的意图。请选择这次要进入的模式：",
                List.of(
                        new AgentUserInputRequest.Option("DIRECTOR", "导演", "生成、改写或保存章节分镜", false),
                        new AgentUserInputRequest.Option("REVIEW", "质检", "检查现有分镜、风险和下一步动作", false),
                        new AgentUserInputRequest.Option("CHAT", "普通对话", "只回答问题，不修改章节内容", true)
                ),
                false,
                "识别结果：" + intent.intent() + "，置信度 " + intent.confidence() + "。原因：" + intent.reason()
        );
    }

    public Map<String, Object> runWorkflowStream(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId,
                                                 MangaAgentRunEventPublisher.RunEventSink sink,
                                                 AgentRunToolStatus.RunState toolState,
                                                 String deepseekApiKey, AgentModelSpec modelSpec,
                                                 MangaAgentRun run) {
        return runWorkflowStream(conversation, message, effectiveRequestId, sink, toolState,
                deepseekApiKey, modelSpec, MangaWorkflowRoute.DIRECTOR, run);
    }

    public void completeRun(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink, Long chapterId, User user,
                            UUID requestId, Map<String, Object> result) {
        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapterId)) {
            sink.complete();
            return;
        }
        String reply = String.valueOf(result.getOrDefault("reply", ""));
        if (Boolean.TRUE.equals(result.get("agent_final_response_degraded"))) {
            mangaAgentRunService.markDegraded(run.getConversation(), requestId, reply,
                    "Agent final response degraded after tool success");
        } else {
            mangaAgentRunService.markSucceeded(run.getConversation(), requestId, reply);
        }
        sink.sendDone(run, reply, requestId);
    }

    public String requireDeepseekApiKey(User user) {
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "请先在设置中配置 DeepSeek API Key 后再使用漫画智能体");
        }
        return deepseekApiKey;
    }

    private MangaWorkflowExecutionContext executionContext(MangaAgentConversation conversation, String message,
                                                           UUID effectiveRequestId, String deepseekApiKey,
                                                           AgentModelSpec modelSpec,
                                                           AgentRunToolStatus.RunState toolState,
                                                           MangaWorkflowContextSnapshot workflowContext) {
        return new MangaWorkflowExecutionContext(
                conversation,
                message,
                effectiveRequestId,
                deepseekApiKey,
                modelSpec,
                toolState,
                conversation.getUser(),
                conversation.getChapter(),
                workflowContext
        );
    }

    private MangaWorkflowContextSnapshot assembleContext(MangaAgentConversation conversation, String userMessage,
                                                         MangaWorkflowRoute route) {
        Chapter chapter = conversation.getChapter();
        Story story = chapter.getStory();
        List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(chapter.getId());
        Map<String, Object> characterProfile = characterProfileService.resolveEffective(chapter.getId());
        List<MangaAgentMessage> history = mangaAgentConversationService.listMessages(conversation);

        return new MangaWorkflowContextSnapshot(
                story.getId(),
                chapter.getId(),
                story.getTitle(),
                chapterDisplayName(chapter),
                story.getMangaStyle(),
                countScenes(chapter.getScenesText()),
                images == null ? 0 : images.size(),
                excerpt(chapter.novelContentOrJoinedMessages(), EXCERPT_LIMIT),
                excerpt(String.valueOf(characterProfile.getOrDefault("content", "")), EXCERPT_LIMIT),
                summarizeConversation(history, userMessage),
                route == null ? MangaWorkflowRoute.DIRECTOR : route,
                warningsFor(chapter, images)
        );
    }

    private MangaWorkflowContextSnapshot withRoute(MangaWorkflowContextSnapshot context, MangaWorkflowRoute route) {
        return new MangaWorkflowContextSnapshot(
                context.storyId(),
                context.chapterId(),
                context.storyTitle(),
                context.chapterDisplayName(),
                context.storyStyle(),
                context.sceneCount(),
                context.imageCount(),
                context.sourceExcerpt(),
                context.characterSummary(),
                context.conversationSummary(),
                route == null ? MangaWorkflowRoute.DIRECTOR : route,
                context.warnings()
        );
    }

    private List<String> warningsFor(Chapter chapter, List<MangaImage> images) {
        ArrayList<String> warnings = new ArrayList<>();
        if (chapter.novelContentOrJoinedMessages() == null || chapter.novelContentOrJoinedMessages().isBlank()) {
            warnings.add("chapter_source_missing");
        }
        if (images == null || images.isEmpty()) {
            warnings.add("no_generated_images");
        }
        return List.copyOf(warnings);
    }

    private String summarizeConversation(List<MangaAgentMessage> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        long startIndex = Math.max(0, history.size() - 8L);
        history.stream()
                .filter(item -> item.getRole() == MessageRole.USER || item.getRole() == MessageRole.ASSISTANT)
                .skip(startIndex)
                .forEach(item -> sb.append(item.getRole().name().toLowerCase()).append(": ")
                        .append(excerpt(item.getContent(), 220)).append("\n"));
        if (userMessage != null && !userMessage.isBlank()) {
            sb.append("user: ").append(excerpt(userMessage, 220)).append("\n");
        }
        return sb.toString().trim();
    }

    private String chapterDisplayName(Chapter chapter) {
        if (chapter.getDisplayTitle() != null && !chapter.getDisplayTitle().isBlank()) {
            return chapter.getDisplayTitle();
        }
        return "第" + chapter.getChapterNumber() + "话";
    }

    private String excerpt(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private int countScenes(String scenesText) {
        if (scenesText == null || scenesText.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < scenesText.length(); i++) {
            if (scenesText.charAt(i) == '"') {
                count++;
            }
        }
        return Math.max(1, count / 2);
    }
}
