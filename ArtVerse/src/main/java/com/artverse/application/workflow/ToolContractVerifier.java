package com.artverse.application.workflow;

import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.MangaAgentRunService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ToolContractVerifier {

    private final MangaAgentRunService runService;

    public ToolContractVerifier(MangaAgentRunService runService) {
        this.runService = runService;
    }

    public MangaWorkflowResult verify(MangaWorkflowExecutionContext context,
                                      MangaWorkflowRoute route,
                                      MangaWorkflowResult result) {
        ToolExpectation expectation = ToolExpectation.forRoute(context, route);
        VerificationOutcome outcome = verify(expectation, context.toolState().eventsForStep(expectation.stepId()), result);
        persist(context, outcome);
        if (outcome.status() == VerificationStatus.FAILED) {
            throw new ToolContractViolationException(outcome.summary());
        }
        if (outcome.status() == VerificationStatus.DEGRADED) {
            return result.degradedWithAttributes(outcome.attributes());
        }
        return result.withAttributes(outcome.attributes());
    }

    VerificationOutcome verify(ToolExpectation expectation,
                               List<AgentRunToolStatus.ToolEvent> events,
                               MangaWorkflowResult result) {
        List<AgentRunToolStatus.ToolEvent> stepEvents = events == null ? List.of() : List.copyOf(events);
        List<String> reasons = new java.util.ArrayList<>();
        VerificationStatus status = VerificationStatus.PASSED;

        for (String forbidden : expectation.forbiddenTools()) {
            if (stepEvents.stream().anyMatch(event -> forbidden.equals(event.toolName()))) {
                reasons.add("forbidden_tool:" + forbidden);
                status = VerificationStatus.FAILED;
            }
        }

        for (Map.Entry<String, Integer> entry : expectation.maxCalls().entrySet()) {
            long count = "commit_storyboard".equals(entry.getKey())
                    ? stepEvents.stream().filter(event -> "commit_storyboard".equals(event.toolName()) && event.succeeded())
                        .map(event -> binding(event)).distinct().count()
                    : stepEvents.stream().filter(event -> entry.getKey().equals(event.toolName())).count();
            if (count > entry.getValue()) {
                reasons.add("tool_called_too_many_times:" + entry.getKey() + ":" + count);
                status = VerificationStatus.FAILED;
            }
        }

        if (expectation.route() == MangaWorkflowRoute.STORYBOARD) {
            int firstCommit = firstSuccessfulCommit(stepEvents, expectation.stepId());
            boolean hasCommit = firstCommit >= 0;
            boolean hasValidDraft = hasCommit && hasValidDraft(stepEvents, firstCommit, stepEvents.get(firstCommit));
            if (!hasValidDraft) {
                reasons.add("missing_valid_draft");
                status = VerificationStatus.FAILED;
            }
            if (!hasCommit) {
                reasons.add("missing_required_tool:commit_storyboard");
                status = VerificationStatus.FAILED;
            }
        } else {
            for (String required : expectation.requiredTools()) {
                boolean present = stepEvents.stream().anyMatch(event -> required.equals(event.toolName()) && event.succeeded());
                if (!present) {
                    reasons.add("missing_required_tool:" + required);
                    status = VerificationStatus.FAILED;
                }
            }
        }

        List<AgentRunToolStatus.ToolEvent> failedEvents = stepEvents.stream()
                .filter(event -> !event.succeeded())
                .toList();
        if (!failedEvents.isEmpty() && !result.degraded()) {
            String failedTools = failedEvents.stream()
                    .map(AgentRunToolStatus.ToolEvent::toolName)
                    .distinct()
                    .collect(Collectors.joining(","));
            reasons.add("tool_failure_masked_by_success:" + failedTools);
            if (expectation.route().isMutating()) {
                status = VerificationStatus.FAILED;
            } else if (status != VerificationStatus.FAILED) {
                status = VerificationStatus.DEGRADED;
            }
        }

        Map<String, Object> attributes = attributes(expectation, stepEvents, reasons, status);
        return new VerificationOutcome(expectation, status, reasons, attributes);
    }

    private void persist(MangaWorkflowExecutionContext context, VerificationOutcome outcome) {
        runService.mergeRunAttributes(context.conversation(), context.requestId(), outcome.attributes());
        runService.appendToolContractEvent(context.conversation(), context.requestId(), contractPayload(outcome));
    }

    private Map<String, Object> attributes(ToolExpectation expectation,
                                           List<AgentRunToolStatus.ToolEvent> events,
                                           List<String> reasons,
                                           VerificationStatus status) {
        Map<String, Object> stepDetails = new LinkedHashMap<>();
        stepDetails.put("route", expectation.route().name());
        stepDetails.put("status", status.name());
        stepDetails.put("policy", expectation.policy().name());
        stepDetails.put("requiredTools", expectation.requiredToolsInOrder());
        stepDetails.put("forbiddenTools", expectation.forbiddenTools());
        stepDetails.put("maxCalls", expectation.maxCalls());
        stepDetails.put("observedTools", events.stream().map(AgentRunToolStatus.ToolEvent::toolName).toList());
        stepDetails.put("reasons", List.copyOf(reasons));

        Map<String, Object> steps = Map.of(expectation.stepId(), stepDetails);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tool_contract_status", status.name());
        attributes.put("tool_contract_last_step_id", expectation.stepId());
        attributes.put("tool_contract_last_route", expectation.route().name());
        attributes.put("tool_contract_steps", steps);
        if (!reasons.isEmpty()) {
            attributes.put("tool_contract_reasons", List.copyOf(reasons));
        }
        return Map.copyOf(attributes);
    }

    private Map<String, Object> contractPayload(VerificationOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_contract_verification");
        payload.put("phase", "tool");
        payload.put("label", outcome.expectation().route().name());
        payload.put("status", outcome.status().name());
        payload.put("stepId", outcome.expectation().stepId());
        payload.put("route", outcome.expectation().route().name());
        payload.put("policy", outcome.expectation().policy().name());
        payload.put("reasons", List.copyOf(outcome.reasons()));
        payload.put("requiredTools", outcome.expectation().requiredToolsInOrder());
        return Map.copyOf(payload);
    }

    private boolean hasValidDraft(List<AgentRunToolStatus.ToolEvent> events, int beforeIndexExclusive,
                                  AgentRunToolStatus.ToolEvent commit) {
        for (int index = 0; index < Math.min(events.size(), beforeIndexExclusive); index++) {
            AgentRunToolStatus.ToolEvent event = events.get(index);
            if (!"draft_structured_storyboard".equals(event.toolName()) || !event.succeeded()
                    || !java.util.Objects.equals(event.requestId(), commit.requestId())
                    || !java.util.Objects.equals(event.stepId(), commit.stepId())) {
                continue;
            }
            if (Boolean.TRUE.equals(data(event).get("validated")) && binding(event) != null
                    && binding(event).equals(binding(commit))) {
                return true;
            }
        }
        return false;
    }

    private int firstSuccessfulCommit(List<AgentRunToolStatus.ToolEvent> events, String stepId) {
        for (int index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if ("commit_storyboard".equals(event.toolName()) && event.succeeded()
                    && stepId.equals(event.stepId()) && binding(event) != null
                    && Boolean.TRUE.equals(data(event).get("saved"))
                    && "COMMITTED".equals(data(event).get("artifact_status"))) {
                return index;
            }
        }
        return -1;
    }

    private List<String> binding(AgentRunToolStatus.ToolEvent event) {
        Map<?,?> data = data(event);
        List<String> fields = List.of("artifact_id", "content_hash", "base_version", "request_id", "step_id");
        if (fields.stream().anyMatch(key -> data.get(key) == null || data.get(key).toString().isBlank())) return null;
        if (event.requestId() == null || !event.requestId().toString().equals(data.get("request_id").toString())
                || !java.util.Objects.equals(event.stepId(), data.get("step_id"))) return null;
        return fields.stream().map(key -> data.get(key).toString()).toList();
    }

    private Map<?,?> data(AgentRunToolStatus.ToolEvent event) {
        return event.result().get("data") instanceof Map<?,?> data ? data : event.result();
    }

    enum VerificationStatus {
        PASSED,
        DEGRADED,
        FAILED
    }

    record VerificationOutcome(ToolExpectation expectation,
                               VerificationStatus status,
                               List<String> reasons,
                               Map<String, Object> attributes) {
        String summary() {
            if (reasons.isEmpty()) {
                return "Tool contract verification failed";
            }
            return "Tool contract verification failed for step " + expectation.stepId()
                    + ": " + String.join(", ", reasons);
        }
    }
}
