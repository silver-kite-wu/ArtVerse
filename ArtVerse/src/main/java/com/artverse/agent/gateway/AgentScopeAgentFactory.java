package com.artverse.agent.gateway;

import com.artverse.agent.AgentModelSpec;
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
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeAgentFactory {

    public void closeAgent(HarnessAgent agent) {
        ReviewExpertSupport.Scope scope = reviewScopes.remove(agent);
        try { if (scope != null) scope.close(); }
        finally { agent.close(); }
    }

    private final Map<HarnessAgent, ReviewExpertSupport.Scope> reviewScopes = new ConcurrentHashMap<>();
    private ReviewExpertSupport reviewExpertSupport;

    @org.springframework.beans.factory.annotation.Autowired
    public void setReviewExpertSupport(ReviewExpertSupport support) {
        this.reviewExpertSupport = java.util.Objects.requireNonNull(support);
    }

    private final CompactionConfig compactionConfig;
    private final ArtVerseProperties properties;
    private final AgentWorkspaceService agentWorkspaceService;
    private final MangaAgentPromptProvider promptProvider;
    private final AgentStateStore agentStateStore;
    private final AgentTurnStateStores turnStateStores;
    private final AgentToolConfigurationRegistry toolConfigurationRegistry;
    private final ArtVerseSkillRegistry skillRegistry;
    private final ArtVerseSkillRepository skillRepository;
    private final MangaAgentRunService runService;
    private final PostgresAgentWorkspaceStore workspaceStore;
    private final AgentToolBoundaryMiddleware toolBoundary;
    private final AgentScopeSystemPromptMiddleware systemPromptMiddleware = new AgentScopeSystemPromptMiddleware();

    public HarnessAgent getOrCreate(AgentRunRequest request, AgentSessionTurns.Lease lease) {
        if (request.modelSpec() == null) {
            throw new BusinessException(400,
                    "LLM provider configuration is missing. Please configure it in Settings.");
        }
        var declared = request.modelSpec().capabilities();
        if ((request.taskType() == AgentTaskType.MANGA_ROUTER || request.taskType() == AgentTaskType.KNOWLEDGE_EXTRACTION)
                && !declared.nativeStructuredOutput() && !declared.toolCalling())
            throw new BusinessException(400, "当前模型尚未声明工具或结构化输出能力，请配置服务端 model-capabilities");
        AgentStateStore scopedState = turnStateStores.forTurn(request, lease);
        HarnessAgent created = null;
        try {
            Path requestWorkspace = agentWorkspaceService.workspaceFor(request);
            BusinessSkillSelection selection = skillRegistry.resolveSelection(request);
            String promptVersion = promptProvider.promptVersionFor(request.taskType());
            if (request.requestId() != null && request.chapterId() != null
                    && request.taskType() != null && request.taskType().isMangaExecutionTask()) {
                runService.recordSkillSelection(Long.valueOf(request.userId()), request.chapterId(), request.requestId(),
                        selection, promptVersion);
                runService.recordStepSkillSelection(Long.valueOf(request.userId()), request.chapterId(),
                        request.requestId(), String.valueOf(request.variables().getOrDefault("step_id", "")), selection);
            }
            created = buildAgent(request, requestWorkspace, selection,
                    resolveModel(request.llmApiKey(), request.modelSpec()), scopedState);
            if (request.taskType().isMangaExecutionTask()) runService.bindModelCapabilities(request);
            return created;
        } catch (RuntimeException error) {
            try { if (created != null) closeAgent(created); }
            finally { scopedState.close(); }
            throw error;
        }
    }

    private HarnessAgent buildAgent(AgentRunRequest request, Path requestWorkspace,
                                    BusinessSkillSelection selection) {
        return buildAgent(request, requestWorkspace, selection, resolveModel(request.llmApiKey(), request.modelSpec()));
    }

    // The same assembly is exercised with a deterministic native Model in integration tests.
    HarnessAgent buildAgent(AgentRunRequest request, Path requestWorkspace,
                            BusinessSkillSelection selection, Model effectiveModel) {
        return buildAgent(request, requestWorkspace, selection, effectiveModel, agentStateStore);
    }

    HarnessAgent buildAgent(AgentRunRequest request, Path requestWorkspace,
                            BusinessSkillSelection selection, Model effectiveModel, AgentStateStore stateStore) {
        var identity = request.variables().get("review_expert") instanceof com.artverse.agent.AgentReviewExpert expert ? expert : null;
        if (request.variables().containsKey("review_expert") && identity == null)
            throw new BusinessException(403, "审查专家执行身份无效");
        if (identity != null && (request.taskType() != AgentTaskType.MANGA_REVIEW || !selection.isEmpty()
                || !java.util.Objects.equals(identity.parentRequestId(), request.requestId())
                || !java.util.Objects.equals(identity.parentStepId(), ReviewExpertBindings.step(request))
                || !(request.variables().get("review_expert_session") instanceof String session) || session.isBlank()))
            throw new BusinessException(403, "审查专家不能扩大父运行权限");
        boolean parentReview = request.taskType() == AgentTaskType.MANGA_REVIEW && identity == null
                && !Boolean.TRUE.equals(request.variables().get("review_completed"));
        ReviewExpertSupport.Scope scope = null;
        if (parentReview) {
            if (reviewExpertSupport == null) throw new IllegalStateException("Native review experts are not configured");
            scope = reviewExpertSupport.open(request, requestWorkspace,
                    (child, childState) -> buildAgent(child, requestWorkspace, BusinessSkillSelection.empty(), effectiveModel, childState));
        }
        try {
            HarnessAgent agent = assembleAgent(request, requestWorkspace, selection, effectiveModel, stateStore, identity, scope);
            if (scope != null) reviewScopes.put(agent, scope);
            return agent;
        } catch (RuntimeException | Error error) {
            if (scope != null) scope.close();
            throw error;
        }
    }

    private HarnessAgent assembleAgent(AgentRunRequest request, Path requestWorkspace,
                            BusinessSkillSelection selection, Model effectiveModel, AgentStateStore stateStore,
                            com.artverse.agent.AgentReviewExpert identity, ReviewExpertSupport.Scope expertScope) {
        if (compactionConfig.getModel() != null)
            throw new IllegalStateException("Compaction must use the declared budgeted run model");
        var budgetRequest = java.util.Objects.equals(request.businessSkillSelection(), selection)
                || (request.businessSkillSelection() == null && selection.isEmpty()) ? request : new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                request.messages(), request.variables(), request.modelSpec(), request.llmApiKey(), request.requestId(),
                request.conversationId(), selection);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                // Per-run toolkit and permissions; durable conversation state remains in AgentStateStore.
                .name("artverse-" + request.taskType().sessionSuffix())
                .sysPrompt(identity == null ? Boolean.TRUE.equals(request.variables().get("review_completed"))
                        ? promptProvider.reviewSummaryPrompt() : promptProvider.promptFor(request.taskType())
                        : com.artverse.agent.MangaReviewSubagentDeclarations.all().stream()
                        .filter(declaration -> identity.expertId().equals(declaration.getName())).findFirst()
                        .orElseThrow(() -> new BusinessException(403, "未知审查专家")).getInlineAgentsBody())
                .model(toolBoundary.budgetedModel(effectiveModel, budgetRequest, properties))
                .workspace(requestWorkspace)
                .abstractFilesystem(new RemoteFilesystem(
                        workspaceStore, AgentWorkspaceService::namespaceFor))
                .compaction(compactionConfig)
                .stateStore(stateStore)
                .maxIters(identity == null ? maxIters(request.taskType()) : 3)
                .maxContextTokens(properties.getAgent().getMaxInputTokens())
                .enablePendingToolRecovery(false)
                .middleware(systemPromptMiddleware)
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableToolsConfig()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableAtPathExpansion()
                .disableWorkspaceContext()
                .modelExecutionConfig(ExecutionConfig.builder().maxAttempts(1)
                        .timeout(Duration.ofSeconds(properties.getAgent().getModelIdleTimeoutSeconds())).build())
                .toolExecutionConfig(ExecutionConfig.builder().maxAttempts(1)
                        .timeout(Duration.ofSeconds(properties.getAgent().getToolIdleTimeoutSeconds())).build());
        if (request.variables().get("answer_delivery") instanceof com.artverse.agent.AgentAnswerDelivery) {
            // Keep the exact external result until native final/failure persistence is verified.
            // A mid-call saveAgentState would leave the native CallExecution.loadedVersion stale.
            builder.disableCompaction().disableToolResultEviction();
        }
        if (!selection.isEmpty()) {
            builder.skillRepository(skillRepository)
                    .disableDynamicSkills()
                    .disableDefaultWorkspaceSkills()
                    .enableSkills(selection.skillKeys().toArray(String[]::new));
        }
        if (expertScope != null && request.taskType().subagentDeclarations().size() > properties.getAgent().getMaxSubagents()) {
            throw new IllegalStateException("Task declares more subagents than the configured hard limit");
        }
        // AgentScope captures initial active groups during build; later registration
        // would leave fresh run state with no business tools visible to the model.
        Toolkit toolkit = new Toolkit();
        toolConfigurationRegistry.configure(toolkit, request);
        if (identity != null && (!toolkit.getToolNames().equals(Set.of("get_chapter_context"))
                || !toolkit.getTool("get_chapter_context").isReadOnly()))
            throw new IllegalStateException("Review experts require exactly the read-only chapter context tool");
        if (expertScope != null) {
            expertScope.install(toolkit);
            builder.middleware(expertScope.middleware());
        }
        if ((!toolkit.getToolNames().isEmpty() || !selection.isEmpty()) && request.modelSpec() != null
                && !request.modelSpec().capabilities().toolCalling())
            throw new BusinessException(400, "当前模型尚未声明工具调用能力，请配置服务端 model-capabilities");
        // These curated tools enforce authenticated scope and artifact confirmation in
        // ArtVerse services. A second framework permission prompt has no product UI.
        var permissions = PermissionContextState.builder().mode(PermissionMode.DONT_ASK);
        for (var schema : toolkit.getToolSchemas()) {
            permissions.addAllowRule(schema.getName(), new PermissionRule(
                    schema.getName(), null, PermissionBehavior.ALLOW, "artverse-business-tools"));
        }
        if (!selection.isEmpty()) {
            permissions.addAllowRule("load_skill_through_path", new PermissionRule(
                    "load_skill_through_path", null, PermissionBehavior.ALLOW, "artverse-selected-skills"));
        }
        var boundary = toolBoundary.forRun(request, toolkit, selection);
        builder.middleware(boundary);
        var currentPermissions = permissions.build();
        HarnessAgent agent = builder.toolkit(toolkit).permissionContext(currentPermissions).build();
        try {
            // Harness copies Toolkit and adds a wait tool even with subagents disabled.
            // Remove this explicitly excluded built-in, then inspect the actual final Toolkit.
            agent.getToolkit().removeTool("wait_async_results");
            if (expertScope != null) agent.getToolkit().registration()
                    .agentTool(toolkit.getTool("wait_async_results")).group("review-experts").apply();
            boundary.seal(agent.getToolkit(), !selection.isEmpty());
            // State restoration includes old groups and permission rules. Replace them for this
            // exact slot while retaining messages and the original pending external call.
            String sessionId = identity == null ? AgentScopeRuntimeContextFactory.createSessionId(request)
                    : (String) request.variables().get("review_expert_session");
            var delegate = agent.getDelegate();
            delegate.getAgentState(request.userId(), sessionId).getToolContext()
                    .setActivatedGroups(agent.getToolkit().getActiveGroups());
            delegate.replacePermissionContext(request.userId(), sessionId, currentPermissions);
            return agent;
        } catch (RuntimeException error) {
            agent.close();
            throw error;
        }
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
                .nativeStructuredOutput(modelSpec.capabilities().nativeStructuredOutput())
                .nativeStructuredOutputWithTools(modelSpec.capabilities().nativeStructuredOutputWithTools())
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
            case READER_CHARACTER_CHAT -> agent.getConversationMaxModelCalls();
            case MANGA_STORYBOARD -> agent.getStoryboardMaxModelCalls();
            case MANGA_REVIEW -> agent.getReviewMaxModelCalls();
            case MANGA_DIRECTOR -> agent.getDirectorMaxModelCalls();
            case KNOWLEDGE_EXTRACTION -> 2;
            default -> 4;
        };
    }

}
