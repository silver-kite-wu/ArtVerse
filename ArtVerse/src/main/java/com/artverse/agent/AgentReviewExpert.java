package com.artverse.agent;

import java.util.UUID;

/** Server-issued child identity; native task state remains in TaskRepository. */
public record AgentReviewExpert(String expertId, String taskId, UUID parentRequestId, String parentStepId) {}
