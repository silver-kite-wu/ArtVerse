package com.artverse.agent;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import java.util.List;
import java.util.UUID;

/** Server-created reference to one immutable accepted answer and its current delivery claim. */
public record AgentAnswerDelivery(UUID actionId, String callId, long pendingVersion,
                                  String answerHash, String canonicalResult, Long fencingToken) {
    public ToolResultBlock toolResult() {
        return ToolResultBlock.builder().id(callId).name("ask_user").state(ToolResultState.SUCCESS)
                .output(List.of(TextBlock.builder().text(canonicalResult).build())).build();
    }

    @Override public String toString() {
        return "AgentAnswerDelivery[actionId=" + actionId + ", pendingVersion=" + pendingVersion + "]";
    }
}
