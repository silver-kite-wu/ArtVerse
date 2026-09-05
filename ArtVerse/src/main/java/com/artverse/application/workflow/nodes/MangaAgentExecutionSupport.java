package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentDataBlock;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.ApiKeyService;
import com.artverse.application.KnowledgeService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNode;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.application.workflow.MangaReviewMetrics;
import com.artverse.application.workflow.ToolContractVerifier;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared execution support for all manga workflow agent nodes.
 * Serves all specialized agents (Conversation, Creative, Storyboard, Review, Director).
 */
@Slf4j
@Component
public class MangaAgentExecutionSupport {

    private final MangaAgentConversationService conversationService;
    private final AgentWorkspaceSyncService workspaceSyncService;
    private final ApiKeyService apiKeyService;
    private final AgentScopeHarnessAgentGateway harnessAgentGateway;
    private final ArtVerseProperties properties;
    private final MangaReviewMetrics reviewMetrics;
    private final KnowledgeService knowledgeService;
    private final MangaAgentRunService runService;
    private final ObjectMapper objectMapper;
    private final ToolContractVerifier toolContractVerifier;

    @Autowired
    public MangaAgentExecutionSupport(MangaAgentConversationService conversationService,
                                      AgentWorkspaceSyncService workspaceSyncService,
                                      ApiKeyService apiKeyService,
                                      AgentScopeHarnessAgentGateway harnessAgentGateway,
                                      ArtVerseProperties properties,
                                      MangaReviewMetrics reviewMetrics,
                                      KnowledgeService knowledgeService,
                                      MangaAgentRunService runService,
                                      ObjectMapper objectMapper,
                                      ToolContractVerifier toolContractVerifier) {
        this.conversationService = conversationService;
        this.workspaceSyncService = workspaceSyncService;
        this.apiKeyService = apiKeyService;
        this.harnessAgentGateway = harnessAgentGateway;
        this.properties = properties;
        this.reviewMetrics = reviewMetrics;
        this.knowledgeService = knowledgeService;
        this.runService = runService;
        this.objectMapper = objectMapper;
        this.toolContractVerifier = toolContractVerifier;
    }

    public MangaAgentExecutionSupport(MangaAgentConversationService conversationService,
                                      AgentWorkspaceSyncService workspaceSyncService,
                                      ApiKeyService apiKeyService,
                                      AgentScopeHarnessAgentGateway harnessAgentGateway,
                                      ArtVerseProperties properties,
                                      MangaReviewMetrics reviewMetrics,
                                      KnowledgeService knowledgeService) {
        this(conversationService, workspaceSyncService, apiKeyService, harnessAgentGateway,
                properties, reviewMetrics, knowledgeService, null, new ObjectMapper(), null);
    }

    public MangaAgentExecutionSupport(MangaAgentConversationService conversationService,
                                      AgentWorkspaceSyncService workspaceSyncService,
                                      ApiKeyService apiKeyService,
                                      AgentScopeHarnessAgentGateway harnessAgentGateway,
                                      ArtVerseProperties properties,
                                      MangaReviewMetrics reviewMetrics,
                                      KnowledgeService knowledgeService,
                                      MangaAgentRunService runService,
                                      ObjectMapper objectMapper) {
        this(conversationService, workspaceSyncService, apiKeyService, harnessAgentGateway,
                properties, reviewMetrics, knowledgeService, runService, objectMapper, null);
    }

    public MangaAgentExecutionSupport(MangaAgentConversationService conversationService,
                                      AgentWorkspaceSyncService workspaceSyncService,
                                      ApiKeyService apiKeyService,
                                      AgentScopeHarnessAgentGateway harnessAgentGateway,
                                      ArtVerseProperties properties,
                                      MangaReviewMetrics reviewMetrics) {
        this(conversationService, workspaceSyncService, apiKeyService, harnessAgentGateway,
                properties, reviewMetrics, null, null, new ObjectMapper(), null);
    }

