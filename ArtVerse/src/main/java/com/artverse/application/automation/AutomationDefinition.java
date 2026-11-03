package com.artverse.application.automation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Persisted definition. Credentials are resolved by the executor, never part of this DTO. */
@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record AutomationDefinition(
        String name, String taskType, String executorKey, String instruction,
        Schedule schedule, JsonNode payload, Limits limits,
        String misfirePolicy, String overlapPolicy, Integer maxLatenessSeconds,
        Long expectedVersion, String origin, UUID sourceConversationId) {
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Schedule(String kind, String localDateTime, String localTime,
                           String timezone, List<Integer> weekdays) {}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Limits(Integer maxImagesPerRun, Integer maxModelCallsPerRun, Integer maxDurationSeconds) {}
}
