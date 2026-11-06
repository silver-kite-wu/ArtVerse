package com.artverse.application;

import java.util.List;
import java.util.Map;

@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record AgentUserInputRequest(
        String question,
        List<Option> options,
        boolean allowFreeText,
        String reason,
        String purpose,
        String toolCallId,
        Long pendingVersion
) {
    public AgentUserInputRequest(String question, List<Option> options, boolean allowFreeText, String reason, String purpose) {
        this(question, options, allowFreeText, reason, purpose, null, null);
    }
    public AgentUserInputRequest(String question, List<Option> options, boolean allowFreeText, String reason) {
        this(question, options, allowFreeText, reason, "BUSINESS_CONFIRMATION", null, null);
    }

    public AgentUserInputRequest {
        options = options == null ? List.of() : List.copyOf(options);
        purpose = purpose == null || purpose.isBlank() ? "BUSINESS_CONFIRMATION" : purpose;
    }

    public Map<String, Object> toEventData() {
        var result = new java.util.LinkedHashMap<String, Object>(Map.of(
                "question", question == null ? "" : question,
                "options", options,
                "allowFreeText", allowFreeText,
                "reason", reason == null ? "" : reason,
                "purpose", purpose
        ));
        if (toolCallId != null) result.put("toolCallId", toolCallId);
        if (pendingVersion != null) result.put("pendingVersion", pendingVersion);
        return java.util.Collections.unmodifiableMap(result);
    }

    public record Option(String id, String label, String description, boolean recommended) {
    }

    public String resolveAnswer(String answer, String selectedOptionId) {
        if ((answer == null) == (selectedOptionId == null)) {
            throw new com.artverse.common.BusinessException(400, "请提交一项选择或一份补充说明");
        }
        if (selectedOptionId != null) {
            return options.stream().filter(option -> option.id().equals(selectedOptionId)).findFirst()
                    .orElseThrow(() -> new com.artverse.common.BusinessException(400, "请选择原问题提供的选项")).label();
        }
        if (!allowFreeText || answer.isBlank() || answer.length() > 4000) {
            throw new com.artverse.common.BusinessException(400, allowFreeText
                    ? "请填写 1 至 4000 字的补充说明" : "此问题仅接受原选项");
        }
        return answer.trim();
    }
}
