package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentRunEvent;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.ExecutionPlan;
import com.artverse.application.workflow.ExecutionPlanCompiler;
import com.artverse.application.workflow.ExecutionStep;
import com.artverse.application.workflow.ExecutionPlanValidator;
import com.artverse.application.workflow.MangaWorkflowContextSnapshot;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNode;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowNodeRegistry;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.application.workflow.RoutingDecision;
import com.artverse.application.workflow.ToolContractViolationException;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-step orchestration agent. Only invoked when the Router detects more
 * than one suggested step (e.g. &quot;rewrite storyboard then review consistency&quot;).
 *
 * <p>The Director does NOT call an LLM itself — it reads the
 * {@link RoutingDecision#suggestedSteps()}, validates the plan, then
 * serially dispatches each step to the registered {@link MangaWorkflowNodeHandler}.
 * A final summary is generated from step outputs.</p>
 */
@Slf4j
@Component
public class MangaDirectorAgentNode implements MangaWorkflowNodeHandler {

    private final MangaWorkflowNodeRegistry nodeRegistry;
    private final MangaAgentRunService runService;
    private final ObjectMapper objectMapper;
    private final MangaAgentExecutionSupport support;
    private final ExecutionPlanValidator planValidator;
    private final ExecutionPlanCompiler planCompiler;

    @Autowired
    public MangaDirectorAgentNode(@Lazy MangaWorkflowNodeRegistry nodeRegistry,
                                   MangaAgentRunService runService,
                                   ObjectMapper objectMapper,
                                   MangaAgentExecutionSupport support,
                                   ExecutionPlanValidator planValidator,
                                   ExecutionPlanCompiler planCompiler) {
        this.nodeRegistry = nodeRegistry;
        this.runService = runService;
        this.objectMapper = objectMapper;
        this.support = support;
        this.planValidator = planValidator;
        this.planCompiler = planCompiler;
    }

    public MangaDirectorAgentNode(@Lazy MangaWorkflowNodeRegistry nodeRegistry,
                                   MangaAgentRunService runService,
                                   ObjectMapper objectMapper,
                                   MangaAgentExecutionSupport support,
                                   ExecutionPlanValidator planValidator) {
        this(nodeRegistry, runService, objectMapper, support, planValidator,
                new ExecutionPlanCompiler(planValidator));
    }

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.DIRECTOR;
    }

    @Override
    public MangaWorkflowResult run(MangaWorkflowExecutionContext context) {
        support.saveUserMessage(context);
        List<MangaWorkflowRoute> suggested = suggestedRoutes(context);
        if (isSingleStep(suggested)) {
            return saveFinalReply(context, delegateTo(suggested).run(internalContext(context, context.message())));
        }
        ExecutionPlan plan = restoreOrBuildPlan(context, suggested, readRoutingDecision(context).requiredCapabilities());
        persistPlan(context, plan);
        return saveFinalReply(context, executePlan(context, plan));
    }

    @Override
    public MangaWorkflowResult stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
        support.saveUserMessage(context);
        List<MangaWorkflowRoute> suggested = suggestedRoutes(context);
        if (isSingleStep(suggested)) {
            MangaWorkflowResult result = delegateTo(suggested).stream(
                    internalContext(context, context.message()), streamContext);
            return saveFinalReply(context, result);
        }

        ExecutionPlan plan = restoreOrBuildPlan(context, suggested, readRoutingDecision(context).requiredCapabilities());
        persistPlan(context, plan);

        int completedSteps = 0;
        boolean childDegraded = false;
        String previousOutput = context.message();

        for (ExecutionStep step : plan.steps()) {
            if ("COMPLETED".equals(step.status())) {
                completedSteps++;
                previousOutput = step.handoffContext() == null ? step.outputSummary() : step.handoffContext();
                continue;
            }
            streamContext.sendRunEvent(AgentRunEvent.step(
                    "workflow_step_started",
                    "running",
                    "Step " + (step.sequence() + 1) + "/" + plan.stepCount() + ": " + step.description(),
                    Map.of("step", step.sequence(), "route", step.route().name(), "mutating", step.mutating())
            ));

            String stepInput = previousOutput;
            step.markRunning(summarizeResult(stepInput));
            persistPlan(context, plan);
            MangaWorkflowExecutionContext stepContext = contextForStep(context, plan.planId(), step, stepInput);

            try {
                MangaWorkflowResult result = nodeRegistry.handlerFor(step.route()).stream(
                        stepContext, streamContext.forStep(plan.planId(), step.sequence(), step.route()));
                step.markCompleted(summarizeResult(result.stepSummary()), truncateHandoff(result.handoffContext()));
                persistPlan(context, plan);
                completedSteps++;
                childDegraded |= result.degraded();
                previousOutput = result.handoffContext();
            } catch (AgentUserInputRequiredException e) {
                persistPlan(context, plan);
                throw e;
            } catch (Exception e) {
                if (step.mutating() || e instanceof ToolContractViolationException) {
                    step.markFailed(e.getMessage());
                    persistPlan(context, plan);
                    throw planFailure(plan, completedSteps, step, e.getMessage());
                }
                step.markSkipped(e.getMessage());
                persistPlan(context, plan);
            }

            streamContext.sendRunEvent(AgentRunEvent.step(
                    "workflow_step_finished",
                    "completed",
                    "Step " + (step.sequence() + 1) + " " + step.status().toLowerCase(),
                    Map.of("step", step.sequence(), "status", step.status(), "summary",
                            step.outputSummary() == null ? "" : step.outputSummary())
            ));
        }

        return saveFinalReply(context, summarizeResults(plan, completedSteps, childDegraded));
    }

    // ---- Plan construction & validation ----

    private RoutingDecision readRoutingDecision(MangaWorkflowExecutionContext context) {
        RoutingDecision decision = runService.findRun(
                        context.user().getId(), context.chapter().getId(), context.requestId())
                .map(runService::routingDecision)
                .orElse(null);
        if (decision != null) {
            return decision;
        }
        log.warn("No routing decision found for requestId={}; using DIRECTOR default", context.requestId());
        return RoutingDecision.fixed(MangaWorkflowRoute.DIRECTOR, "missing_decision");
    }

    private List<MangaWorkflowRoute> suggestedRoutes(MangaWorkflowExecutionContext context) {
        RoutingDecision decision = readRoutingDecision(context);
        return extractSuggestedSteps(decision);
    }

    private static boolean isSingleStep(List<MangaWorkflowRoute> suggested) {
        return suggested.size() <= 1;
    }

    private MangaWorkflowRoute resolveSingleRoute(List<MangaWorkflowRoute> suggested) {
        if (suggested.isEmpty() || suggested.get(0) == MangaWorkflowRoute.DIRECTOR) {
            return MangaWorkflowRoute.CONVERSATION;
        }
        return suggested.get(0);
    }

    private MangaWorkflowNodeHandler delegateTo(List<MangaWorkflowRoute> suggested) {
        return nodeRegistry.handlerFor(resolveSingleRoute(suggested));
    }

    private List<MangaWorkflowRoute> extractSuggestedSteps(RoutingDecision decision) {
        List<MangaWorkflowRoute> steps = decision.suggestedSteps();
        if (steps == null || steps.isEmpty()) {
            return List.of(decision.route());
        }
        return steps;
    }

    private ExecutionPlan buildAndValidatePlan(RoutingDecision decision) {
        return planCompiler.compile(decision, this::hasRegisteredHandler);
    }

    private ExecutionPlan restoreOrBuildPlan(MangaWorkflowExecutionContext context,
                                              List<MangaWorkflowRoute> suggested,
                                              java.util.Set<com.artverse.application.workflow.MangaWorkflowCapability> requiredCapabilities) {
        planValidator.requireValid(suggested, requiredCapabilities);
        ExecutionPlan restored = runService.findRun(
                        context.user().getId(), context.chapter().getId(), context.requestId())
                .map(runService::executionPlan)
                .orElse(null);
        if (restored != null
                && restored.steps().stream().map(ExecutionStep::route).toList().equals(suggested)) {
            if (context.answerResume() != null) {
                var original = restored.steps().stream().filter(step ->
                        (restored.planId() + ":" + step.sequence()).equals(context.answerResume().stepId())).findFirst()
                        .orElseThrow(() -> new BusinessException(409, "原提问节点与当前计划不一致"));
                if ("COMPLETED".equals(original.status())) {
                    runService.requireCompletedAnswerStep(context);
                    context.answerResume().turn().close();
                } else {
                    var current = restored.steps().stream().filter(step -> !"COMPLETED".equals(step.status())).findFirst()
                            .orElseThrow(() -> new BusinessException(409, "原计划已经完成，不能重新投递答复"));
                    if (current != original || !"RUNNING".equals(current.status()))
                        throw new BusinessException(409, "原提问节点与当前计划不一致");
                }
            }
            return restored;
        }
        if (context.answerResume() != null) throw new BusinessException(409, "原提问计划缺失或已变化，已有产物保留");
        RoutingDecision decision = readRoutingDecision(context);
        return buildAndValidatePlan(decision);
    }

    private boolean hasRegisteredHandler(MangaWorkflowRoute route) {
        try {
            nodeRegistry.handlerFor(route);
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private void persistPlan(MangaWorkflowExecutionContext context, ExecutionPlan plan) {
        try {
            String json = objectMapper.writeValueAsString(plan);
            runService.updateExecutionPlan(context, json);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "Failed to persist Director execution plan");
        }
    }

    // ---- Plan execution ----

    private MangaWorkflowResult executePlan(MangaWorkflowExecutionContext context, ExecutionPlan plan) {
        int completedSteps = 0;
        boolean childDegraded = false;
        String previousOutput = context.message();

        for (ExecutionStep step : plan.steps()) {
            if ("COMPLETED".equals(step.status())) {
                completedSteps++;
                previousOutput = step.handoffContext() == null ? step.outputSummary() : step.handoffContext();
                continue;
            }
            String stepInput = previousOutput;
            step.markRunning(summarizeResult(stepInput));
            persistPlan(context, plan);
            MangaWorkflowExecutionContext stepContext = contextForStep(context, plan.planId(), step, stepInput);

            try {
                MangaWorkflowResult result = nodeRegistry.handlerFor(step.route()).run(stepContext);
                step.markCompleted(summarizeResult(result.stepSummary()), truncateHandoff(result.handoffContext()));
                persistPlan(context, plan);
                completedSteps++;
                childDegraded |= result.degraded();
                previousOutput = result.handoffContext();
            } catch (AgentUserInputRequiredException e) {
                persistPlan(context, plan);
                throw e;
            } catch (Exception e) {
                if (step.mutating() || e instanceof ToolContractViolationException) {
                    step.markFailed(e.getMessage());
                    persistPlan(context, plan);
                    throw planFailure(plan, completedSteps, step, e.getMessage());
                }
                step.markSkipped(e.getMessage());
                persistPlan(context, plan);
                log.warn("Read-only step {} ({}) failed, skipping: {}", step.sequence(), step.route(), e.getMessage());
            }
        }

        return summarizeResults(plan, completedSteps, childDegraded);
    }

    private MangaWorkflowExecutionContext contextForStep(MangaWorkflowExecutionContext original,
                                                          String planId, ExecutionStep step, String stepInput) {
        String enrichedMessage = original.message();
        if (stepInput != null && !stepInput.isBlank() && !stepInput.equals(original.message())) {
            enrichedMessage = original.message() + "\n\n[上一步上下文]" + stepInput;
        }
        return internalContext(original, enrichedMessage, planId + ":" + step.sequence());
    }

    private MangaWorkflowExecutionContext internalContext(MangaWorkflowExecutionContext original,
                                                           String message) {
        return internalContext(original, message, null);
    }

    private MangaWorkflowExecutionContext internalContext(MangaWorkflowExecutionContext original,
                                                           String message, String stepId) {
        return new MangaWorkflowExecutionContext(
                original.conversation(),
                message,
                original.requestId(),
                original.llmApiKey(),
                original.modelSpec(),
                original.toolState(),
                original.user(),
                original.chapter(),
                original.workflowContext(),
                false,
                stepId,
                original.executionClaim(),
                original.answerResume()
        );
    }

    // ---- Result handling ----

    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final int MAX_HANDOFF_LENGTH = 12_000;

    private String summarizeResult(String text) {
        return text == null || text.isBlank() ? "No output" : truncateResult(text);
    }

    private static String truncateResult(String s) {
        return s.length() > MAX_SUMMARY_LENGTH ? s.substring(0, MAX_SUMMARY_LENGTH - 3) + "..." : s;
    }

    private BusinessException planFailure(ExecutionPlan plan, int completedSteps,
                                          ExecutionStep failedStep, String error) {
        return new BusinessException(502, "Director plan failed at step " + (failedStep.sequence() + 1)
                + " (" + failedStep.route() + ") after " + completedSteps + "/" + plan.stepCount()
                + " completed steps: " + (error == null ? "unknown error" : error));
    }

    private static String truncateHandoff(String value) {
        if (value == null || value.length() <= MAX_HANDOFF_LENGTH) return value;
        return value.substring(0, MAX_HANDOFF_LENGTH);
    }

    private MangaWorkflowResult summarizeResults(ExecutionPlan plan, int completedSteps,
                                                  boolean childDegraded) {
        StringBuilder summary = new StringBuilder("计划执行完成（共" + plan.stepCount() + "步）：\n");
        long failedOrSkipped = plan.steps().stream()
                .filter(s -> "FAILED".equals(s.status()) || "SKIPPED".equals(s.status()))
                .count();

        for (ExecutionStep step : plan.steps()) {
            summary.append(step.sequence() + 1).append(". ")
                    .append(step.description()).append(" — ").append(step.status());
            if (step.outputSummary() != null && !step.outputSummary().isBlank()) {
                summary.append("（").append(summarizeResult(step.outputSummary())).append("）");
            }
            summary.append("\n");
        }

        String reply = summary.toString().trim();
        boolean degraded = childDegraded || failedOrSkipped > 0;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("director_plan_completed", true);
        attributes.put("total_steps", plan.stepCount());
        attributes.put("completed_steps", completedSteps);
        attributes.put("degraded", degraded);
        MangaWorkflowResult result = degraded
                ? MangaWorkflowResult.degraded(reply)
                : MangaWorkflowResult.success(reply);
        return result.withAttributes(attributes);
    }

    private MangaWorkflowResult saveFinalReply(MangaWorkflowExecutionContext context, MangaWorkflowResult result) {
        // The outer ResultFinalizer atomically persists the final assistant
        // reply and terminal run status after all step facts are verified.
        return result;
    }
}
