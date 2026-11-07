package com.artverse.application;

import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentAnswerDelivery;
import com.artverse.agent.AgentTaskType;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.common.BusinessException;
import com.artverse.domain.AgentRunType;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunEventRecord;
import com.artverse.domain.MangaAgentRunStep;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.User;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaRouteSource;
import com.artverse.application.workflow.RoutingDecision;
import com.artverse.application.workflow.ExecutionPlan;
import com.artverse.persistence.MangaAgentRunEventRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.artverse.persistence.MangaAgentRunStepRepository;
import com.artverse.persistence.MangaAgentRunArtifactRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.artverse.application.MangaAgentRunSnapshots.*;

@Slf4j
@Service
public class MangaAgentRunService {

    private static final List<MangaAgentRunStatus> OPEN_STATUSES = List.of(
            MangaAgentRunStatus.RUNNING,
            MangaAgentRunStatus.WAITING_USER
    );

    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final MangaAgentRunStepRepository stepRepository;
    private final MangaAgentRunArtifactRepository artifactRepository;
    private final AgentRunLeaseService leaseService;
    private final MangaAgentRunSnapshotReader snapshotReader;
    private org.springframework.jdbc.core.JdbcTemplate automationClaimJdbc;

    @Autowired
    public void setAutomationClaimJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.automationClaimJdbc = Objects.requireNonNull(jdbc);
    }

    /** Bind the original parent lease before any child model/tool dispatch, including initial attachment. */
    @Transactional
    public void bindAutomationExecutionClaim(MangaAgentConversation conversation, UUID requestId,
            MangaWorkflowExecutionContext.ExecutionClaim childClaim,
            com.artverse.agent.AutomationExecutionClaim parentClaim) {
        if (automationClaimJdbc == null) throw new IllegalStateException("Automation claim persistence is unavailable");
        var parents = automationClaimJdbc.queryForList("""
                SELECT a.id,a.lease_owner,a.fencing_token,e.id AS execution_id,e.execution_kind,e.agent_run_id,
                    a.state='RUNNING' AND a.lease_owner IS NOT NULL AND a.lease_until>clock_timestamp() AS active
                FROM automation_run a JOIN automation_run_execution e ON e.run_id=a.id
                WHERE a.user_id=? AND e.request_id=? AND e.conversation_uuid=?
                ORDER BY a.id FOR UPDATE OF a
                """, conversation.getUser().getId(), requestId, conversation.getConversationUuid());
        if (parents.isEmpty()) {
            if (parentClaim != null) throw new BusinessException(403, "后台原执行关联不存在");
            return;
        }
        if (parents.size() != 1 || parentClaim == null || !parentClaim.valid())
            throw new BusinessException(409, "后台原执行身份缺失或关联不唯一");
        var parent = parents.getFirst();
        if (!parentClaim.runId().equals(parent.get("id")) || !parentClaim.owner().equals(parent.get("lease_owner"))
                || !Objects.equals(parentClaim.fencingToken(), parent.get("fencing_token"))
                || !Boolean.TRUE.equals(parent.get("active")))
            throw new BusinessException(409, "后台原执行身份已变化");
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        requireClaim(run, childClaim);
        if (!Objects.equals(run.getUser().getId(), conversation.getUser().getId())
                || !Objects.equals(run.getStory().getId(), conversation.getStory().getId())
                || !Objects.equals(run.getChapter().getId(), conversation.getChapter().getId())
                || !run.getRunType().name().equals(parent.get("execution_kind"))
                || (parent.get("agent_run_id") != null && !Objects.equals(run.getId(), parent.get("agent_run_id"))))
            throw new BusinessException(403, "后台子运行范围不匹配");
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var claims = objectMap(attributes.get("automation_execution_claims"));
        String key = automationClaimKey(childClaim);
        var expected = new LinkedHashMap<>(parentClaim.variables());
        expected.put("child_owner", childClaim.owner());
        expected.put("child_fencing_token", childClaim.fencingToken());
        var canonical = readMap(toJson(expected));
        if (claims.containsKey(key) && !canonical.equals(claims.get(key)))
            throw new BusinessException(409, "本次子执行不能替换原后台身份");
        claims.put(key, canonical);
        attributes.put("automation_execution_claims", claims);
        run.setRunAttributesJson(toJson(attributes));
        runRepository.saveAndFlush(run);
        int attached = automationClaimJdbc.update("""
                UPDATE automation_run_execution SET agent_run_id=? WHERE id=?
                    AND (agent_run_id IS NULL OR agent_run_id=?)
                """, run.getId(), parent.get("execution_id"), run.getId());
        if (attached != 1) throw new BusinessException(409, "后台子运行关联已变化");
    }

    /** Look up only the immutable mapping of the captured child claim; never adopt a newer parent lease. */
    @Transactional(readOnly = true)
    public Map<String, Object> automationExecutionVariables(MangaAgentConversation conversation, UUID requestId,
            MangaWorkflowExecutionContext.ExecutionClaim childClaim) {
        MangaAgentRun run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        requireClaim(run, childClaim);
        var claims = objectMap(readMap(run.getRunAttributesJson()).get("automation_execution_claims"));
        var original = objectMap(claims.get(automationClaimKey(childClaim)));
        if (original.isEmpty()) return Map.of();
        if (!Objects.equals(childClaim.owner(), original.get("child_owner"))
                || !(original.get("child_fencing_token") instanceof Number f) || f.longValue() != childClaim.fencingToken()
                || !(original.get("automation_run_id") instanceof String parentId)
                || !(original.get("automation_execution_owner") instanceof String owner)
                || !(original.get("automation_fencing_token") instanceof Number fence))
            throw new BusinessException(409, "后台原执行凭证不完整");
        var claim = new com.artverse.agent.AutomationExecutionClaim(UUID.fromString(parentId), owner, fence.longValue());
        if (!claim.valid()) throw new BusinessException(409, "后台原执行凭证无效");
        return claim.variables();
    }

    private static String automationClaimKey(MangaWorkflowExecutionContext.ExecutionClaim claim) {
        if (claim == null || !claim.valid()) throw new BusinessException(409, "原子执行身份缺失");
        return ToolIdempotencyService.sha256(claim.owner() + ":" + claim.fencingToken());
    }

    /** Call at the start of a domain write transaction, before taking its child/root lock. */
    @Transactional
    public void lockAutomationExecution(com.artverse.agent.MangaAgentRuntimeContext context) {
        if (context == null) throw new BusinessException(403, "SCOPE_DENIED");
        lockAutomationExecution(context.userId(), context.storyId(), context.chapterId(), context.conversationId(),
                context.requestId(), new MangaWorkflowExecutionContext.ExecutionClaim(context.executionOwner(), context.fencingToken()),
                context.automationClaim(), true);
    }

    /** Terminal callbacks carry the original child claim and recover only its immutable parent mapping. */
    @Transactional
    public void lockAutomationExecution(MangaAgentConversation conversation, UUID requestId,
            MangaWorkflowExecutionContext.ExecutionClaim childClaim) {
        lockAutomationExecution(conversation.getUser().getId(), conversation.getStory().getId(), conversation.getChapter().getId(),
                conversation.getConversationUuid(), requestId, childClaim, null, false);
    }

    /** A real author decision may arrive while the parent is waiting; it does not borrow a model lease. */
    @Transactional
    public void lockAutomationAuthorDecision(Long actor, Long chapter, UUID requestId) {
        lockAutomationReview(actor, chapter, requestId, true);
    }

    @Transactional
    public void lockAutomationReviewReceipt(Long actor, Long chapter, UUID requestId) {
        lockAutomationReview(actor, chapter, requestId, false);
    }

    private void lockAutomationReview(Long actor, Long chapter, UUID requestId, boolean authorDecision) {
        if (automationClaimJdbc == null) throw new IllegalStateException("Automation claim persistence is unavailable");
        var parents = automationClaimJdbc.queryForList("""
                SELECT a.id,a.state FROM automation_run a JOIN automation_run_execution e ON e.run_id=a.id
                JOIN manga_agent_runs r ON r.id=e.agent_run_id AND r.user_id=a.user_id
                    AND r.request_id=e.request_id AND r.run_type=e.execution_kind
                JOIN ai_conversations c ON c.id=r.conversation_id AND c.user_id=r.user_id
                    AND c.story_id=r.story_id AND c.chapter_id=r.chapter_id AND c.conversation_uuid=e.conversation_uuid
                WHERE r.user_id=? AND r.chapter_id=? AND r.request_id=? ORDER BY a.id FOR UPDATE OF a
                """, actor, chapter, requestId);
        if (parents.size() > 1) throw new BusinessException(409, "后台原执行关联不唯一");
        if (authorDecision && !parents.isEmpty() && !Set.of("WAITING_USER", "RUNNING").contains(parents.getFirst().get("state")))
            throw new BusinessException(409, "后台运行已停止，不能继续授权原动作");
        if (parents.isEmpty() && Boolean.TRUE.equals(automationClaimJdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM automation_run_execution e JOIN manga_agent_runs r ON r.id=e.agent_run_id
                    WHERE r.user_id=? AND r.chapter_id=? AND r.request_id=?)
                """, Boolean.class, actor, chapter, requestId)))
            throw new BusinessException(403, "后台原执行关联不完整");
    }

    private void lockAutomationExecution(Long actor, Long story, Long chapter, UUID conversation, UUID requestId,
            MangaWorkflowExecutionContext.ExecutionClaim childClaim,
            com.artverse.agent.AutomationExecutionClaim suppliedParent, boolean requireSupplied) {
        if (automationClaimJdbc == null) throw new IllegalStateException("Automation claim persistence is unavailable");
        String key = automationClaimKey(childClaim);
        var children = automationClaimJdbc.queryForList("""
                SELECT r.id,r.run_attributes_json::text AS attributes FROM manga_agent_runs r
                JOIN ai_conversations c ON c.id=r.conversation_id AND c.user_id=r.user_id
                    AND c.story_id=r.story_id AND c.chapter_id=r.chapter_id
                WHERE r.user_id=? AND r.story_id=? AND r.chapter_id=? AND c.conversation_uuid=? AND r.request_id=?
                """, actor, story, chapter, conversation, requestId);
        if (children.size() != 1) throw new BusinessException(403, "后台子运行范围不匹配");
        var original = objectMap(objectMap(readMap(Objects.toString(children.getFirst().get("attributes"), "{}"))
                .get("automation_execution_claims")).get(key));
        var parents = automationClaimJdbc.queryForList("""
                SELECT a.id,a.lease_owner,a.fencing_token,e.agent_run_id FROM automation_run a
                JOIN automation_run_execution e ON e.run_id=a.id
                WHERE a.user_id=? AND e.request_id=? AND e.conversation_uuid=?
                ORDER BY a.id FOR UPDATE OF a
                """, actor, requestId, conversation);
        if (parents.isEmpty()) {
            if (!original.isEmpty() || suppliedParent != null) throw new BusinessException(403, "后台原执行关联不存在");
            return;
        }
        if (parents.size() != 1 || original.isEmpty()) throw new BusinessException(409, "后台原执行凭证缺失或不唯一");
        var parent = parents.getFirst();
        if (!Objects.equals(parent.get("agent_run_id"), children.getFirst().get("id"))
                || !childClaim.owner().equals(original.get("child_owner"))
                || !(original.get("child_fencing_token") instanceof Number cf) || cf.longValue() != childClaim.fencingToken()
                || !parent.get("id").toString().equals(original.get("automation_run_id"))
                || !Objects.equals(parent.get("lease_owner"), original.get("automation_execution_owner"))
                || !(original.get("automation_fencing_token") instanceof Number pf)
                || !Objects.equals(pf.longValue(), parent.get("fencing_token"))
                || (requireSupplied && (suppliedParent == null || !suppliedParent.valid()
                    || !suppliedParent.runId().toString().equals(original.get("automation_run_id"))
                    || !suppliedParent.owner().equals(original.get("automation_execution_owner"))
                    || suppliedParent.fencingToken() != pf.longValue())))
            throw new BusinessException(409, "后台原执行身份已变化");
        Boolean active = automationClaimJdbc.queryForObject("""
                SELECT state='RUNNING' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()
                FROM automation_run WHERE id=? AND user_id=?
                """, Boolean.class, parent.get("lease_owner"), pf.longValue(), parent.get("id"), actor);
        if (!Boolean.TRUE.equals(active)) throw new BusinessException(409, "后台原执行权已停止或过期");
    }

    @Autowired
    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                MangaAgentRunStepRepository stepRepository,
                                MangaAgentRunArtifactRepository artifactRepository,
                                AgentRunLeaseService leaseService,
                                MangaAgentRunSnapshotReader snapshotReader) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.stepRepository = stepRepository;
        this.artifactRepository = artifactRepository;
        this.leaseService = leaseService;
        this.snapshotReader = snapshotReader;
    }

    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                MangaAgentRunStepRepository stepRepository,
                                MangaAgentRunArtifactRepository artifactRepository,
                                AgentRunLeaseService leaseService) {
        this(runRepository, eventRepository, objectMapper, stepRepository, artifactRepository, leaseService,
                new MangaAgentRunSnapshotReader(runRepository, eventRepository, stepRepository, artifactRepository, objectMapper));
    }

    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                MangaAgentRunStepRepository stepRepository,
                                MangaAgentRunArtifactRepository artifactRepository) {
        this(runRepository, eventRepository, objectMapper, stepRepository, artifactRepository, null);
    }

    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                MangaAgentRunStepRepository stepRepository) {
        this(runRepository, eventRepository, objectMapper, stepRepository, null, null);
    }

    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper) {
        this(runRepository, eventRepository, objectMapper, null, null, null);
    }

    @Transactional
    public MangaAgentRun startOrReuse(User user, Chapter chapter, UUID requestId, String inputMessage) {
        MangaAgentRun claimed = runRepository.findForUpdate(user.getId(), chapter.getId(), requestId)
                .map(existing -> {
                    if (existing.getStatus() == MangaAgentRunStatus.RUNNING) {
                        throw new BusinessException(409, "任务正在执行，请查看当前进度。");
                    }
                    if (existing.getStatus() == MangaAgentRunStatus.WAITING_USER) {
                        existing.setStatus(MangaAgentRunStatus.RUNNING);
                        existing.setUserInputRequestJson(null);
                        existing.setErrorMessage(null);
                        existing.setCurrentPhase("MODEL");
                        existing.setLastProgressAt(OffsetDateTime.now());
                        existing.setUpdatedAt(OffsetDateTime.now());
                    }
                    return runRepository.saveAndFlush(existing);
                })
                .orElseGet(() -> {
                    MangaAgentRun run = new MangaAgentRun();
                    run.setUser(user);
                    run.setStory(chapter.getStory());
                    run.setChapter(chapter);
                    run.setRequestId(requestId);
                    run.setInputMessage(inputMessage);
                    run.setStatus(MangaAgentRunStatus.RUNNING);
                    run.setCurrentPhase("MODEL");
                    return runRepository.saveAndFlush(run);
                });
        return claimIfConfigured(claimed);
    }

    @Transactional
    public MangaAgentRun startOrReuse(MangaAgentConversation conversation, UUID requestId, String inputMessage) {
        return startOrReuse(conversation, requestId, inputMessage, MangaWorkflowRoute.DIRECTOR);
    }

    @Transactional
    public MangaAgentRun startOrReuse(MangaAgentConversation conversation, UUID requestId, String inputMessage,
                                      MangaWorkflowRoute route) {
        return startOrReuse(conversation, requestId, inputMessage, AgentRunType.MANGA_AGENT, route,
                route == null ? MangaWorkflowRoute.DIRECTOR.name() : route.name());
    }

    @Transactional
    public MangaAgentRun startOrReuse(MangaAgentConversation conversation, UUID requestId, String inputMessage,
                                      AgentRunType runType, MangaWorkflowRoute route, String routeKey) {
        MangaWorkflowRoute effectiveRoute = route == null ? MangaWorkflowRoute.DIRECTOR : route;
        AgentRunType effectiveRunType = runType == null ? AgentRunType.MANGA_AGENT : runType;
        String effectiveRouteKey = routeKey == null || routeKey.isBlank()
                ? effectiveRoute.name()
                : routeKey.trim().toUpperCase();
        MangaAgentRun claimed = runRepository.findForUpdate(conversation.getId(), requestId)
                .map(existing -> {
                    if (existing.getStatus() == MangaAgentRunStatus.RUNNING) {
                        throw new BusinessException(409, "任务正在执行，请查看当前进度。");
                    }
                    if (existing.getStatus() == MangaAgentRunStatus.WAITING_USER) {
                        if (existing.getRunType() == AgentRunType.STORY_CHAT) {
                            throw new BusinessException(409, "请通过原提问或审阅结果恢复任务。");
                        }
                        existing.setStatus(MangaAgentRunStatus.RUNNING);
                        existing.setUserInputRequestJson(null);
                        existing.setErrorMessage(null);
                        existing.setCurrentPhase("MODEL");
                        existing.setLastProgressAt(OffsetDateTime.now());
                        existing.setUpdatedAt(OffsetDateTime.now());
                    }
                    return runRepository.saveAndFlush(existing);
                })
                .orElseGet(() -> {
                    MangaAgentRun run = new MangaAgentRun();
                    run.setUser(conversation.getUser());
                    run.setStory(conversation.getStory());
                    run.setChapter(conversation.getChapter());
                    run.setConversation(conversation);
                    run.setRequestId(requestId);
                    run.setInputMessage(inputMessage);
                    run.setRunType(effectiveRunType);
                    run.setRoute(effectiveRoute);
                    run.setRouteKey(effectiveRouteKey);
                    run.setStatus(MangaAgentRunStatus.RUNNING);
                    run.setCurrentPhase("MODEL");
                    return runRepository.saveAndFlush(run);
                });
        return claimIfConfigured(claimed);
    }

    private MangaAgentRun claimIfConfigured(MangaAgentRun run) {
        return leaseService == null || run.getStatus() != MangaAgentRunStatus.RUNNING ? run : leaseService.claim(run);
    }

    @Transactional
    public void bindStoryChatExecution(MangaAgentConversation conversation, UUID requestId, Map<String, Object> identity) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        if (run.getRunType() != AgentRunType.STORY_CHAT || run.getStatus() != MangaAgentRunStatus.RUNNING) {
            throw new BusinessException(409, "任务当前不能绑定执行身份");
        }
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        Object previous = attributes.get("story_chat_execution");
        if (previous != null && !previous.equals(identity)) {
            throw new BusinessException(409, "任务执行身份已经固定");
        }
        attributes.put("story_chat_execution", identity);
        run.setRunAttributesJson(toJson(attributes));
        runRepository.save(run);
    }

    @Transactional
    public void bindStoryChatExecution(MangaAgentConversation conversation, UUID requestId, Map<String, Object> identity,
                                       MangaWorkflowExecutionContext.ExecutionClaim claim) {
        lockAutomationExecution(conversation, requestId, claim);
        requireExecutionClaim(conversation, requestId, claim);
        bindStoryChatExecution(conversation, requestId, identity);
    }

    @Transactional
    public ExternalQuestion recordExternalQuestion(com.artverse.agent.MangaAgentRuntimeContext context,
                                                    String callId, AgentUserInputRequest input) {
        if (context == null || context.stepId() == null || context.storyId() == null || callId == null || callId.isBlank() || callId.length() > 256) {
            throw new BusinessException(400, "原生提问关联无效");
        }
        lockAutomationExecution(context);
        MangaAgentRun run = runRepository.findForUpdate(context.userId(), context.chapterId(), context.requestId())
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        if (run.getConversation() == null || !Objects.equals(run.getConversation().getConversationUuid(), context.conversationId())
                || !Objects.equals(run.getStory().getId(), context.storyId())) {
            throw new BusinessException(409, "提问执行权已失效");
        }
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var questions = objectMap(attributes.get("external_questions"));
        String hash = ToolIdempotencyService.sha256(toJson(input));
        if (questions.get(callId) instanceof Map<?, ?> existing) {
            var pending = waitingInput(run);
            if (!hash.equals(existing.get("input_hash")) || !Objects.equals(context.stepId(), existing.get("step_id"))
                    || !"WAITING".equals(existing.get("status")) || pending == null || !callId.equals(pending.toolCallId())) {
                throw new BusinessException(409, "原提问已消费或内容不一致");
            }
            return new ExternalQuestion(pending, false);
        }
        if (run.getStatus() != MangaAgentRunStatus.RUNNING
                || !Objects.equals(run.getFencingToken(), context.fencingToken())
                || !Objects.equals(run.getOwnerInstanceId(), context.executionOwner())
                || (leaseService != null && !leaseService.owns(run, context.fencingToken()))) {
            throw new BusinessException(409, "提问执行权已失效");
        }
        if (questions.size() >= 64 || (waitingInput(run) != null && waitingInput(run).toolCallId() != null)) {
            throw new BusinessException(409, "已有待答问题或提问数量超过限制");
        }
        long version = attributes.get("pending_sequence") instanceof Number n ? n.longValue() + 1 : 1;
        var pending = new AgentUserInputRequest(input.question(), input.options(), input.allowFreeText(), input.reason(),
                "USER_QUESTION", callId, version);
        questions.put(callId, Map.of("input_hash", hash, "step_id", context.stepId(), "pending_version", version,
                "fencing_token", context.fencingToken(), "request", pending, "status", "WAITING",
                "task_type", context.taskType().name(), "profile", "local-tools-v1"));
        attributes.put("external_questions", questions);
        attributes.put("pending_sequence", version);
        run.setRunAttributesJson(toJson(attributes));
        run.setUserInputRequestJson(toJson(pending));
        // The external tool bridge and the durable waiting transition are one operation.
        // Otherwise a later progress/finalization path can observe a saved question before
        // the run itself has entered WAITING_USER and reject a valid author review.
        applyWaiting(run, pending, context.fencingToken());
        runRepository.saveAndFlush(run);
        return new ExternalQuestion(pending, true);
    }

    @Transactional
    public AcceptedAnswer acceptAnswer(MangaAgentConversation conversation, UUID requestId,
                                       String callId, Long version, String answer) {
        return acceptAnswer(conversation, requestId, callId, version, answer, null);
    }

    @Transactional
    public AcceptedAnswer acceptAnswer(MangaAgentConversation conversation, UUID requestId,
                                       String callId, Long version, String answer, String selectedOptionId) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        if (callId == null || callId.isBlank() || version == null || version <= 0) {
            throw new BusinessException(400, "请提供原提问标识、版本及 1 至 4000 字的补充说明");
        }
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var questions = objectMap(attributes.get("external_questions"));
        var question = objectMap(questions.get(callId));
        if (!(question.get("pending_version") instanceof Number n) || n.longValue() != version) {
            throw new BusinessException(409, "原提问版本已变化，请刷新后回复");
        }
        AgentUserInputRequest pending = question.get("request") == null ? waitingInput(run)
                : objectMapper.convertValue(question.get("request"), AgentUserInputRequest.class);
        if (pending == null || !callId.equals(pending.toolCallId()) || !version.equals(pending.pendingVersion())) {
            throw new BusinessException(409, "原提问记录不完整，请刷新任务状态");
        }
        String normalizedAnswer = pending.resolveAnswer(answer, selectedOptionId);
        String hash = ToolIdempotencyService.sha256(selectedOptionId == null ? "TEXT:" + normalizedAnswer : "OPTION:" + selectedOptionId);
        if ("ANSWERED".equals(question.get("status"))) {
            if (!hash.equals(question.get("answer_hash"))) throw new BusinessException(409, "原提问已经收到另一份答复");
            return acceptedAnswer(run, callId, question);
        }
        if (!Set.of(AgentRunType.STORY_CHAT, AgentRunType.MANGA_AGENT).contains(run.getRunType()) || run.getStatus() != MangaAgentRunStatus.WAITING_USER
                || pending == null || !"USER_QUESTION".equals(pending.purpose())
                || !callId.equals(pending.toolCallId()) || !version.equals(pending.pendingVersion())
                || !"WAITING".equals(question.get("status")) || attributes.containsKey("review_result")) {
            throw new BusinessException(409, "该任务没有可恢复的普通提问，请通过审阅页处理草稿。");
        }
        question.put("status", "ANSWERED");
        question.put("answer_hash", hash);
        question.put("answer", normalizedAnswer);
        question.put("request", pending);
        var output = new LinkedHashMap<String, Object>();
        output.put("status", "ANSWERED");
        output.put("answer", normalizedAnswer);
        if (selectedOptionId != null) output.put("selected_option_id", selectedOptionId);
        question.put("canonical_result", toJson(output));
        question.put("action_id", UUID.randomUUID().toString());
        question.put("delivery_status", "ACCEPTED");
        questions.put(callId, question);
        attributes.put("external_questions", questions);
        run.setRunAttributesJson(toJson(attributes));
        runRepository.saveAndFlush(run);
        return acceptedAnswer(run, callId, question);
    }

    public record ExternalQuestion(AgentUserInputRequest request, boolean created) { }
    public record AcceptedAnswer(MangaAgentRun run, String callId, long pendingVersion, String answer,
                                  String answerHash, UUID actionId, String canonicalResult) {
        @Override public String toString() { return "AcceptedAnswer[actionId=" + actionId + ", pendingVersion=" + pendingVersion + "]"; }
    }
    public record AnswerContinuation(MangaAgentRun run, String callId, String answer, boolean claimed,
                                      AgentAnswerDelivery delivery) { }

    private AcceptedAnswer acceptedAnswer(MangaAgentRun run, String callId, Map<String, Object> question) {
        if (!(question.get("action_id") instanceof String action) || !(question.get("canonical_result") instanceof String result)
                || !(question.get("pending_version") instanceof Number version)
                || !(question.get("answer_hash") instanceof String hash) || !(question.get("answer") instanceof String answer))
            throw new BusinessException(409, "原答复缺少可靠投递记录，已有产物保留");
        return new AcceptedAnswer(run, callId, version.longValue(), answer, hash, UUID.fromString(action), result);
    }

    /** The caller's automation transaction persists acceptance and its queue response together. */
    @Transactional
    public AcceptedAnswer acceptBackgroundAnswer(Long actor, Long chapter, UUID requestId, UUID conversationId,
                                                  String callId, Long version, String answer, String selectedOptionId) {
        var run = runRepository.findForUpdate(actor, chapter, requestId)
                .orElseThrow(() -> new BusinessException(404, "后台创作运行不存在"));
        if (run.getConversation() == null || !conversationId.equals(run.getConversation().getConversationUuid())
                || !Set.of(AgentRunType.STORY_CHAT, AgentRunType.MANGA_AGENT).contains(run.getRunType()))
            throw new BusinessException(409, "后台创作会话关联已变化");
        return acceptAnswer(run.getConversation(), requestId, callId, version, answer, selectedOptionId);
    }

    @Transactional(readOnly = true)
    public boolean isNativeAnswerRecorded(MangaAgentConversation conversation, UUID requestId, String callId, Long version) {
        if (callId == null || version == null) return false;
        var run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId).orElse(null);
        if (run == null) return false;
        var question = objectMap(objectMap(readMap(run.getRunAttributesJson()).get("external_questions")).get(callId));
        return "NATIVE_RECORDED".equals(question.get("delivery_status"))
                && question.get("pending_version") instanceof Number pending && pending.longValue() == version
                && question.get("native_checkpoint_version") instanceof Number checkpoint && checkpoint.longValue() >= 0
                && question.get("canonical_result") instanceof String canonical
                && ToolIdempotencyService.sha256(canonical).equals(question.get("native_result_hash"));
    }

    public boolean hasActiveExecutionLease(MangaAgentRun run) {
        return leaseService != null && leaseService.hasActiveLease(run);
    }

    public record AnswerExecution(AgentTaskType taskType, String stepId) { }

    @Transactional(readOnly = true)
    public AnswerExecution answerExecution(MangaAgentConversation conversation, UUID requestId, AcceptedAnswer accepted) {
        var run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        var question = objectMap(objectMap(readMap(run.getRunAttributesJson()).get("external_questions")).get(accepted.callId()));
        var actual = acceptedAnswer(run, accepted.callId(), question);
        if (!actual.actionId().equals(accepted.actionId()) || !actual.answerHash().equals(accepted.answerHash())
                || !"local-tools-v1".equals(question.get("profile")) || !(question.get("step_id") instanceof String step) || step.isBlank())
            throw new BusinessException(409, "原提问执行身份已变化");
        try { return new AnswerExecution(AgentTaskType.valueOf(String.valueOf(question.get("task_type"))), step); }
        catch (IllegalArgumentException error) { throw new BusinessException(409, "原提问执行类型不兼容"); }
    }

    /** Must be called separately from acceptAnswer, after the session turn is acquired. */
    @Transactional
    public AnswerContinuation claimAnswerDelivery(MangaAgentConversation conversation, UUID requestId, AcceptedAnswer accepted) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var questions = objectMap(attributes.get("external_questions"));
        var question = objectMap(questions.get(accepted.callId()));
        var actual = acceptedAnswer(run, accepted.callId(), question);
        if (!actual.actionId().equals(accepted.actionId()) || !actual.answerHash().equals(accepted.answerHash())
                || actual.pendingVersion() != accepted.pendingVersion() || !actual.canonicalResult().equals(accepted.canonicalResult()))
            throw new BusinessException(409, "原答复关联已变化，请刷新后重试");
        var current = waitingInput(run);
        if ((current != null && !accepted.callId().equals(current.toolCallId()))
                || !Set.of(MangaAgentRunStatus.WAITING_USER, MangaAgentRunStatus.RUNNING, MangaAgentRunStatus.FAILED,
                           MangaAgentRunStatus.INTERRUPTED).contains(run.getStatus())
                || (leaseService != null && leaseService.hasActiveLease(run))
                || (leaseService == null && "DELIVERING".equals(question.get("delivery_status"))
                    && run.getStatus() == MangaAgentRunStatus.RUNNING)) {
            return new AnswerContinuation(run, actual.callId(), actual.answer(), false, null);
        }
        if (!"ANSWERED".equals(question.get("status")) || attributes.containsKey("review_result"))
            throw new BusinessException(409, "该答复不能继续执行，请查看原审阅结果");
        if (run.getRunType() == AgentRunType.MANGA_AGENT) {
            AgentTaskType task;
            try { task = AgentTaskType.valueOf(String.valueOf(question.get("task_type"))); }
            catch (IllegalArgumentException error) { throw new BusinessException(409, "原提问执行类型不兼容"); }
            if (!completedAnswerStep(run, accepted.callId(), question, task, String.valueOf(question.get("step_id")), attributes))
                requireExecutionBinding(run, task, String.valueOf(question.get("step_id")), attributes);
            if (!"local-tools-v1".equals(question.get("profile"))) throw new BusinessException(409, "原提问工具契约已变化");
        }
        MangaAgentRun claimed = claimContinuation(run);
        // claim refreshes the entity; update the delivery metadata only after that refresh.
        attributes = new LinkedHashMap<>(readMap(claimed.getRunAttributesJson()));
        questions = objectMap(attributes.get("external_questions"));
        question = objectMap(questions.get(accepted.callId()));
        if (!"NATIVE_RECORDED".equals(question.get("delivery_status"))) question.put("delivery_status", "DELIVERING");
        question.put("delivery_fencing_token", claimed.getFencingToken());
        question.put("delivery_owner", claimed.getOwnerInstanceId());
        questions.put(accepted.callId(), question);
        attributes.put("external_questions", questions);
        claimed.setRunAttributesJson(toJson(attributes));
        runRepository.saveAndFlush(claimed);
        var delivery = new AgentAnswerDelivery(actual.actionId(), actual.callId(), actual.pendingVersion(),
                actual.answerHash(), actual.canonicalResult(), claimed.getFencingToken());
        return new AnswerContinuation(claimed, actual.callId(), actual.answer(), true, delivery);
    }

    @Transactional(readOnly = true)
    public void requireAnswerDelivery(AgentRunRequest request, AgentAnswerDelivery delivery) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(Long.valueOf(request.userId()), request.chapterId(), request.requestId())
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        verifyAnswerDelivery(run, request, delivery);
    }

    @Transactional(readOnly = true)
    public void requireCompletedAnswerStep(MangaWorkflowExecutionContext context) {
        if (context == null || context.answerResume() == null) throw new BusinessException(409, "原答复节点缺失");
        MangaAgentRun run = runRepository.findByConversationIdAndRequestId(context.conversation().getId(), context.requestId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        requireClaim(run, context.executionClaim());
        var delivery = context.answerResume().delivery();
        var attributes = readMap(run.getRunAttributesJson());
        var question = objectMap(objectMap(attributes.get("external_questions")).get(delivery.callId()));
        var accepted = acceptedAnswer(run, delivery.callId(), question);
        if (!accepted.actionId().equals(delivery.actionId()) || !accepted.answerHash().equals(delivery.answerHash())
                || !completedAnswerStep(run, delivery.callId(), question, context.answerResume().taskType(),
                        context.answerResume().stepId(), attributes))
            throw new BusinessException(409, "原答复节点缺少已完成证据，已有产物保留");
    }

    private boolean completedAnswerStep(MangaAgentRun run, String callId, Map<String, Object> question,
                                        AgentTaskType task, String stepId, Map<String, Object> attributes) {
        if (run.getRunType() != AgentRunType.MANGA_AGENT || run.getRoute() != MangaWorkflowRoute.DIRECTOR) return false;
        ExecutionPlan plan = executionPlan(run);
        if (plan == null || plan.steps().size() < 2) return false;
        var step = plan.steps().stream().filter(candidate -> (plan.planId() + ":" + candidate.sequence()).equals(stepId)).findFirst().orElse(null);
        if (step == null || !"COMPLETED".equals(step.status())) return false;
        var record = stepRepository == null ? null : stepRepository.findByRunIdAndPlanIdAndStepSequence(run.getId(), plan.planId(), step.sequence()).orElse(null);
        var binding = objectMap(objectMap(attributes.get("manga_execution_bindings")).get(stepId));
        var receipt = objectMap(question.get("native_completion"));
        String expectedTask = switch (step.route()) {
            case CONVERSATION -> AgentTaskType.MANGA_CONVERSATION.name();
            case CREATIVE -> AgentTaskType.MANGA_CREATIVE.name();
            case STORYBOARD -> AgentTaskType.MANGA_STORYBOARD.name();
            case REVIEW -> AgentTaskType.MANGA_REVIEW.name();
            case DIRECTOR -> "";
        };
        String session = com.artverse.agent.gateway.AgentScopeRuntimeContextFactory.createSessionId(
                String.valueOf(run.getUser().getId()), run.getStory().getId(), run.getChapter().getId(), run.getConversation().getConversationUuid(), task);
        if (record == null || !"COMPLETED".equals(record.getStatus()) || record.getRun() == null
                || !Objects.equals(record.getRun().getId(), run.getId()) || !Objects.equals(record.getPlanId(), plan.planId())
                || record.getStepSequence() != step.sequence() || record.getRoute() != step.route()
                || task == null || !expectedTask.equals(task.name()) || !"manga-workflow-v1".equals(run.getWorkflowVersion())
                || !(binding.get("version") instanceof Number version) || version.intValue() != 1
                || !"local-tools-v1".equals(binding.get("profile")) || !run.getWorkflowVersion().equals(binding.get("workflow_version"))
                || !step.route().name().equals(binding.get("route")) || !task.name().equals(binding.get("task_type"))
                || !stepId.equals(binding.get("step_id")) || !plan.planId().equals(binding.get("plan_id"))
                || !"NATIVE_RECORDED".equals(question.get("delivery_status"))
                || !Objects.equals(question.get("action_id"), receipt.get("action_id"))
                || !Objects.equals(question.get("answer_hash"), receipt.get("answer_hash"))
                || !callId.equals(receipt.get("call_id")) || !String.valueOf(question.get("pending_version")).equals(receipt.get("pending_version"))
                || !task.name().equals(receipt.get("task_type")) || !stepId.equals(receipt.get("step_id"))
                || !session.equals(receipt.get("session_id")) || !(receipt.get("checkpoint_version") instanceof String checkpoint) || !checkpoint.matches("[0-9]+")
                || !(receipt.get("result_id") instanceof String id) || id.isBlank()
                || !(receipt.get("result_hash") instanceof String hash) || hash.isBlank())
            throw new BusinessException(409, "原答复节点缺少已完成证据，已有产物保留");
        return true;
    }

    @Transactional
    public void recordNativeAnswer(AgentRunRequest request, AgentAnswerDelivery delivery, long nativeVersion, String sessionId) {
        lockRequestAutomation(request);
        MangaAgentRun run = runRepository.findForUpdate(Long.valueOf(request.userId()), request.chapterId(), request.requestId())
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        var question = verifyAnswerDelivery(run, request, delivery);
        if (nativeVersion < 0 || !com.artverse.agent.gateway.AgentScopeRuntimeContextFactory.createSessionId(request).equals(sessionId))
            throw new BusinessException(409, "原生答复证据与本次会话不一致");
        if ("NATIVE_RECORDED".equals(question.get("delivery_status"))) return;
        question.put("delivery_status", "NATIVE_RECORDED");
        question.put("native_checkpoint_version", nativeVersion);
        question.put("native_session_id", sessionId);
        question.put("native_result_hash", ToolIdempotencyService.sha256(delivery.canonicalResult()));
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var questions = objectMap(attributes.get("external_questions"));
        questions.put(delivery.callId(), question);
        attributes.put("external_questions", questions);
        run.setRunAttributesJson(toJson(attributes));
        runRepository.saveAndFlush(run);
    }

    private Map<String, Object> verifyAnswerDelivery(MangaAgentRun run, AgentRunRequest request, AgentAnswerDelivery delivery) {
        var question = objectMap(objectMap(readMap(run.getRunAttributesJson()).get("external_questions")).get(delivery.callId()));
        var actual = acceptedAnswer(run, delivery.callId(), question);
        Object requestedFence = request.variables().get("fencing_token");
        if (!actual.actionId().equals(delivery.actionId()) || !actual.answerHash().equals(delivery.answerHash())
                || actual.pendingVersion() != delivery.pendingVersion() || !actual.canonicalResult().equals(delivery.canonicalResult())
                || !Objects.equals(run.getFencingToken(), delivery.fencingToken())
                || !(requestedFence instanceof Number n) || n.longValue() != delivery.fencingToken()
                || !(question.get("delivery_fencing_token") instanceof Number f) || f.longValue() != delivery.fencingToken()
                || !(request.variables().get("execution_owner") instanceof String owner) || owner.isBlank()
                || !owner.equals(run.getOwnerInstanceId()) || !owner.equals(question.get("delivery_owner"))
                || !Objects.equals(question.get("step_id"), request.variables().get("step_id"))
                || !request.taskType().name().equals(question.get("task_type"))
                || !"local-tools-v1".equals(question.get("profile"))
                || run.getStatus() != MangaAgentRunStatus.RUNNING || run.getConversation() == null || run.getStory() == null
                || !Objects.equals(run.getConversation().getConversationUuid(), request.conversationId())
                || !Objects.equals(run.getStory().getId(), request.storyId())
                || !List.of("DELIVERING", "NATIVE_RECORDED").contains(question.get("delivery_status"))
                || (leaseService != null && !leaseService.owns(run, delivery.fencingToken())))
            throw new BusinessException(409, "原答复投递执行权或关联已变化");
        return question;
    }

    /** A real native AgentResult and its durable checkpoint must both exist before replaying a final answer. */
    @Transactional
    public void recordNativeCompletion(AgentRunRequest request, AgentAnswerDelivery delivery, long checkpointVersion,
                                       String resultId, String resultText) {
        lockRequestAutomation(request);
        MangaAgentRun run = runRepository.findForUpdate(Long.valueOf(request.userId()), request.chapterId(), request.requestId())
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        var question = verifyAnswerDelivery(run, request, delivery);
        if (!"NATIVE_RECORDED".equals(question.get("delivery_status")) || checkpointVersion < 0
                || resultId == null || resultId.isBlank() || resultText == null || resultText.isBlank())
            throw new BusinessException(409, "原生完成证据不完整，已受理答复及产物保留");
        var receipt = nativeCompletionReceipt(request, delivery, checkpointVersion, resultId, resultText);
        var existing = objectMap(question.get("native_completion"));
        if (!existing.isEmpty() && !existing.equals(receipt))
            throw new BusinessException(409, "原生完成证据已变化，已有产物保留");
        question.put("native_completion", receipt);
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var questions = objectMap(attributes.get("external_questions"));
        questions.put(delivery.callId(), question);
        attributes.put("external_questions", questions);
        run.setRunAttributesJson(toJson(attributes));
        runRepository.saveAndFlush(run);
    }

    @Transactional(readOnly = true)
    public void requireNativeCompletion(AgentRunRequest request, AgentAnswerDelivery delivery, long checkpointVersion,
                                        String resultId, String resultText) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(Long.valueOf(request.userId()), request.chapterId(), request.requestId())
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        var question = verifyAnswerDelivery(run, request, delivery);
        if (!nativeCompletionReceipt(request, delivery, checkpointVersion, resultId, resultText)
                .equals(objectMap(question.get("native_completion"))))
            throw new BusinessException(409, "原生最终回复缺少匹配完成凭据，已受理答复及产物保留");
    }

    private Map<String, Object> nativeCompletionReceipt(AgentRunRequest request, AgentAnswerDelivery delivery,
            long checkpointVersion, String resultId, String resultText) {
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("action_id", delivery.actionId().toString());
        receipt.put("call_id", delivery.callId());
        receipt.put("pending_version", String.valueOf(delivery.pendingVersion()));
        receipt.put("answer_hash", delivery.answerHash());
        receipt.put("task_type", request.taskType().name());
        receipt.put("step_id", request.variables().get("step_id"));
        receipt.put("session_id", com.artverse.agent.gateway.AgentScopeRuntimeContextFactory.createSessionId(request));
        receipt.put("checkpoint_version", String.valueOf(checkpointVersion));
        receipt.put("result_id", resultId);
        receipt.put("result_hash", ToolIdempotencyService.sha256(resultText));
        return receipt;
    }

    private Map<String, Object> objectMap(Object value) {
        var result = new LinkedHashMap<String, Object>();
        if (value instanceof Map<?, ?> map) map.forEach((key, entry) -> result.put(String.valueOf(key), entry));
        return result;
    }

    @Transactional
    public MangaAgentRun claimReviewedContinuation(MangaAgentConversation conversation, UUID requestId, UUID artifactId) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        var attributes = readMap(run.getRunAttributesJson());
        Object value = attributes.get("review_result");
        if (run.getRunType() != AgentRunType.STORY_CHAT || !(value instanceof Map<?, ?> receipt) || artifactId == null
                || !artifactId.toString().equals(receipt.get("artifact_id"))
                || !List.of("COMMITTED", "REJECTED").contains(String.valueOf(receipt.get("status")))) {
            throw new BusinessException(409, "请先完成这份草稿的人工审阅。");
        }
        if (run.getStatus() == MangaAgentRunStatus.SUCCEEDED
                && !"PENDING".equals(attributes.get("review_summary_status"))) return run;
        if (!Set.of(MangaAgentRunStatus.WAITING_USER, MangaAgentRunStatus.FAILED, MangaAgentRunStatus.SUCCEEDED)
                .contains(run.getStatus())) {
            throw new BusinessException(409, "总结任务正在执行或已停止，请查看任务状态。");
        }
        MangaAgentRun claimed = claimContinuation(run);
        var current = new LinkedHashMap<>(readMap(claimed.getRunAttributesJson()));
        current.put("review_summary_continuation", Map.of("artifact_id", artifactId.toString(),
                "fencing_token", claimed.getFencingToken(), "id", UUID.randomUUID().toString()));
        current.put("review_summary_status", "PENDING");
        claimed.setRunAttributesJson(toJson(current));
        return runRepository.saveAndFlush(claimed);
    }

    @Transactional
    public boolean recordReviewReceipt(MangaAgentConversation conversation, UUID requestId,
                                    Map<String, Object> receipt, boolean continueAgent) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        Map<String, Object> normalized = readMap(toJson(receipt));
        Object previous = attributes.get("review_result");
        if (previous instanceof Map<?, ?> existing) {
            for (String field : List.of("artifact_id", "status", "content_hash", "decision", "chapter_id",
                    "chapter_version", "base_version", "revision_id", "revision_number")) {
                if (existing.containsKey(field) && !Objects.equals(existing.get(field), normalized.get(field))) {
                    throw new BusinessException(409, "该任务已经保存另一份审阅结果");
                }
            }
            var enriched = new LinkedHashMap<>(normalized);
            existing.forEach((key, entry) -> enriched.put(String.valueOf(key), entry));
            attributes.put("review_result", enriched);
        } else attributes.put("review_result", normalized);
        if (continueAgent && !"COMPLETED".equals(attributes.get("review_summary_status"))) {
            attributes.put("review_summary_status", "PENDING");
        }
        run.setRunAttributesJson(toJson(attributes));
        runRepository.save(run);
        return previous == null;
    }

    @Transactional
    public MangaAgentRun markReviewSummarySucceeded(MangaAgentConversation conversation, UUID requestId,
                                                    UUID artifactId, Long fence, String reply) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "修改任务不存在"));
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        if (artifactId == null || fence == null || !(attributes.get("review_summary_continuation") instanceof Map<?, ?> continuation)
                || !artifactId.toString().equals(continuation.get("artifact_id"))
                || !(continuation.get("fencing_token") instanceof Number token) || token.longValue() != fence
                || run.getStatus() != MangaAgentRunStatus.RUNNING
                || !Objects.equals(run.getFencingToken(), fence)
                || (leaseService != null && !leaseService.owns(run, fence))) {
            throw new BusinessException(409, "总结执行权已失效，请查看原审阅回执");
        }
        attributes.put("review_summary_status", "COMPLETED");
        run.setRunAttributesJson(toJson(attributes));
        return applyTerminal(run, MangaAgentRunStatus.SUCCEEDED, reply, null);
    }

    private MangaAgentRun claimContinuation(MangaAgentRun run) {
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setUserInputRequestJson(null);
        run.setErrorMessage(null);
        run.setCompletedAt(null);
        run.setCurrentPhase("WAITING_MODEL");
        run.setLastProgressAt(OffsetDateTime.now());
        // The lease uses JDBC and refreshes this entity: persist RUNNING before either operation.
        return claimIfConfigured(runRepository.saveAndFlush(run));
    }

    @Transactional(readOnly = true)
    public List<RunSnapshot> storyConversationRuns(MangaAgentConversation conversation) {
        return runRepository.findByConversationIdOrderByCreatedAtDesc(conversation.getId(), PageRequest.of(0, 30))
                .stream().map(snapshotReader::snapshot).toList();
    }

    @Transactional
    public MangaAgentRun updateRoutingDecision(MangaAgentRun run, RoutingDecision decision, MangaRouteSource source,
                                               MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaAgentRun attached = lockedClaimedRun(run, claim);
        attached.setRoute(decision.route());
        attached.setRouteKey(decision.route().name());
        attached.setRouteSource(source == null ? MangaRouteSource.AUTO : source);
        attached.setRouteConfidence(decision.confidence());
        attached.setRouterVersion(decision.routerVersion());
        attached.setRoutingDecisionJson(toJson(decision));
        attached.setCurrentPhase("AGENT");
        attached.setLastProgressAt(OffsetDateTime.now());
        return runRepository.save(attached);
    }

    @Transactional
    public MangaAgentRun markRouting(MangaAgentRun run, MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaAgentRun attached = lockedClaimedRun(run, claim);
        attached.setCurrentPhase("ROUTER");
        attached.setLastProgressAt(OffsetDateTime.now());
        return runRepository.save(attached);
    }

    @Transactional
    public MangaAgentRun recordModelConfig(MangaAgentRun run, Long modelConfigId) {
        MangaAgentRun attached = runRepository.getReferenceById(run.getId());
        if (attached.getModelConfigId() == null) {
            attached.setModelConfigId(modelConfigId);
        } else if (modelConfigId != null && !modelConfigId.equals(attached.getModelConfigId())) {
            throw new BusinessException(409, "Run model configuration is already fixed");
        }
        return runRepository.save(attached);
    }

    @Transactional
    public MangaAgentRun recordModelConfig(MangaAgentRun run, Long modelConfigId, MangaWorkflowExecutionContext.ExecutionClaim claim) {
        lockedClaimedRun(run, claim);
        return recordModelConfig(run, modelConfigId);
    }

    private MangaAgentRun lockedClaimedRun(MangaAgentRun run, MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaAgentRun identity = runRepository.findById(run.getId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        // Read the child identity without locking, take the parent lock first, then lock the child.
        // The returned run is detached from startOrReuse and must not expose lazy associations here.
        lockAutomationExecution(identity.getConversation(), identity.getRequestId(), claim);
        MangaAgentRun attached = runRepository.findForUpdateById(run.getId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        requireClaim(attached, claim);
        return attached;
    }

    @Transactional
    public void bindModelCapabilities(AgentRunRequest request) {
        lockRequestAutomation(request);
        var run = runRepository.findForUpdate(Long.valueOf(request.userId()), request.chapterId(), request.requestId())
                .orElseThrow(() -> new BusinessException(404, "模型运行不存在"));
        Object token = request.variables().get("fencing_token");
        Long fence = token instanceof Number number ? number.longValue() : null;
        if (run.getStatus() != MangaAgentRunStatus.RUNNING || fence == null || !fence.equals(run.getFencingToken())
                || run.getConversation() == null || !Objects.equals(request.conversationId(), run.getConversation().getConversationUuid())
                || run.getStory() == null || !Objects.equals(request.storyId(), run.getStory().getId())
                || (leaseService != null && !leaseService.owns(run, fence)))
            throw new BusinessException(409, "模型运行执行权或会话关联已变化");
        var spec = request.modelSpec();
        if (spec == null || (run.getModelConfigId() != null && !run.getModelConfigId().equals(spec.configId())))
            throw new BusinessException(409, "模型配置与原运行不一致");
        var identity = new LinkedHashMap<String, Object>();
        identity.put("provider", spec.provider());
        identity.put("endpoint_hash", AgentModelSpecFactory.sha256Hex(spec.baseUrl().trim().replaceAll("/+$", "")));
        identity.put("model", spec.model());
        identity.put("config_id", spec.configId());
        identity.put("capabilities", spec.capabilities());
        // Normalize through the same persisted representation to avoid Integer/Long equality differences.
        var normalized = readMap(toJson(identity));
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var bindings = objectMap(attributes.get("model_capability_bindings"));
        Object executionStep = request.variables().get("step_id");
        if (!(executionStep instanceof String step) || step.isBlank() || step.length() > 255)
            throw new BusinessException(409, "模型运行缺少准确执行步骤");
        String slot = request.taskType().sessionSuffix() + ":" + step;
        Object previous = bindings.get(slot);
        if (previous != null) {
            if (!previous.equals(normalized)) throw new BusinessException(409, "原运行模型能力声明已变化，请保留产物并使用新运行");
            return;
        }
        bindings.put(slot, normalized);
        attributes.put("model_capability_bindings", bindings);
        run.setRunAttributesJson(toJson(attributes));
        runRepository.saveAndFlush(run);
    }

    @Transactional
    public void recordSkillSelection(Long userId, Long chapterId, UUID requestId,
                                     BusinessSkillSelection selection, String promptVersion) {
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .ifPresent(run -> {
                    Map<String, Object> versions = new LinkedHashMap<>(readMap(run.getSkillVersionsJson()));
                    versions.putAll(selection.skillVersions());
                    run.setSkillVersionsJson(toJson(versions));
                    run.setPromptVersion(promptVersion);
                    runRepository.save(run);
                    if (!selection.isEmpty()) {
                        appendEvent(run, "skill_selected", Map.of(
                                "skillKeys", selection.skillKeys(),
                                "versions", selection.skillVersions(),
                                "primarySkillKey", selection.primarySkillKey() == null ? "" : selection.primarySkillKey(),
                                "promptVersion", promptVersion == null ? "" : promptVersion));
                    }
                });
    }

    @Transactional
    public void recordStepSkillSelection(Long userId, Long chapterId, UUID requestId, String stepId,
                                         BusinessSkillSelection selection) {
        if (stepRepository == null || stepId == null || stepId.isBlank()) return;
        var primarySkill = selection.primarySkill();
        if (primarySkill == null) return;
        int separator = stepId.lastIndexOf(':');
        if (separator <= 0 || separator == stepId.length() - 1) return;
        int sequence;
        try {
            sequence = Integer.parseInt(stepId.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return;
        }
        String planId = stepId.substring(0, separator);
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .flatMap(run -> stepRepository.findByRunIdAndPlanIdAndStepSequence(
                        run.getId(), planId, sequence))
                .ifPresent(step -> {
                    step.setSkillKey(primarySkill.skillKey());
                    step.setSkillVersion(primarySkill.semanticVersion());
                    stepRepository.save(step);
                });
    }

    @Transactional
    public void recordKnowledgeSnapshot(Long userId, Long chapterId, UUID requestId,
                                        KnowledgeService.RecallPreview preview) {
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .ifPresent(run -> {
                    run.setKnowledgeSnapshotId(preview.snapshotId());
                    RunContextSnapshot contextSnapshot = withKnowledgeRecallHash(
                            readContextSnapshot(run.getContextSnapshotJson()),
                            preview.contextHash());
                    run.setContextSnapshotJson(toJson(contextSnapshot));
                    runRepository.save(run);
                    appendEvent(run, "rag_retrieved", Map.of(
                            "snapshotId", preview.snapshotId() == null ? "" : preview.snapshotId(),
                            "embeddingSpaceId", preview.embeddingSpaceId(),
                            "contextHash", preview.contextHash(),
                            "items", preview.items().stream().map(item -> Map.of(
                                    "knowledgeUnitId", item.knowledgeUnitId(),
                                    "version", item.version(),
                                    "score", item.score())).toList()));
                });
    }

    @Transactional
    public void recordContextSnapshot(MangaAgentRun run, RunContextSnapshot snapshot, MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaAgentRun attached = lockedClaimedRun(run, claim);
        attached.setContextSnapshotJson(toJson(snapshot));
        attached.setLastProgressAt(OffsetDateTime.now());
        runRepository.save(attached);
    }

    private void persistExecutionSteps(MangaAgentRun run, String executionPlanJson) {
        if (stepRepository == null || executionPlanJson == null || executionPlanJson.isBlank()) {
            return;
        }
        try {
            ExecutionPlan plan = objectMapper.readValue(executionPlanJson, ExecutionPlan.class);
            for (com.artverse.application.workflow.ExecutionStep step : plan.steps()) {
                MangaAgentRunStep record = stepRepository
                        .findByRunIdAndPlanIdAndStepSequence(run.getId(), plan.planId(), step.sequence())
                        .orElseGet(MangaAgentRunStep::new);
                record.setRun(run);
                record.setPlanId(plan.planId());
                record.setStepSequence(step.sequence());
                record.setRoute(step.route());
                record.setStatus(step.status());
                record.setMutating(step.mutating());
                record.setInputSummary(step.inputSummary());
                record.setOutputSummary(step.outputSummary());
                record.setStartedAt(step.startedAt());
                record.setCompletedAt(step.completedAt());
                stepRepository.save(record);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Failed to persist execution plan steps", error);
        }
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRun> findRun(Long userId, Long chapterId, UUID requestId) {
        return runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId);
    }

    /** Revalidate persisted scope on every native model/tool call, including resumed sessions. */
    @Transactional(readOnly = true)
    public void requireToolExecution(com.artverse.agent.MangaAgentRuntimeContext context, boolean summary) {
        if (context == null) throw new BusinessException(403, "SCOPE_DENIED");
        MangaAgentRun run = requireActiveExecution(context.userId(), context.storyId(), context.chapterId(),
                context.requestId(), context.conversationId(), context.fencingToken());
        if (context.executionOwner() == null || context.executionOwner().isBlank()
                || !Objects.equals(context.executionOwner(), run.getOwnerInstanceId()))
            throw new BusinessException(409, "RUN_NOT_ACTIVE: execution owner changed");
        var attributes = readMap(run.getRunAttributesJson());
        requireExecutionBinding(run, context.taskType(), context.stepId(), attributes);
        boolean reviewed = attributes.get("review_result") instanceof Map<?, ?>;
        if (summary != reviewed) throw new BusinessException(403, "TOOL_NOT_ALLOWED: review phase changed");
        if (summary) {
            var receipt = objectMap(attributes.get("review_result"));
            var continuation = objectMap(attributes.get("review_summary_continuation"));
            var execution = objectMap(attributes.get("story_chat_execution"));
            if (run.getRunType() != AgentRunType.STORY_CHAT
                    || context.taskType() == null
                    || !Set.of(com.artverse.agent.AgentTaskType.STORY_CHAT_READ,
                            com.artverse.agent.AgentTaskType.STORY_CHAT_WRITE).contains(context.taskType())
                    || !(execution.get("version") instanceof Number version) || version.intValue() != 1
                    || !"local-tools-v1".equals(execution.get("profile"))
                    || !Objects.equals(run.getRouteKey(), execution.get("route"))
                    || !Objects.equals(context.taskType().name(), execution.get("task_type"))
                    || context.stepId() == null || !Objects.equals(context.stepId(), execution.get("step_id"))
                    || !Set.of("COMMITTED", "REJECTED").contains(String.valueOf(receipt.get("status")))
                    || !(receipt.get("chapter_id") instanceof Number chapter) || chapter.longValue() != context.chapterId()
                    || !(receipt.get("artifact_id") instanceof String artifactId) || artifactId.isBlank()
                    || !Objects.equals(artifactId, continuation.get("artifact_id"))
                    || !(continuation.get("id") instanceof String id) || id.isBlank()
                    || !(continuation.get("fencing_token") instanceof Number token)
                    || token.longValue() != context.fencingToken()
                    || !"PENDING".equals(attributes.get("review_summary_status")))
                throw new BusinessException(403, "TOOL_NOT_ALLOWED: review continuation changed");
        }
    }

    /** Rebuild authority from persisted business facts, never from a model or restored tool context. */
    @Transactional
    public com.artverse.agent.gateway.tools.AgentToolCapability.ExecutionScope captureToolExecutionScope(AgentRunRequest request) {
        if (request == null || request.taskType() == null || !request.taskType().isMangaExecutionTask())
            throw new BusinessException(403, "SCOPE_DENIED: business execution required");
        var context = new com.artverse.agent.gateway.AgentScopeRuntimeContextFactory().create(request)
                .get(com.artverse.agent.MangaAgentRuntimeContext.class);
        lockAutomationExecution(context);
        MangaAgentRun run = runRepository.findForUpdate(context.userId(), context.chapterId(), context.requestId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        var attributes = readMap(run.getRunAttributesJson());
        boolean summary = attributes.get("review_result") instanceof Map<?, ?>;
        if (summary != Boolean.TRUE.equals(request.variables().get("review_completed")))
            throw new BusinessException(403, "TOOL_NOT_ALLOWED: persisted review phase differs");
        requireToolExecution(context, summary);
        var source = summary ? com.artverse.agent.gateway.tools.AgentToolCapability.Source.REVIEW_SUMMARY
                : context.automationClaim() == null ? com.artverse.agent.gateway.tools.AgentToolCapability.Source.INTERACTIVE
                : com.artverse.agent.gateway.tools.AgentToolCapability.Source.AUTOMATION;
        String session = com.artverse.agent.gateway.AgentScopeRuntimeContextFactory.createSessionId(request);
        com.artverse.agent.AgentReviewExpert expert = null;
        if (request.variables().containsKey("review_expert")) {
            if (!(request.variables().get("review_expert") instanceof com.artverse.agent.AgentReviewExpert identity)
                    || summary || context.taskType() != AgentTaskType.MANGA_REVIEW
                    || !Objects.equals(identity.parentRequestId(), run.getRequestId())
                    || !Objects.equals(identity.parentStepId(), context.stepId()))
                throw new BusinessException(403, "TOOL_NOT_ALLOWED: invalid expert scope");
            var binding = objectMap(objectMap(objectMap(attributes.get("review_expert_tasks"))
                    .get(context.stepId())).get(identity.expertId()));
            if (!identity.taskId().equals(binding.get("task_id"))
                    || !identity.expertId().equals(binding.get("expert_id"))
                    || !run.getRequestId().toString().equals(binding.get("parent_request_id"))
                    || !context.stepId().equals(binding.get("parent_step_id"))
                    || !"local-tools-v1".equals(binding.get("profile"))
                    || com.artverse.agent.MangaReviewSubagentDeclarations.all().stream()
                    .noneMatch(declaration -> declaration.getName().equals(identity.expertId())))
                throw new BusinessException(403, "TOOL_NOT_ALLOWED: expert task binding changed");
            String repositorySession = "review-" + AgentModelSpecFactory.sha256Hex(request.userId() + ":"
                    + run.getStory().getId() + ":" + run.getChapter().getId() + ":" + run.getRequestId() + ":" + context.stepId());
            session = "expert-" + AgentModelSpecFactory.sha256Hex(repositorySession + ":" + identity.expertId() + ":" + identity.taskId());
            if (!session.equals(request.variables().get("review_expert_session")))
                throw new BusinessException(403, "TOOL_NOT_ALLOWED: expert session differs");
            expert = identity;
            source = com.artverse.agent.gateway.tools.AgentToolCapability.Source.EXPERT;
        }
        var binding = run.getRunType() == AgentRunType.MANGA_AGENT
                ? objectMap(objectMap(attributes.get("manga_execution_bindings")).get(context.stepId()))
                : objectMap(attributes.get("story_chat_execution"));
        var continuation = objectMap(attributes.get("review_summary_continuation"));
        var receipt = objectMap(attributes.get("review_result"));
        var model = request.modelSpec();
        String modelHash = model == null ? "none" : AgentBudgetService.hash(Map.of(
                "provider", model.provider(), "endpoint_hash", AgentModelSpecFactory.sha256Hex(model.baseUrl()),
                "model", model.model(), "config_id", Objects.toString(model.configId(), ""), "capabilities", model.capabilities()));
        return new com.artverse.agent.gateway.tools.AgentToolCapability.ExecutionScope(run.getTenantId(), run.getUser().getId(),
                run.getStory().getId(), run.getChapter().getId(), "OWNED_STORY_CHAPTERS", run.getRequestId(), run.getId(),
                run.getConversation().getConversationUuid(), session, context.stepId(), context.taskType().name(), source,
                "local-tools-v1", run.getWorkflowVersion(), run.getOwnerInstanceId(), run.getFencingToken(),
                context.automationClaim(), expert, Objects.toString(continuation.get("id"), ""),
                receipt.isEmpty() ? "" : AgentBudgetService.hash(receipt), AgentBudgetService.hash(binding), modelHash);
    }

    /** Bind only a server-dispatched node after proving its persisted route or actual Director step. */
    @Transactional
    public void bindMangaExecution(MangaWorkflowExecutionContext context, AgentTaskType taskType) {
        if (context == null || context.conversation() == null || taskType == null)
            throw new BusinessException(403, "SCOPE_DENIED");
        lockAutomationExecution(context.conversation(), context.requestId(), context.executionClaim());
        MangaAgentRun run = runRepository.findForUpdate(context.conversation().getId(), context.requestId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        requireClaim(run, context.executionClaim());
        requireWorkflowScope(run, context);
        String step = context.stepId() == null || context.stepId().isBlank() ? taskType.sessionSuffix() : context.stepId();
        var expected = mangaExecutionIdentity(run, taskType, step);
        var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
        var bindings = objectMap(attributes.get("manga_execution_bindings"));
        var previous = bindings.get(step);
        if (previous != null && !previous.equals(expected))
            throw new BusinessException(409, "TOOL_NOT_ALLOWED: workflow binding changed");
        bindings.put(step, expected);
        attributes.put("manga_execution_bindings", bindings);
        run.setRunAttributesJson(toJson(attributes));
        runRepository.saveAndFlush(run);
    }

    private void requireExecutionBinding(MangaAgentRun run, AgentTaskType type, String step, Map<String, Object> attributes) {
        if (run.getRunType() == AgentRunType.MANGA_AGENT) {
            var expected = mangaExecutionIdentity(run, type, step);
            if (!expected.equals(objectMap(objectMap(attributes.get("manga_execution_bindings")).get(step))))
                throw new BusinessException(403, "TOOL_NOT_ALLOWED: workflow binding missing or changed");
            return;
        }
        var original = objectMap(attributes.get("story_chat_execution"));
        String route = run.getRouteKey();
        AgentTaskType expectedType = "WRITE".equals(route) || "POLISH".equals(route)
                ? AgentTaskType.STORY_CHAT_WRITE : AgentTaskType.STORY_CHAT_READ;
        if (run.getRunType() != AgentRunType.STORY_CHAT || route == null
                || !Set.of("WRITE", "POLISH", "READ", "DRAFT", "REVIEW").contains(route)
                || type != expectedType || !(original.get("version") instanceof Number version) || version.intValue() != 1
                || !"local-tools-v1".equals(original.get("profile")) || !route.equals(original.get("route"))
                || !type.name().equals(original.get("task_type")) || !("story-chat:" + route).equals(step)
                || !Objects.equals(step, original.get("step_id")))
            throw new BusinessException(403, "TOOL_NOT_ALLOWED: original story task or profile changed");
    }

    private Map<String, Object> mangaExecutionIdentity(MangaAgentRun run, AgentTaskType type, String stepId) {
        if (run.getRunType() != AgentRunType.MANGA_AGENT || type == null || stepId == null || stepId.isBlank()
                || !"manga-workflow-v1".equals(run.getWorkflowVersion()))
            throw new BusinessException(403, "TOOL_NOT_ALLOWED: unsupported manga workflow profile");
        MangaWorkflowRoute route = run.getRoute();
        if (route == null) throw new BusinessException(403, "TOOL_NOT_ALLOWED: workflow route missing");
        String planId = "";
        if (route == MangaWorkflowRoute.DIRECTOR) {
            ExecutionPlan plan = executionPlan(run);
            if (plan != null && plan.steps().size() > 1) {
                var step = plan.steps().stream().filter(candidate ->
                        (plan.planId() + ":" + candidate.sequence()).equals(stepId)).findFirst()
                        .orElseThrow(() -> new BusinessException(403, "TOOL_NOT_ALLOWED: unknown workflow step"));
                var record = stepRepository == null ? null : stepRepository.findByRunIdAndPlanIdAndStepSequence(
                        run.getId(), plan.planId(), step.sequence()).orElse(null);
                if (!"RUNNING".equals(step.status()) || record == null || !"RUNNING".equals(record.getStatus())
                        || record.getRun() == null || !Objects.equals(record.getRun().getId(), run.getId())
                        || !Objects.equals(record.getPlanId(), plan.planId()) || record.getStepSequence() != step.sequence()
                        || record.getRoute() != step.route())
                    throw new BusinessException(403, "TOOL_NOT_ALLOWED: workflow step is not active");
                route = step.route();
                planId = plan.planId();
            } else {
                RoutingDecision decision = routingDecision(run);
                if (decision == null || decision.suggestedSteps().size() > 1)
                    throw new BusinessException(403, "TOOL_NOT_ALLOWED: Director dispatch is not bound");
                route = decision.suggestedSteps().isEmpty() ? MangaWorkflowRoute.CONVERSATION : decision.suggestedSteps().getFirst();
                if (route == MangaWorkflowRoute.DIRECTOR) route = MangaWorkflowRoute.CONVERSATION;
            }
        }
        AgentTaskType expected = switch (route) {
            case CONVERSATION -> AgentTaskType.MANGA_CONVERSATION;
            case CREATIVE -> AgentTaskType.MANGA_CREATIVE;
            case STORYBOARD -> AgentTaskType.MANGA_STORYBOARD;
            case REVIEW -> AgentTaskType.MANGA_REVIEW;
            case DIRECTOR -> throw new BusinessException(403, "TOOL_NOT_ALLOWED: Director has no model dispatch");
        };
        if (type != expected || (planId.isEmpty() && !type.sessionSuffix().equals(stepId)))
            throw new BusinessException(403, "TOOL_NOT_ALLOWED: task differs from persisted workflow");
        return Map.of("version", 1, "profile", "local-tools-v1", "workflow_version", run.getWorkflowVersion(),
                "route", route.name(), "task_type", type.name(), "step_id", stepId, "plan_id", planId);
    }

    private void requireWorkflowScope(MangaAgentRun run, MangaWorkflowExecutionContext context) {
        if (context.user() == null || context.chapter() == null || context.chapter().getStory() == null
                || run.getUser() == null || !Objects.equals(run.getUser().getId(), context.user().getId())
                || run.getChapter() == null || !Objects.equals(run.getChapter().getId(), context.chapter().getId())
                || run.getStory() == null || !Objects.equals(run.getStory().getId(), context.chapter().getStory().getId())
                || run.getConversation() == null || !Objects.equals(run.getConversation().getConversationUuid(), context.conversation().getConversationUuid()))
            throw new BusinessException(403, "SCOPE_DENIED");
    }

    private void requireClaim(MangaAgentRun run, MangaWorkflowExecutionContext.ExecutionClaim claim) {
        if (claim == null || !claim.valid() || run.getStatus() != MangaAgentRunStatus.RUNNING
                || !Objects.equals(run.getOwnerInstanceId(), claim.owner())
                || !Objects.equals(run.getFencingToken(), claim.fencingToken())
                || leaseService == null || !leaseService.owns(run, claim.fencingToken()))
            throw new BusinessException(409, "RUN_NOT_ACTIVE: original execution claim changed");
    }

    /** A queued or derived workflow may use only the claim captured at its original start. */
    @Transactional(readOnly = true)
    public void requireWorkflowExecutionClaim(com.artverse.application.workflow.MangaWorkflowExecutionContext context) {
        if (context == null || context.executionClaim() == null || !context.executionClaim().valid()
                || context.user() == null || context.chapter() == null || context.chapter().getStory() == null
                || context.conversation() == null)
            throw new BusinessException(409, "RUN_NOT_ACTIVE: workflow execution claim is missing");
        var claim = context.executionClaim();
        MangaAgentRun run = requireActiveExecution(context.user().getId(), context.chapter().getStory().getId(),
                context.chapter().getId(), context.requestId(), context.conversation().getConversationUuid(), claim.fencingToken());
        if (!Objects.equals(claim.owner(), run.getOwnerInstanceId()))
            throw new BusinessException(409, "RUN_NOT_ACTIVE: original workflow owner changed");
    }

    @Transactional
    public void updateExecutionPlan(MangaWorkflowExecutionContext context, String executionPlanJson) {
        lockAutomationExecution(context.conversation(), context.requestId(), context.executionClaim());
        MangaAgentRun run = runRepository.findForUpdate(context.conversation().getId(), context.requestId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        requireClaim(run, context.executionClaim());
        requireWorkflowScope(run, context);
        if (run.getRunType() != AgentRunType.MANGA_AGENT || run.getRoute() != MangaWorkflowRoute.DIRECTOR)
            throw new BusinessException(403, "TOOL_NOT_ALLOWED: only the owned Director may update its plan");
        run.setExecutionPlanJson(executionPlanJson);
        run.setCurrentPhase("AGENT");
        run.setLastProgressAt(OffsetDateTime.now());
        runRepository.save(run);
        persistExecutionSteps(run, executionPlanJson);
    }

    private MangaAgentRun requireActiveExecution(Long userId, Long storyId, Long chapterId, UUID requestId,
                                                UUID conversationId, Long fence) {
        if (userId == null || storyId == null || chapterId == null || requestId == null || conversationId == null)
            throw new BusinessException(403, "SCOPE_DENIED");
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (run.getStory() == null || !storyId.equals(run.getStory().getId()) || run.getConversation() == null
                || !conversationId.equals(run.getConversation().getConversationUuid()))
            throw new BusinessException(403, "SCOPE_DENIED");
        // The database owner/fence/clock are authoritative; a JVM timestamp cannot grant execution.
        if (run.getStatus() != MangaAgentRunStatus.RUNNING || fence == null || fence <= 0
                || !fence.equals(run.getFencingToken()) || leaseService == null || !leaseService.owns(run, fence))
            throw new BusinessException(409, "RUN_NOT_ACTIVE");
        return run;
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRun> findRun(MangaAgentConversation conversation, UUID requestId) {
        return runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId);
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRun> findLatestOpenRun(Long userId, Long chapterId) {
        return runRepository.findByUserIdAndChapterIdAndStatusInOrderByUpdatedAtDesc(
                        userId,
                        chapterId,
                        OPEN_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRun> findLatestOpenRun(MangaAgentConversation conversation) {
        return runRepository.findByConversationIdAndStatusInOrderByUpdatedAtDesc(
                        conversation.getId(),
                        OPEN_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public MangaAgentRun requireWaitingRun(Long userId, Long chapterId, UUID requestId) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "No waiting agent run found"));
        if (run.getStatus() != MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(404, "No waiting agent run found");
        }
        return run;
    }

    @Transactional(readOnly = true)
    public MangaAgentRun requireWaitingRun(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRun run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "No waiting agent run found"));
        if (run.getStatus() != MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(404, "No waiting agent run found");
        }
        return run;
    }

    @Transactional(readOnly = true)
    public AgentUserInputRequest waitingInput(MangaAgentRun run) {
        return snapshotReader.waitingInput(run);
    }

    @Transactional(readOnly = true)
    public RoutingDecision routingDecision(MangaAgentRun run) {
        return snapshotReader.routingDecision(run);
    }

    @Transactional(readOnly = true)
    public ExecutionPlan executionPlan(MangaAgentRun run) {
        return snapshotReader.executionPlan(run);
    }

    @Transactional
    public void markWaiting(UUID requestId, Long userId, Long chapterId, AgentUserInputRequest request) {
        MangaAgentRun run = runRepository.findForUpdate(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        applyWaiting(run, request, null);
    }

    @Transactional
    public void markWaiting(MangaAgentConversation conversation, UUID requestId, AgentUserInputRequest request) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        applyWaiting(run, request, null);
    }

    @Transactional
    public void markWaiting(MangaAgentConversation conversation, UUID requestId, AgentUserInputRequest request, Long fencingToken) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        applyWaiting(run, request, fencingToken);
    }

    @Transactional
    public void markWaiting(MangaAgentConversation conversation, UUID requestId, AgentUserInputRequest request,
                            MangaWorkflowExecutionContext.ExecutionClaim claim) {
        lockAutomationExecution(conversation, requestId, claim);
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        var waitingClaim = objectMap(readMap(run.getRunAttributesJson()).get("waiting_execution_claim"));
        if (run.getStatus() == MangaAgentRunStatus.WAITING_USER
                && request != null && request.equals(waitingInput(run))) return;
        if (claim != null && claim.valid() && run.getStatus() == MangaAgentRunStatus.WAITING_USER
                && request != null && request.equals(waitingInput(run)) && Objects.equals(claim.fencingToken(), run.getFencingToken())
                && claim.owner().equals(waitingClaim.get("owner"))
                && waitingClaim.get("fencing_token") instanceof Number fence && fence.longValue() == claim.fencingToken()) return;
        requireClaim(run, claim);
        applyWaiting(run, request, claim.fencingToken());
    }

    private void applyWaiting(MangaAgentRun run, AgentUserInputRequest request, Long expectedFence) {
        if (request == null || (expectedFence != null && !expectedFence.equals(run.getFencingToken()))
                || !Set.of(MangaAgentRunStatus.RUNNING, MangaAgentRunStatus.WAITING_USER).contains(run.getStatus())) {
            throw new BusinessException(409, "原执行已结束或执行权已变化，不能重新等待答复");
        }
        var current = waitingInput(run);
        // The external bridge may already have committed the exact question while executing
        // the tool. A later continuation callback is an idempotent acknowledgement, not a
        // second transition that needs to revalidate an owner that was intentionally cleared.
        if (run.getStatus() == MangaAgentRunStatus.WAITING_USER && request.equals(current)) return;
        if (request.toolCallId() != null) {
            var questions = objectMap(readMap(run.getRunAttributesJson()).get("external_questions"));
            var question = objectMap(questions.get(request.toolCallId()));
            if (!request.equals(current) || !"WAITING".equals(question.get("status"))
                    || !(question.get("pending_version") instanceof Number version)
                    || !Objects.equals(request.pendingVersion(), version.longValue())
                    || !(question.get("fencing_token") instanceof Number fence)
                    || !Objects.equals(run.getFencingToken(), fence.longValue())) {
                throw new BusinessException(409, "原提问已消费或已被后续问题替换");
            }
        } else if (current != null && current.toolCallId() != null) {
            throw new BusinessException(409, "原生提问仍待答，不能用另一决策覆盖");
        }
        if (run.getStatus() == MangaAgentRunStatus.WAITING_USER) {
            if (request.equals(current)) return;
            throw new BusinessException(409, "已有另一项待办，请刷新后处理");
        }
        if (leaseService != null && !leaseService.owns(run, run.getFencingToken())) {
            throw new BusinessException(409, "提问执行权已失效");
        }
        run.setStatus(MangaAgentRunStatus.WAITING_USER);
        run.setUserInputRequestJson(toJson(request));
        if (run.getOwnerInstanceId() != null && run.getFencingToken() != null) {
            var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
            attributes.put("waiting_execution_claim", Map.of("owner", run.getOwnerInstanceId(), "fencing_token", run.getFencingToken()));
            run.setRunAttributesJson(toJson(attributes));
        }
        run.setOwnerInstanceId(null);
        run.setLeaseUntil(null);
        run.setUpdatedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    @Transactional
    public void markRunning(UUID requestId, Long userId, Long chapterId) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setUserInputRequestJson(null);
        run.setErrorMessage(null);
        run.setCurrentPhase("MODEL");
        run.setLastProgressAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    @Transactional
    public void markRunning(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRun run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setUserInputRequestJson(null);
        run.setErrorMessage(null);
        run.setCurrentPhase("MODEL");
        run.setLastProgressAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    @Transactional
    public void markSucceeded(UUID requestId, Long userId, Long chapterId, String reply) {
        markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.SUCCEEDED, reply, null);
    }

    @Transactional
    public void markSucceeded(MangaAgentConversation conversation, UUID requestId, String reply) {
        markTerminal(conversation, requestId, MangaAgentRunStatus.SUCCEEDED, reply, null);
    }

    @Transactional
    public void markSucceeded(MangaAgentConversation conversation, UUID requestId, String reply,
                              MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaAgentRun run = requireFinalizable(conversation, requestId, claim);
        applyTerminal(run, MangaAgentRunStatus.SUCCEEDED, reply, null);
    }

    /** Deterministic author cancellation checks the exact pending decision, without a model lease. */
    @Transactional
    public void requireWaitingDecision(MangaAgentConversation conversation, UUID requestId, AgentUserInputRequest expected) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (run.getStatus() != MangaAgentRunStatus.WAITING_USER || expected == null || !expected.equals(waitingInput(run))
                || !Set.of("MUTATION_CONFIRMATION", "CONTEXT_MISSING").contains(expected.purpose()))
            throw new BusinessException(409, "原决策已变化，请刷新任务后重试");
    }

    /**
     * Acquires the terminal-transition row lock before an assistant reply is
     * persisted. A cancellation that acquired the lock first remains
     * authoritative and prevents a late success reply from being stored.
     */
    @Transactional
    public MangaAgentRun requireFinalizable(MangaAgentConversation conversation, UUID requestId) {
        return requireFinalizable(conversation, requestId, null);
    }

    @Transactional
    public MangaAgentRun requireFinalizable(MangaAgentConversation conversation, UUID requestId,
                                             MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaAgentRun run = requireExecutionClaim(conversation, requestId, claim);
        if (waitingInput(run) != null) throw new BusinessException(409, "Agent run has pending user input");
        return run;
    }

    @Transactional
    public MangaAgentRun requireExecutionClaim(MangaAgentConversation conversation, UUID requestId,
                                                MangaWorkflowExecutionContext.ExecutionClaim claim) {
        lockAutomationExecution(conversation, requestId, claim);
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        requireClaim(run, claim);
        return run;
    }

    /** Persisting a waiting-state assistant message does not reacquire the released execution lease. */
    @Transactional
    public MangaAgentRun requireWaitingExecutionClaim(MangaAgentConversation conversation, UUID requestId,
                                                      MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        var waitingClaim = objectMap(readMap(run.getRunAttributesJson()).get("waiting_execution_claim"));
        if (claim == null || !claim.valid() || run.getStatus() != MangaAgentRunStatus.WAITING_USER
                || waitingInput(run) == null
                || !Objects.equals(run.getFencingToken(), claim.fencingToken())
                || !(waitingClaim.get("fencing_token") instanceof Number fence)
                || fence.longValue() != claim.fencingToken()
                || !Objects.equals(waitingClaim.get("owner"), claim.owner())) {
            throw new BusinessException(409, "WAITING_EXECUTION_CLAIM_DENIED");
        }
        return run;
    }

    /**
     * Persists verified business facts and terminal status in the transaction
     * opened by ResultFinalizer together with the assistant message.
     */
    @Transactional
    public MangaAgentRun completeVerified(MangaAgentConversation conversation, UUID requestId,
                                          String reply, boolean degraded, String resultSchema,
                                          Map<String, Object> verifiedFacts) {
        return completeVerified(conversation, requestId, reply, degraded, resultSchema, verifiedFacts, null);
    }

    @Transactional
    public MangaAgentRun completeVerified(MangaAgentConversation conversation, UUID requestId,
                                          String reply, boolean degraded, String resultSchema,
                                          Map<String, Object> verifiedFacts, MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaAgentRun run = requireFinalizable(conversation, requestId, claim);
        OffsetDateTime now = OffsetDateTime.now();
        run.setResultSchema(resultSchema);
        run.setVerifiedResultJson(toJson(verifiedFacts == null ? Map.of() : verifiedFacts));
        run.setVerifiedAt(now);
        MangaAgentRunStatus status = degraded ? MangaAgentRunStatus.DEGRADED : MangaAgentRunStatus.SUCCEEDED;
        MangaAgentRun completed = applyTerminal(run, status, reply,
                degraded ? "Agent final response degraded after a verified mutation" : null);
        appendEvent(completed, "result_verified", Map.of(
                "type", "result_verified",
                "phase", "result",
                "status", status.name(),
                "resultSchema", resultSchema == null ? "" : resultSchema,
                "facts", verifiedFacts == null ? Map.of() : verifiedFacts
        ));
        return completed;
    }

    /**
     * Closes the short window where the assistant reply is stored before the run becomes terminal.
     * The row lock makes a concurrent cancellation authoritative when it wins the transition first.
     */
    @Transactional
    public Optional<MangaAgentRun> reconcileCachedReply(MangaAgentConversation conversation, UUID requestId,
                                                        String reply) {
        // A cached message is not evidence that the current continuation completed.
        return runRepository.findForUpdate(conversation.getId(), requestId);
    }

    @Transactional
    public void markDegraded(UUID requestId, Long userId, Long chapterId, String reply, String error) {
        markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.DEGRADED, reply, error);
    }

    @Transactional
    public void markDegraded(MangaAgentConversation conversation, UUID requestId, String reply, String error) {
        markTerminal(conversation, requestId, MangaAgentRunStatus.DEGRADED, reply, error);
    }

    @Transactional
    public void markFailed(UUID requestId, Long userId, Long chapterId, String error) {
        markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.FAILED, null, error);
    }

    @Transactional
    public void markFailed(MangaAgentConversation conversation, UUID requestId, String error) {
        markTerminal(conversation, requestId, MangaAgentRunStatus.FAILED, null, error);
    }

    @Transactional
    public void markFailed(MangaAgentConversation conversation, UUID requestId, String error,
                           MangaWorkflowExecutionContext.ExecutionClaim claim) {
        lockAutomationExecution(conversation, requestId, claim);
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        requireClaim(run, claim);
        applyTerminal(run, MangaAgentRunStatus.FAILED, null, error);
    }

    private void lockRequestAutomation(AgentRunRequest request) {
        var runtime = new com.artverse.agent.gateway.AgentScopeRuntimeContextFactory().create(request)
                .get(com.artverse.agent.MangaAgentRuntimeContext.class);
        if (runtime == null) throw new BusinessException(403, "SCOPE_DENIED");
        lockAutomationExecution(runtime);
    }

    @Transactional
    public MangaAgentRun cancel(UUID requestId, Long userId, Long chapterId, String reason) {
        return markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.CANCELLED, null,
                reason == null || reason.isBlank() ? "Agent run cancelled by user" : reason);
    }

    @Transactional
    public MangaAgentRun cancel(MangaAgentConversation conversation, UUID requestId, String reason) {
        return markTerminal(conversation, requestId, MangaAgentRunStatus.CANCELLED, null,
                reason == null || reason.isBlank() ? "Agent run cancelled by user" : reason);
    }

    @Transactional
    public MangaAgentRun markInterrupted(UUID requestId, Long userId, Long chapterId, String reason) {
        return markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.INTERRUPTED, null,
                reason == null || reason.isBlank() ? "Agent run interrupted" : reason);
    }

    @Transactional
    public int interruptStalledRunningRuns(OffsetDateTime modelStaleBefore, OffsetDateTime toolStaleBefore) {
        OffsetDateTime candidateBefore = modelStaleBefore.isAfter(toolStaleBefore)
                ? modelStaleBefore
                : toolStaleBefore;
        List<Long> candidates = runRepository.findStalledCandidateIds(MangaAgentRunStatus.RUNNING, candidateBefore);
        OffsetDateTime now = OffsetDateTime.now();
        int interrupted = 0;
        for (Long id : candidates) {
            MangaAgentRun run = runRepository.findForUpdateById(id).orElse(null);
            if (run == null || run.getStatus() != MangaAgentRunStatus.RUNNING || run.getLastProgressAt() == null) continue;
            OffsetDateTime cutoff = "TOOL".equals(run.getCurrentPhase()) ? toolStaleBefore : modelStaleBefore;
            if (!run.getLastProgressAt().isBefore(cutoff)) continue;
            run.setStatus(MangaAgentRunStatus.INTERRUPTED);
            run.setErrorMessage("Agent run interrupted because no real progress was recorded in "
                    + run.getCurrentPhase() + " phase");
            run.setUserInputRequestJson(null);
            run.setCompletedAt(now);
            run.setUpdatedAt(now);
            runRepository.save(run);
            interrupted++;
        }
        return interrupted;
    }

    @Transactional
    public void recordProgress(MangaAgentRun run, String phase) {
        if (automationClaimJdbc != null) {
            int updated = automationClaimJdbc.update("""
                    UPDATE manga_agent_runs
                    SET current_phase=?, last_progress_at=clock_timestamp(), updated_at=clock_timestamp()
                    WHERE id=?
                    """, normalizePhase(phase), run.getId());
            if (updated == 1) return;
            throw new BusinessException(404, "Agent run not found");
        }
        MangaAgentRun attachedRun = runRepository.getReferenceById(run.getId());
        attachedRun.setCurrentPhase(normalizePhase(phase));
        attachedRun.setLastProgressAt(OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public boolean isTerminal(UUID requestId, Long userId, Long chapterId) {
        return runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .map(run -> isTerminal(run.getStatus()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isTerminal(MangaAgentConversation conversation, UUID requestId) {
        return runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .map(run -> isTerminal(run.getStatus()))
                .orElse(false);
    }

    @Transactional
    public Long appendEvent(MangaAgentRun run, String eventName, Map<String, Object> payload) {
        MangaAgentRun attachedRun = runRepository.getReferenceById(run.getId());
        if (attachedRun == null) {
            attachedRun = run;
        }
        MangaAgentRunEventRecord event = new MangaAgentRunEventRecord();
        event.setRun(attachedRun);
        event.setEventName(eventName);
        event.setEventType(asString(payload.get("type")));
        event.setPhase(asString(payload.get("phase")));
        event.setLabel(asString(payload.get("label")));
        event.setStatus(asString(payload.get("status")));
        event.setPayloadJson(toJson(payload));
        eventRepository.save(event);
        // 仅递增 updated_at，禁止整行回写：流式事件与本行事务存在并发，
        // 整行 UPDATE 会用旧快照覆盖 waiting 状态或执行权字段。
        runRepository.touchUpdatedAt(run.getId(), OffsetDateTime.now());
        return event.getId();
    }

    @Transactional
    public void appendRunEvent(MangaAgentRun run, AgentRunEvent event) {
        appendEvent(run, "run_event", toPayload(event));
    }

    @Transactional
    public void appendToolAuditEvent(Long userId, Long chapterId, UUID requestId, AgentRunToolStatus.ToolEvent event) {
        if (event == null || requestId == null) {
            return;
        }
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .ifPresent(run -> appendEvent(run, "tool", toolAuditPayload(run, event)));
    }

    @Transactional
    public void appendToolContractEvent(MangaAgentConversation conversation, UUID requestId,
                                        Map<String, Object> payload) {
        if (conversation == null || requestId == null || payload == null || payload.isEmpty()) {
            return;
        }
        runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .ifPresent(run -> appendEvent(run, "tool_contract", payload));
    }

    @Transactional
    public void mergeRunAttributes(MangaAgentConversation conversation, UUID requestId,
                                   Map<String, Object> attributes) {
        if (conversation == null || requestId == null || attributes == null || attributes.isEmpty()) {
            return;
        }
        runRepository.findForUpdate(conversation.getId(), requestId)
                .ifPresent(run -> {
                    Map<String, Object> merged = deepMerge(readMap(run.getRunAttributesJson()), attributes);
                    run.setRunAttributesJson(toJson(merged));
                    runRepository.save(run);
                });
    }

    @Transactional(readOnly = true)
    public RunSnapshot snapshot(MangaAgentRun run) {
        return snapshotReader.snapshot(run);
    }

    @Transactional(readOnly = true)
    public RunEventReplayPage replayEvents(Long userId, Long chapterId, UUID requestId, long afterEventId) {
        return snapshotReader.replayEvents(userId, chapterId, requestId, afterEventId);
    }

    @Transactional(readOnly = true)
    public ResumeContext resumeContext(MangaAgentRun run) {
        return snapshotReader.resumeContext(run);
    }

    public Map<String, Object> toPayload(AgentRunEvent event) {
        return snapshotReader.toPayload(event);
    }

    private MangaAgentRun markTerminal(UUID requestId, Long userId, Long chapterId, MangaAgentRunStatus status,
                                       String reply, String error) {
        MangaAgentRun run = runRepository.findForUpdate(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (isTerminal(run.getStatus())) {
            return run;
        }
        return applyTerminal(run, status, reply, error);
    }

    private MangaAgentRun markTerminal(MangaAgentConversation conversation, UUID requestId, MangaAgentRunStatus status,
                                       String reply, String error) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (isTerminal(run.getStatus())) {
            return run;
        }
        return applyTerminal(run, status, reply, error);
    }

    private MangaAgentRun applyTerminal(MangaAgentRun run, MangaAgentRunStatus status, String reply, String error) {
        if (status == MangaAgentRunStatus.FAILED) {
            var attributes = new LinkedHashMap<>(readMap(run.getRunAttributesJson()));
            if (attributes.containsKey("review_summary_continuation") && "PENDING".equals(attributes.get("review_summary_status"))) {
                attributes.put("review_summary_status", "FAILED");
                run.setRunAttributesJson(toJson(attributes));
            }
        }
        run.setStatus(status);
        run.setFinalReply(reply);
        run.setErrorMessage(error);
        run.setUserInputRequestJson(null);
        run.setOwnerInstanceId(null);
        run.setLeaseUntil(null);
        run.setCompletedAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        return runRepository.save(run);
    }

    private boolean isTerminal(MangaAgentRunStatus status) {
        return status == MangaAgentRunStatus.SUCCEEDED
                || status == MangaAgentRunStatus.DEGRADED
                || status == MangaAgentRunStatus.FAILED
                || status == MangaAgentRunStatus.CANCELLED
                || status == MangaAgentRunStatus.INTERRUPTED;
    }

    private String normalizePhase(String phase) {
        if (phase == null) {
            return "MODEL";
        }
        return switch (phase.toUpperCase()) {
            case "ROUTER", "AGENT", "TOOL", "MODEL", "PREPARING_CONTEXT", "BUILDING_REQUEST",
                    "WAITING_MODEL", "GENERATING" -> phase.toUpperCase();
            default -> "MODEL";
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize manga agent run payload", e);
        }
    }

    private Map<String, Object> readMap(String value) {
        return snapshotReader.readMap(value);
    }

    private RunContextSnapshot readContextSnapshot(String value) {
        return snapshotReader.readContextSnapshot(value);
    }

    private RunContextSnapshot withKnowledgeRecallHash(RunContextSnapshot current, String knowledgeRecallHash) {
        return snapshotReader.withKnowledgeRecallHash(current, knowledgeRecallHash);
    }

    private Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> updates) {
        return snapshotReader.deepMerge(base, updates);
    }

    private Map<String, Object> toolAuditPayload(MangaAgentRun run, AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_audit");
        payload.put("phase", "tool");
        payload.put("label", event.toolName());
        payload.put("runId", run.getId());
        payload.put("requestId", event.requestId() == null ? run.getRequestId() : event.requestId());
        if (event.stepId() != null && !event.stepId().isBlank()) {
            payload.put("stepId", event.stepId());
        }
        payload.put("tool", event.toolName());
        payload.put("toolName", event.toolName());
        payload.put("status", event.status());
        payload.put("succeeded", event.succeeded());
        payload.put("durationMs", event.durationMs());
        if (event.resultHash() != null && !event.resultHash().isBlank()) {
            payload.put("resultHash", event.resultHash());
        }
        if (event.auditId() != null && !event.auditId().isBlank()) {
            payload.put("auditId", event.auditId());
        }
        if (event.error() != null && !event.error().isBlank()) {
            payload.put("error", event.error());
        }
        payload.put("result", StoryChatToolAuditSummary.project(event.toolName(), event.result()));
        if (event.createdAt() != null) {
            payload.put("createdAt", event.createdAt().toString());
        }
        return Map.copyOf(payload);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
