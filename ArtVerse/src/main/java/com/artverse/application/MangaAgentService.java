package com.artverse.application;

import com.artverse.agents.AgentMessage;
import com.artverse.agents.AgentModelSpec;
import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentRunEvent;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentScopeEventMapper;
import com.artverse.agents.AgentTaskType;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.application.workflow.MangaWorkflowContextAssembler;
import com.artverse.application.workflow.MangaWorkflowContextSnapshot;
import com.artverse.application.workflow.MangaWorkflowNode;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import io.agentscope.core.tool.ToolSuspendException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaAgentService {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final MangaAgentConversationRegistry mangaAgentConversationRegistry;
    private final HarnessAgentGateway harnessAgentGateway;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final AgentWorkspaceSyncService agentWorkspaceSyncService;
    private final ApiKeyService apiKeyService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final ArtVerseProperties properties;
    private final AgentRunToolStatus agentRunToolStatus;
    private final AgentScopeEventMapper agentScopeEventMapper;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaAgentRunEventPublisher mangaAgentRunEventPublisher;
    private final MangaWorkflowContextAssembler mangaWorkflowContextAssembler;

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return mangaAgentConversationService.listMessages(conversation);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentConversation> listConversations(Long chapterId, User user) {
        return mangaAgentConversationRegistry.list(chapterId, user);
    }

    public MangaAgentConversation createConversation(Long chapterId, User user) {
        return mangaAgentConversationRegistry.create(chapterId, user);
    }

    public MangaAgentConversation archiveConversation(Long chapterId, UUID conversationId, User user) {
        return mangaAgentConversationRegistry.archive(chapterId, user, conversationId);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return mangaAgentConversationService.listMessages(conversation);
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return run(conversation, message, requestId, user);
    }

    public RunResult run(MangaAgentConversation conversation, String message, UUID requestId, User user) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        try (AgentRunToolStatus.RunScope scope = agentRunToolStatus.start(
                user.getId(),
                conversation.getChapter().getId(),
                effectiveRequestId
        )) {
            return runWithToolState(conversation, message, effectiveRequestId, scope.state());
        }
    }

    public SseEmitter runStream(Long chapterId, String message, UUID requestId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return runStreamInternal(conversation, message, requestId, MangaAgentRunEventPublisher.StreamProtocol.LEGACY_AND_AG_UI);
    }

    public SseEmitter runAgUiStream(Long chapterId, String message, UUID requestId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return runStreamInternal(conversation, message, requestId, MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY);
    }

    public SseEmitter runAgUiStream(Long chapterId, UUID conversationId, String message, UUID requestId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return runStreamInternal(conversation, message, requestId, MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY);
    }

    private SseEmitter runStreamInternal(MangaAgentConversation conversation, String message, UUID requestId,
                                         MangaAgentRunEventPublisher.StreamProtocol protocol) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = sinkFor(emitter, protocol);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    effectiveRequestId,
                    event -> sink.sendToolEvent(runRef.get(), event)
            )) {
                runStreamLeader(conversation, message, effectiveRequestId, ignored.state(), sink, runRef);
            } catch (AgentUserInputRequiredException e) {
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markWaiting(conversation, effectiveRequestId, e.request());
                }
                sink.sendUserInputRequested(run, effectiveRequestId, e.request());
            } catch (Exception e) {
                String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
                MangaAgentRun run = runRef.get();
                if (run != null && !mangaAgentRunService.isTerminal(conversation, effectiveRequestId)) {
                    mangaAgentRunService.markFailed(conversation, effectiveRequestId, detail);
                }
                sink.sendError(run, effectiveRequestId, detail);
            }
        });

        return emitter;
    }

    public SseEmitter resumeStream(Long chapterId, UUID requestId, String answer, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return resumeStreamInternal(conversation, requestId, answer, MangaAgentRunEventPublisher.StreamProtocol.LEGACY_AND_AG_UI);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return resumeStreamInternal(conversation, requestId, answer, MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return resumeStreamInternal(conversation, requestId, answer, MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY);
    }

    private SseEmitter resumeStreamInternal(MangaAgentConversation conversation, UUID requestId, String answer,
                                            MangaAgentRunEventPublisher.StreamProtocol protocol) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        MangaAgentRun waitingRun = mangaAgentRunService.requireWaitingRun(conversation, requestId);
        AgentUserInputRequest waiting = mangaAgentRunService.waitingInput(waitingRun);
        String message = mangaAgentConversationService.resumeMessage(waitingRun.getInputMessage(), waiting, answer);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        mangaAgentRunService.markRunning(conversation, requestId);

        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = sinkFor(emitter, protocol);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>(waitingRun);
        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    requestId,
                    event -> sink.sendToolEvent(runRef.get(), event)
            )) {
                sink.sendUserAnswerEvent(waitingRun, requestId, answer);
                runStreamLeader(conversation, message, requestId, ignored.state(), sink, runRef);
            } catch (AgentUserInputRequiredException e) {
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markWaiting(conversation, requestId, e.request());
                }
                sink.sendUserInputRequested(run, requestId, e.request());
            } catch (Exception e) {
                String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
                MangaAgentRun run = runRef.get();
                if (run != null && !mangaAgentRunService.isTerminal(conversation, requestId)) {
                    mangaAgentRunService.markFailed(conversation, requestId, detail);
                }
                sink.sendError(run, requestId, detail);
            }
        });
        return emitter;
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return resume(conversation, requestId, answer);
    }

    public RunResult resume(MangaAgentConversation conversation, UUID requestId, String answer) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        MangaAgentRun waitingRun = mangaAgentRunService.requireWaitingRun(conversation, requestId);
        AgentUserInputRequest waiting = mangaAgentRunService.waitingInput(waitingRun);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        mangaAgentRunService.markRunning(conversation, requestId);
        String message = mangaAgentConversationService.resumeMessage(waitingRun.getInputMessage(), waiting, answer);
        try {
            RunResult result = run(conversation, message, requestId, user);
            mangaAgentRunService.markSucceeded(conversation, requestId, result.reply());
            return result;
        } catch (AgentUserInputRequiredException e) {
            mangaAgentRunService.markWaiting(conversation, requestId, e.request());
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
            mangaAgentRunService.markFailed(conversation, requestId, detail);
            throw e instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(detail, e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRunService.RunSnapshot> latestOpenRun(Long chapterId, User user) {
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return mangaAgentRunService.findLatestOpenRun(conversation)
                .map(mangaAgentRunService::snapshot);
    }

    public Optional<MangaAgentRunService.RunSnapshot> latestOpenRun(Long chapterId, UUID conversationId, User user) {
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return mangaAgentRunService.findLatestOpenRun(conversation)
                .map(mangaAgentRunService::snapshot);
    }

    public MangaAgentRunService.RunSnapshot getRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return mangaAgentRunService.findRun(conversation, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunService.RunSnapshot getRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return mangaAgentRunService.findRun(conversation, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunService.RunSnapshot cancelRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        MangaAgentRun run = mangaAgentRunService.cancel(conversation, requestId, "Agent run cancelled by user");
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        return mangaAgentRunService.snapshot(run);
    }

    public MangaAgentRunService.RunSnapshot cancelRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        MangaAgentRun run = mangaAgentRunService.cancel(conversation, requestId, "Agent run cancelled by user");
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        return mangaAgentRunService.snapshot(run);
    }

    private RunResult runWithToolState(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                       AgentRunToolStatus.RunState toolState) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }
        var cached = mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId);
        if (cached.isPresent()) {
            return new RunResult(cached.get().getContent(), effectiveRequestId);
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
                () -> runWorkflowLeader(conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState)
        );
        return new RunResult(String.valueOf(result.getOrDefault("reply", "")), effectiveRequestId);
    }

    private Map<String, Object> runWorkflowLeader(MangaAgentConversation conversation, String message,
                                                   UUID effectiveRequestId, String deepseekApiKey,
                                                   AgentModelSpec modelSpec, AgentRunToolStatus.RunState toolState) {
        MangaWorkflowContextSnapshot workflowContext = mangaWorkflowContextAssembler.assemble(conversation, message);
        log.info("Workflow route for request {} -> {}", effectiveRequestId, workflowContext.route());
        return runLeader(conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState);
    }

    private void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                 AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                 AtomicReference<MangaAgentRun> runRef) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        Long chapterId = chapter.getId();
        MangaAgentRun run = mangaAgentRunService.startOrReuse(conversation, effectiveRequestId, message);
        runRef.set(run);
        sink.sendStatus(run, "智能体开始处理当前章节", effectiveRequestId);

        if (mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId).isPresent()) {
            RunResult result = runWithToolState(conversation, message, effectiveRequestId, toolState);
            mangaAgentRunService.markSucceeded(conversation, effectiveRequestId, result.reply());
            sink.sendDone(run, result.reply(), result.requestId());
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
                        deepseekApiKey, modelSpec, run, user, chapter)
        );

        completeRun(run, sink, chapterId, user, effectiveRequestId, result);
    }

    private Map<String, Object> runLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                          String deepseekApiKey, AgentModelSpec modelSpec,
                                          AgentRunToolStatus.RunState toolState) {
        Chapter chapter = conversation.getChapter();
        User user = conversation.getUser();
        List<AgentMessage> messages = prepareAgentMessages(conversation, message, effectiveRequestId);
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(chapter.getId(), String.valueOf(user.getId()));

        AgentRunRequest request = buildRunRequest(conversation, messages, modelSpec, deepseekApiKey, effectiveRequestId);
        try {
            String reply = harnessAgentGateway.generateText(request).block(agentRunTimeout());
            throwIfWaitingForUser(toolState);
            if (reply == null || reply.isBlank()) {
                throw new BusinessException(502, "Agent returned empty response");
            }
            mangaAgentConversationService.saveMessage(conversation, MessageRole.ASSISTANT, reply, effectiveRequestId);
            return Map.of("reply", reply);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(toolState);
            throw new BusinessException(502, "Agent tool suspended without user input");
        } catch (BusinessException e) {
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        conversation, effectiveRequestId, toolState, e.getMessage());
            }
            mangaAgentConversationService.saveFailureMessage(conversation, e.getMessage(), effectiveRequestId);
            throw e;
        } catch (Exception e) {
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        conversation, effectiveRequestId, toolState, error);
            }
            mangaAgentConversationService.saveFailureMessage(conversation, error, effectiveRequestId);
            throw new BusinessException(502, "Agent service failed: " + error);
        }
    }

    private Map<String, Object> runWorkflowStream(MangaAgentConversation conversation, String message,
                                                  UUID effectiveRequestId,
                                                  MangaAgentRunEventPublisher.RunEventSink sink,
                                                  AgentRunToolStatus.RunState toolState,
                                                  String deepseekApiKey, AgentModelSpec modelSpec,
                                                  MangaAgentRun run, User user, Chapter chapter) {
        MangaWorkflowContextSnapshot workflowContext = mangaWorkflowContextAssembler.assemble(conversation, message);
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.ROUTING.name(),
                "running",
                "正在路由当前任务",
                Map.of("route", workflowContext.route().name())
        ));
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
        List<AgentMessage> messages = prepareAgentMessages(conversation, message, effectiveRequestId);
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.GENERATING.name(),
                "running",
                "正在调用智能体生成内容",
                Map.of("provider", modelSpec.provider(), "model", modelSpec.model())
        ));
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(chapter.getId(), String.valueOf(user.getId()));
        AgentRunRequest request = buildRunRequest(conversation, messages, modelSpec, deepseekApiKey, effectiveRequestId);
        Map<String, Object> response = executeStreamedRequest(run, sink, toolState, request, chapter, user, effectiveRequestId);
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.EVALUATING.name(),
                "running",
                "正在评估生成结果",
                Map.of("degraded", Boolean.TRUE.equals(response.get("agent_final_response_degraded")))
        ));
        return response;
    }

    private Map<String, Object> executeStreamedRequest(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink,
                                                       AgentRunToolStatus.RunState toolState, AgentRunRequest request,
                                                       Chapter chapter, User user, UUID requestId) {
        StringBuilder reply = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            harnessAgentGateway.streamEvents(request)
                    .doOnNext(event -> agentScopeEventMapper.map(event).ifPresent(mapped -> {
                        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapter.getId())) {
                            throw new AgentRunTerminatedException();
                        }
                        if ("text_delta".equals(mapped.type()) && mapped.text() != null) {
                            reply.append(mapped.text());
                        }
                        sink.sendRunEvent(run, mapped);
                    }))
                    .blockLast(agentRunTimeout());
            finished.set(true);
            throwIfWaitingForUser(toolState);
        } catch (AgentRunTerminatedException e) {
            return Map.of("reply", "");
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(toolState);
            throw new BusinessException(502, "Agent tool suspended without user input");
        } catch (Exception e) {
            if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapter.getId())) {
                return Map.of("reply", "");
            }
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        run.getConversation(), requestId, toolState, error);
            }
            mangaAgentConversationService.saveFailureMessage(run.getConversation(), error, requestId);
            throw new BusinessException(502, "Agent service failed: " + error);
        }

        String finalReply = reply.toString().trim();
        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapter.getId())) {
            return Map.of("reply", "");
        }
        if (!finished.get() || finalReply.isBlank()) {
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        run.getConversation(), requestId, toolState, "Agent returned empty response");
            }
            throw new BusinessException(502, "Agent returned empty response");
        }

        mangaAgentConversationService.saveMessage(run.getConversation(), MessageRole.ASSISTANT, finalReply, requestId);
        return Map.of("reply", finalReply);
    }

    private void completeRun(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink, Long chapterId, User user,
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

    private List<AgentMessage> prepareAgentMessages(MangaAgentConversation conversation, String message, UUID requestId) {
        mangaAgentConversationService.saveMessage(conversation, MessageRole.USER, message, requestId);
        List<MangaAgentMessage> history = mangaAgentConversationService.listMessages(conversation);
        return mangaAgentConversationService.buildMessages(
                conversation.getChapter(),
                conversation.getUser(),
                history,
                message,
                requestId
        );
    }

    private AgentRunRequest buildRunRequest(MangaAgentConversation conversation, List<AgentMessage> messages,
                                            AgentModelSpec modelSpec, String deepseekApiKey, UUID requestId) {
        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        return new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapter.getId(),
                AgentTaskType.MANGA_DIRECTOR,
                messages,
                Map.of("coze_api_key", nullToBlank(apiKeyService.getDecryptedKey(user, "coze"))),
                modelSpec,
                deepseekApiKey,
                requestId,
                conversation.getConversationUuid()
        );
    }

    private String requireDeepseekApiKey(User user) {
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new BusinessException(400, "请先在设置中配置 DeepSeek API Key 后再使用漫画智能体");
        }
        return deepseekApiKey;
    }

    private void throwIfWaitingForUser(AgentRunToolStatus.RunState toolState) {
        AgentUserInputRequest waiting = toolState.userInputRequest();
        if (waiting != null) {
            throw new AgentUserInputRequiredException(waiting);
        }
    }

    private Duration agentRunTimeout() {
        return Duration.ofSeconds(Math.max(1, properties.getAgent().getRunTimeoutSeconds()));
    }

    private void interruptStaleRunningRuns() {
        int staleSeconds = Math.max(
                properties.getAgent().getStaleRunningSeconds(),
                properties.getAgent().getRunTimeoutSeconds() * 2
        );
        mangaAgentRunService.interruptStaleRunningRuns(OffsetDateTime.now().minusSeconds(staleSeconds));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private MangaAgentRunEventPublisher.RunEventSink sinkFor(SseEmitter emitter,
                                                             MangaAgentRunEventPublisher.StreamProtocol protocol) {
        return protocol == MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY
                ? mangaAgentRunEventPublisher.agUiOnly(emitter)
                : mangaAgentRunEventPublisher.legacyAndAgUi(emitter);
    }

    public record RunResult(String reply, UUID requestId) {
    }

    private static class AgentRunTerminatedException extends RuntimeException {
    }
}
