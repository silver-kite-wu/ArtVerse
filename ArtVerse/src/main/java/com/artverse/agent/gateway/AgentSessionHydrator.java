package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentAnswerDelivery;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.MessageUtils;
import com.artverse.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Avoids sending DB history again when AgentScope state is healthy, while
 * retaining the recent DB history as a deterministic recovery payload.
 */
@Slf4j
@Component
public class AgentSessionHydrator {

    private final AgentStateStore stateStore;

    public AgentSessionHydrator(AgentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public List<AgentMessage> messagesFor(AgentRunRequest request) {
        List<AgentMessage> messages = request.messages() == null ? List.of() : request.messages();
        if (!hasState(request)) {
            return messages;
        }
        int currentUserIndex = lastUserIndex(messages);
        if (currentUserIndex < 0) {
            return messages.stream()
                    .filter(message -> "system".equalsIgnoreCase(message.role()))
                    .toList();
        }
        List<AgentMessage> compact = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            if ("system".equalsIgnoreCase(message.role()) || index == currentUserIndex) {
                compact.add(message);
            }
        }
        return List.copyOf(compact);
    }

    boolean hasState(AgentRunRequest request) {
        try {
            return stateStore.exists(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request));
        } catch (Exception error) {
            log.warn("AgentState lookup failed; hydrating request {} from PostgreSQL history",
                    request.requestId());
            return false;
        }
    }

    /** Resume must inspect durable native state before Factory can create a fresh slot. */
    public void requireNoPending(AgentRunRequest request) {
        try {
            var state = stateStore.get(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request),
                    "agent_state", AgentState.class);
            if (state.isPresent() && !MessageUtils.pendingToolUseIds(state.get().getContext()).isEmpty()) {
                throw new BusinessException(409, "原工具调用仍待结果，请回复原问题后继续");
            }
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException(503, "暂时无法核对原生执行状态，请稍后重试");
        }
    }

    public void requirePendingResults(AgentRunRequest request, List<ToolResultBlock> results) {
        List<ToolUseBlock> pending = pendingCalls(request);
        if (results.isEmpty() || results.size() != pending.size()
                || results.stream().map(ToolResultBlock::getId).distinct().count() != results.size()) {
            throw new BusinessException(409, "原生待答与答复不匹配，请刷新任务状态");
        }
        for (var result : results) {
            if (pending.stream().noneMatch(call -> call.getId().equals(result.getId())
                    && call.getName().equals(result.getName()))) {
                throw new BusinessException(409, "原提问不存在或已消费，不能重新开始写作");
            }
        }
    }

    public List<ToolUseBlock> pendingCalls(AgentRunRequest request) {
        try {
            AgentState state = stateStore.get(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request),
                    "agent_state", AgentState.class).orElseThrow(() ->
                    new BusinessException(409, "原生执行状态缺失，已有产物保留，请查看审阅结果"));
            return MessageUtils.extractPendingToolCalls(state.getContext(), "artverse-" + request.taskType().sessionSuffix());
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException(503, "暂时无法核对原生执行状态，请稍后重试");
        }
    }

    public record AnswerState(boolean pending, long version, Msg finalResponse) { }

    /** Reads the authoritative checkpoint; in-memory events and synthesized errors are insufficient. */
    public AnswerState answerState(AgentRunRequest request, AgentAnswerDelivery delivery) {
        try {
            var saved = stateStore.getVersioned(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request),
                    "agent_state", AgentState.class);
            if (saved == null || saved.value() == null || saved.version() < 0)
                throw new BusinessException(409, "原生答复状态缺失，已受理答复及产物保留");
            List<Msg> context = saved.value().getContext();
            String agentName = "artverse-" + request.taskType().sessionSuffix();
            var pending = MessageUtils.extractPendingToolCalls(context, agentName);
            if (pending.stream().anyMatch(call -> delivery.callId().equals(call.getId()) && "ask_user".equals(call.getName()))) {
                if (pending.size() != 1) throw new BusinessException(409, "当前待答批次不支持可靠投递");
                return new AnswerState(true, saved.version(), null);
            }
            var results = context.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .filter(result -> delivery.callId().equals(result.getId())).toList();
            if (results.size() != 1 || !matchesAnswer(results.getFirst(), delivery))
                throw new BusinessException(409, "原生答复结果不一致或证据缺失，已有产物保留");
            Msg last = context.isEmpty() ? null : context.getLast();
            Msg finished = last != null && last.getRole() == MsgRole.ASSISTANT && agentName.equals(last.getName())
                    && last.getContentBlocks(ToolUseBlock.class).isEmpty() && !last.getTextContent().isBlank()
                    && pending.isEmpty() ? last : null;
            return new AnswerState(false, saved.version(), finished);
        } catch (BusinessException error) { throw error; }
        catch (RuntimeException error) { throw new BusinessException(503, "暂时无法核对原生答复状态，请稍后重试"); }
    }

    private boolean matchesAnswer(ToolResultBlock result, AgentAnswerDelivery delivery) {
        return "ask_user".equals(result.getName()) && result.getState() == ToolResultState.SUCCESS
                && result.getOutput().size() == 1 && result.getOutput().getFirst() instanceof TextBlock text
                && delivery.canonicalResult().equals(text.getText());
    }

    private int lastUserIndex(List<AgentMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equalsIgnoreCase(messages.get(index).role())) {
                return index;
            }
        }
        return -1;
    }
}