    public List<AgentMessage> prepareAgentMessages(MangaWorkflowExecutionContext context) {
        if (context.persistConversationMessages()) {
            saveUserMessage(context);
        }
        List<MangaAgentMessage> history = conversationService.listMessages(context.conversation());
        List<AgentMessage> messages = conversationService.buildMessages(
                context.chapter(),
                context.user(),
                history,
                context.message(),
                context.requestId()
        );
        return enrichWithKnowledge(context, enrichWithWorkflowSnapshot(context, messages));
    }

    private List<AgentMessage> enrichWithWorkflowSnapshot(MangaWorkflowExecutionContext context,
                                                          List<AgentMessage> messages) {
        if (context.workflowContext() == null) {
            return messages;
        }
        List<AgentMessage> enriched = new ArrayList<>(messages);
        int insertionPoint = !enriched.isEmpty()
                && "user".equalsIgnoreCase(enriched.get(enriched.size() - 1).role())
                ? enriched.size() - 1
                : enriched.size();
        enriched.add(insertionPoint, new AgentMessage("user", workflowSnapshotDataBlock(context.workflowContext())));
        return List.copyOf(enriched);
    }

    private AgentDataBlock workflowSnapshotDataBlock(com.artverse.application.workflow.MangaWorkflowContextSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("story_id", snapshot.storyId());
        data.put("chapter_id", snapshot.chapterId());
        data.put("story_title", blankIfNull(snapshot.storyTitle()));
        data.put("chapter_display_name", blankIfNull(snapshot.chapterDisplayName()));
        data.put("story_style", blankIfNull(snapshot.storyStyle()));
        data.put("scene_count", snapshot.sceneCount());
        data.put("image_count", snapshot.imageCount());
        data.put("source_excerpt", blankIfNull(snapshot.sourceExcerpt()));
        data.put("storyboard_excerpt", blankIfNull(snapshot.storyboardExcerpt()));
        data.put("character_summary", blankIfNull(snapshot.characterSummary()));
        data.put("conversation_summary", blankIfNull(snapshot.conversationSummary()));
        return new AgentDataBlock("chapter_snapshot", "chapter-" + snapshot.chapterId(), Map.of(
                "context_hash", blankIfNull(snapshot.contextHash()),
                "required_fields", snapshot.requiredFields(),
                "warnings", snapshot.warnings(),
                "data", data
        ));
    }

    private List<AgentMessage> enrichWithKnowledge(MangaWorkflowExecutionContext context,
                                                   List<AgentMessage> messages) {
        if (knowledgeService == null || !properties.getAgent().isRagInjectionEnabled()) {
            return messages;
        }
        return knowledgeService.recallForGeneration(
                        context.chapter().getStory().getId(),
                        context.user().getId(),
                        context.chapter().getChapterNumber(),
                        context.message(),
                        context.chapter().getId())
                .map(preview -> {
                    if (runService != null) {
                        runService.recordKnowledgeSnapshot(context.user().getId(), context.chapter().getId(),
                                context.requestId(), preview);
                    }
                    List<AgentMessage> enriched = new ArrayList<>(messages);
                    int insertionPoint = !enriched.isEmpty()
                            && "user".equalsIgnoreCase(enriched.get(enriched.size() - 1).role())
                            ? enriched.size() - 1
                            : enriched.size();
                    enriched.add(insertionPoint, new AgentMessage("user", knowledgeDataBlock(preview)));
                    return List.copyOf(enriched);
                })
                .orElse(messages);
    }

    private AgentDataBlock knowledgeDataBlock(KnowledgeService.RecallPreview preview) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("embedding_space_id", preview.embeddingSpaceId());
        data.put("snapshot_id", preview.snapshotId());
        data.put("items", preview.items().stream().map(item -> Map.of(
                "knowledge_unit_id", item.knowledgeUnitId(),
                "version", item.version(),
                "type", item.type(),
                "title", item.title(),
                "content", item.content(),
                "score", String.format(java.util.Locale.ROOT, "%.4f", item.score())
        )).toList());
        return new AgentDataBlock("knowledge_recall", "knowledge-" + preview.snapshotId(), Map.of(
                "context_hash", preview.contextHash(),
                "required_fields", List.of("knowledge_unit_id", "version", "type", "title", "content", "score"),
                "warnings", List.of(),
                "data", data
        ));
    }

    public void saveUserMessage(MangaWorkflowExecutionContext context) {
        conversationService.saveMessage(
                context.conversation(), MessageRole.USER, context.message(), context.requestId());
    }

    public AgentRunRequest buildRunRequest(MangaWorkflowExecutionContext context, List<AgentMessage> messages) {
        return buildRunRequest(context, messages, AgentTaskType.MANGA_DIRECTOR);
    }

    public AgentRunRequest buildRunRequest(MangaWorkflowExecutionContext context, List<AgentMessage> messages,
                                           AgentTaskType taskType) {
        User user = context.user();
        Chapter chapter = context.chapter();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workflow_config", apiKeyService.activeUserProviderConfigOrBlank(
                user, ApiKeyService.SLOT_WORKFLOW));
        variables.put("step_id", context.stepId() == null || context.stepId().isBlank()
                ? taskType.sessionSuffix()
                : context.stepId());
        if (runService != null) {
            runService.findRun(user.getId(), chapter.getId(), context.requestId())
                    .map(com.artverse.domain.MangaAgentRun::getFencingToken)
                    .ifPresent(token -> variables.put("fencing_token", token));
        }
        return new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapter.getId(),
                taskType,
                messages,
                Map.copyOf(variables),
                context.modelSpec(),
                context.llmApiKey(),
                context.requestId(),
                context.conversation().getConversationUuid()
        );
    }

    public void syncWorkspace(MangaWorkflowExecutionContext context) {
        workspaceSyncService.syncMangaDirectorKnowledge(
                context.chapter().getId(),
                String.valueOf(context.user().getId()),
                context.conversation().getConversationUuid()
        );
    }

    public void throwIfWaitingForUser(MangaWorkflowExecutionContext context) {
        AgentUserInputRequest waiting = context.toolState().userInputRequest();
        if (waiting != null) {
            throw new AgentUserInputRequiredException(waiting);
        }
    }

    public void saveReply(MangaWorkflowExecutionContext context, String reply) {
        if (!context.persistConversationMessages()) {
            return;
        }
        conversationService.saveMessage(
                context.conversation(),
                MessageRole.ASSISTANT,
                reply,
                context.requestId()
        );
    }

    public MangaWorkflowResult fallbackAfterToolSuccess(MangaWorkflowExecutionContext context, String error) {
        Map<String, Object> payload = conversationService.fallbackAfterToolSuccess(
                context.conversation(),
                context.requestId(),
                context.toolState(),
                error,
                // The fallback is only a candidate. ResultFinalizer persists it
                // after it proves the committed artifact from database facts.
                false
        );
        return MangaWorkflowResult.fromPayload(payload);
    }

    public MangaWorkflowResult verifyToolContract(MangaWorkflowExecutionContext context,
                                                  MangaWorkflowRoute route,
                                                  MangaWorkflowResult result) {
        if (toolContractVerifier == null) {
            return result;
        }
        return toolContractVerifier.verify(context, route, result);
    }

    public MangaWorkflowResult executeRequest(MangaWorkflowExecutionContext context,
                                               AgentRunRequest request,
                                               boolean supportsDegradedFallback) {
        if (request.taskType() == AgentTaskType.MANGA_REVIEW) {
            return executeObservedReviewRequest(context, request);
        }
        String reply;
        try {
            reply = harnessAgentGateway.generateText(request).block();
            throwIfWaitingForUser(context);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (BusinessException e) {
            return handleExecutionFailure(context, e.getMessage(), supportsDegradedFallback, e);
        } catch (Exception e) {
            String error = errorMessage(e);
            return handleExecutionFailure(
                    context,
                    error,
                    supportsDegradedFallback,
                    new BusinessException(502, "Agent service failed: " + error)
            );
        }
        return completeRequest(context, reply, supportsDegradedFallback);
    }

    private MangaWorkflowResult executeObservedReviewRequest(MangaWorkflowExecutionContext context,
                                                              AgentRunRequest request) {
        StringBuilder reply = new StringBuilder();
        AtomicReference<String> finalReply = new AtomicReference<>();
        MangaReviewSubagentTracker tracker = new MangaReviewSubagentTracker();
        try {
            harnessAgentGateway.streamEvents(request)
                    .doOnNext(event -> {
                        tracker.observe(event);
                        if (event instanceof TextBlockDeltaEvent text && !tracker.isSubagentEvent(event)
                                && text.getDelta() != null) {
                            reply.append(text.getDelta());
                        }
                        if (event instanceof AgentResultEvent result && !tracker.isSubagentEvent(event)) {
                            finalReply.set(result.getResult().getTextContent());
                        }
                    })
                    .timeout(
                            Mono.delay(Duration.ofSeconds(Math.max(1,
                                    properties.getAgent().getFirstEventTimeoutSeconds()))),
                            mapped -> Mono.delay(agentIdleTimeout(mapped))
                    )
                    .blockLast();
            throwIfWaitingForUser(context);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (BusinessException e) {
            return handleExecutionFailure(context, e.getMessage(), false, e);
        } catch (Exception e) {
            String error = errorMessage(e);
            return handleExecutionFailure(context, error, false,
                    new BusinessException(502, "Agent service failed: " + error));
        }
        MangaWorkflowResult result = completeRequest(context,
                firstNonBlank(finalReply.get(), reply.toString()), false);
        return applyReviewAudit(result, tracker.finish(reviewMetrics), null);
    }

    public void saveFailureMessage(MangaWorkflowExecutionContext context, String error) {
        if (context.persistConversationMessages()) {
            conversationService.saveFailureMessage(context.conversation(), error, context.requestId());
        }
    }

    public Optional<AgentRunEvent> mapAgentScopeEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent start) {
            Map<String, Object> agentData = new java.util.LinkedHashMap<>();
            agentData.put("agent", start.getName() == null ? "unknown" : start.getName());
            if (start.getRole() != null) {
                agentData.put("role", start.getRole());
            }
            return Optional.of(new AgentRunEvent(
                    "run_started", "started", "agent started",
                    null, "running", null,
                    eventData(event, agentData),
                    OffsetDateTime.now()
            ));
        }
        if (event instanceof ModelCallStartEvent) {
            return Optional.of(AgentRunEvent.of("model_started", "thinking", "model is analyzing the chapter"));
        }
        if (event instanceof ModelCallEndEvent) {
            return Optional.of(AgentRunEvent.of("model_finished", "thinking", "model analysis complete"));
        }
        if (event instanceof ThinkingBlockStartEvent) {
            return Optional.of(AgentRunEvent.of("thinking_started", "thinking", "agent is reasoning"));
        }
        if (event instanceof ThinkingBlockDeltaEvent) {
            return Optional.empty();
        }
        if (event instanceof RequireExternalExecutionEvent ext) {
            return Optional.of(new AgentRunEvent(
                    "external_exec_required", "waiting_input", "waiting for user confirmation",
                    null, "waiting", null,
                    Map.of("toolCalls", ext.getToolCalls()),
                    OffsetDateTime.now()
            ));
        }
        if (event instanceof ToolCallStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_started",
                    labelForTool(tool.getToolCallName(), "preparing"),
                    tool.getToolCallName(),
                    "running",
                    eventData(event, Map.of("toolCallId", tool.getToolCallId()))
            ));
        }
        if (event instanceof ToolCallEndEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_ready",
                    labelForTool(tool.getToolCallName(), "tool args ready"),
                    tool.getToolCallName(),
                    "running",
                    eventData(event, Map.of("toolCallId", tool.getToolCallId()))
            ));
        }
        if (event instanceof ToolResultStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_started",
                    labelForTool(tool.getToolCallName(), "running"),
                    tool.getToolCallName(),
                    "running",
                    eventData(event, Map.of("toolCallId", tool.getToolCallId()))
            ));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String status = tool.getState() == null ? "finished" : tool.getState().name().toLowerCase();
            return Optional.of(AgentRunEvent.tool(
                    "tool_finished",
                    labelForTool(tool.getToolCallName(), "tool finished"),
                    tool.getToolCallName(),
                    status,
                    eventData(event, Map.of("toolCallId", tool.getToolCallId()))
            ));
        }
        if (event instanceof TextBlockDeltaEvent text) {
            String delta = text.getDelta();
            return delta == null || delta.isBlank() ? Optional.empty() : Optional.of(AgentRunEvent.text(delta));
        }
        if (event instanceof AgentResultEvent) {
            return Optional.of(AgentRunEvent.of("reply_ready", "replying", "final reply generated"));
        }
        if (event instanceof AgentEndEvent) {
            return Optional.of(new AgentRunEvent(
                    "run_finished", "finished", "agent finished",
                    null, "finished", null, eventData(event, Map.of()), OffsetDateTime.now()));
        }
        return Optional.empty();
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private String labelForTool(String toolName, String prefix) {
        return prefix + ": " + switch (toolName == null ? "" : toolName) {
            case "get_chapter_context" -> "read chapter context";
            case "generate_storyboard" -> "generate storyboard";
            case "save_storyboard" -> "save storyboard";
            case "save_structured_storyboard" -> "save structured storyboard";
            case "draft_structured_storyboard" -> "validate storyboard draft";
            case "commit_storyboard" -> "commit storyboard";
            case "ask_user" -> "ask user";
            default -> toolName == null || toolName.isBlank() ? "tool" : toolName;
        };
    }

    /**
     * Shared streaming execution for all agent node handlers.
     *
     * @param supportsDegradedFallback if true, returns a degraded result when
     *        mutating tools succeeded but the final response failed (Storyboard only)
     */
    public MangaWorkflowResult executeStreamedRequest(MangaWorkflowExecutionContext context,
                                                       MangaWorkflowStreamContext streamContext,
                                                       AgentRunRequest request,
                                                       boolean supportsDegradedFallback) {
        StringBuilder reply = new StringBuilder();
        AtomicReference<String> resultReply = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        MangaReviewSubagentTracker reviewTracker = request.taskType() == AgentTaskType.MANGA_REVIEW
                ? new MangaReviewSubagentTracker()
                : null;
        try {
            harnessAgentGateway.streamEvents(request)
                    .doOnNext(event -> {
                        if (reviewTracker != null) {
                            reviewTracker.observe(event);
                        }
                        if (context.toolState().isCancelled()) {
                            throw new AgentRunTerminatedException(context.requestId(),
                                    context.user().getId(), context.chapter().getId());
                        }
                        if (event instanceof AgentResultEvent result
                                && (reviewTracker == null || !reviewTracker.isSubagentEvent(event))) {
                            resultReply.set(result.getResult().getTextContent());
                        }
                        streamContext.sink().recordProgress(streamContext.run(), phaseFor(event));
                        mapAgentScopeEvent(event).ifPresent(mapped -> {
                            if (reviewTracker != null && event instanceof TextBlockDeltaEvent
                                    && reviewTracker.isSubagentEvent(event)) {
                                return;
                            }
                            if ("text_delta".equals(mapped.type()) && mapped.text() != null) {
                                reply.append(mapped.text());
                            }
                            streamContext.sendRunEvent(mapped);
                        });
                    })
                    .timeout(
                            Mono.delay(Duration.ofSeconds(Math.max(1,
                                    properties.getAgent().getFirstEventTimeoutSeconds()))),
                            mapped -> Mono.delay(agentIdleTimeout(mapped))
                    )
                    .blockLast();
            finished.set(true);
            throwIfWaitingForUser(context);
        } catch (AgentRunTerminatedException e) {
            log.debug("Agent run terminated by concurrent cancel: requestId={} userId={} chapterId={}",
                    e.requestId(), e.userId(), e.chapterId());
            return MangaWorkflowResult.success("");
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (BusinessException e) {
            return handleExecutionFailure(context, e.getMessage(), supportsDegradedFallback, e);
        } catch (Exception e) {
            if (context.toolState().isCancelled()) {
                return MangaWorkflowResult.success("");
            }
            String error = errorMessage(e);
            return handleExecutionFailure(
                    context,
                    error,
                    supportsDegradedFallback,
                    new BusinessException(502, "Agent service failed: " + error)
            );
        }

        String finalReply = firstNonBlank(resultReply.get(), reply.toString());
        if (context.toolState().isCancelled()) {
            return MangaWorkflowResult.success("");
        }
        if (!finished.get()) {
            return handleExecutionFailure(
                    context,
                    "Agent stream did not finish",
                    supportsDegradedFallback,
                    new BusinessException(502, "Agent stream did not finish")
            );
        }
        MangaWorkflowResult result = completeRequest(context, finalReply, supportsDegradedFallback);
        if (reviewTracker != null) {
            return applyReviewAudit(result, reviewTracker.finish(reviewMetrics), streamContext);
        }
        return result;
    }

    private MangaWorkflowResult applyReviewAudit(MangaWorkflowResult result,
                                                 MangaReviewSubagentTracker.Audit audit,
                                                 MangaWorkflowStreamContext streamContext) {
        Map<String, Object> attributes = audit.attributes();
        if (streamContext != null) {
            streamContext.sendRunEvent(new AgentRunEvent(
                    "review_subagents_summary",
                    audit.complete() ? "completed" : "degraded",
                    audit.complete() ? "all review subagents completed" : "review subagents incomplete",
                    null,
                    audit.complete() ? "completed" : "degraded",
                    null,
                    attributes,
                    OffsetDateTime.now()));
        }
        return audit.complete()
                ? result.withAttributes(attributes)
                : result.degradedWithAttributes(attributes);
    }

    private Map<String, Object> eventData(AgentEvent event, Map<String, Object> values) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>(values);
        if (event.getSource() != null && !event.getSource().isBlank()) {
            data.put("source", event.getSource());
        }
        return Map.copyOf(data);
    }

    private MangaWorkflowResult completeRequest(MangaWorkflowExecutionContext context,
                                                String reply,
                                                boolean supportsDegradedFallback) {
        String finalReply = reply == null ? "" : reply;
        if (finalReply.isBlank()) {
            return handleExecutionFailure(
                    context,
                    "Agent returned empty response",
                    supportsDegradedFallback,
                    new BusinessException(502, "Agent returned empty response")
            );
        }
        return MangaWorkflowResult.success(finalReply);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank()
                ? preferred.trim()
                : fallback == null ? "" : fallback.trim();
    }

    private MangaWorkflowResult handleExecutionFailure(MangaWorkflowExecutionContext context,
                                                       String error,
                                                       boolean supportsDegradedFallback,
                                                       BusinessException failure) {
        if (supportsDegradedFallback && context.toolState().hasSuccessfulMutatingTool()) {
            return fallbackAfterToolSuccess(context, error);
        }
        saveFailureMessage(context, error);
        throw failure;
    }

    private String errorMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private Duration agentIdleTimeout(io.agentscope.core.event.AgentEvent event) {
        return Duration.ofSeconds("TOOL".equals(phaseFor(event))
                ? Math.max(1, properties.getAgent().getToolIdleTimeoutSeconds())
                : Math.max(1, properties.getAgent().getModelIdleTimeoutSeconds()));
    }

    private String phaseFor(io.agentscope.core.event.AgentEvent event) {
        return isToolEvent(event) ? "TOOL" : "MODEL";
    }

    private static boolean isToolEvent(io.agentscope.core.event.AgentEvent event) {
        return event instanceof ToolCallStartEvent
                || event instanceof ToolCallEndEvent
                || event instanceof ToolResultStartEvent
                || event instanceof ToolResultEndEvent;
    }
}
