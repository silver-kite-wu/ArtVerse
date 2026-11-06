package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentGatewayLifecycleTest {
    @Test void failureAfterAgentCreationAlwaysClosesTheAgentAndTurn() {
        for (boolean structured : List.of(false, true)) {
            var factory = mock(AgentScopeAgentFactory.class);
            var contexts = mock(AgentScopeRuntimeContextFactory.class);
            var turns = mock(AgentSessionTurns.class);
            var lease = mock(AgentSessionTurns.Lease.class);
            when(turns.acquire(any(AgentRunRequest.class))).thenReturn(lease);
            var agent = mock(HarnessAgent.class);
            when(factory.getOrCreate(any(), any())).thenReturn(agent);
            when(agent.getToolkit()).thenReturn(new Toolkit());
            when(contexts.create(any(), anyString())).thenThrow(new IllegalStateException("scripted context failure"));
            var gateway = gateway(factory, contexts, turns);
            var request = request("1", UUID.randomUUID(), UUID.randomUUID(),
                    structured ? AgentTaskType.MANGA_ROUTER : AgentTaskType.CHAT);
            assertThatThrownBy(() -> {
                if (structured) gateway.generateStructured(request, String.class).block();
                else gateway.streamEvents(request).blockLast();
            }).hasMessageContaining("scripted context failure");
            verify(agent).close();
            verify(lease).close();
            var order = inOrder(turns, factory);
            order.verify(turns).acquire(request);
            order.verify(factory).getOrCreate(eq(request), any());
        }
    }

    @Test void busyTurnRejectsBeforeStateOrFactoryAccess() {
        var factory = mock(AgentScopeAgentFactory.class);
        var turns = mock(AgentSessionTurns.class);
        when(turns.acquire(any(AgentRunRequest.class))).thenThrow(new BusinessException(409, "busy"));
        var gateway = gateway(factory, new AgentScopeRuntimeContextFactory(), turns);
        assertThatThrownBy(() -> gateway.streamEvents(request("1", UUID.randomUUID(), UUID.randomUUID(), AgentTaskType.CHAT)).blockLast())
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(factory);
    }

    @Test void scopedCancellationAndDuplicateRegistrationCannotReplaceAnotherInvocation() {
        var factory = mock(AgentScopeAgentFactory.class);
        var turns = mock(AgentSessionTurns.class);
        when(turns.acquire(any(AgentRunRequest.class))).thenAnswer(ignored -> mock(AgentSessionTurns.Lease.class));
        var first = mock(HarnessAgent.class);
        var duplicate = mock(HarnessAgent.class);
        var otherActor = mock(HarnessAgent.class);
        when(factory.getOrCreate(any(), any())).thenReturn(first, duplicate, otherActor);
        for (var agent : List.of(first, duplicate, otherActor)) {
            when(agent.prepareRun(anyList(), any())).thenReturn(AgentRun.<AgentEvent>create(
                    UUID.randomUUID().toString(), Flux::never));
        }
        var gateway = gateway(factory, new AgentScopeRuntimeContextFactory(), turns);
        UUID requestId = UUID.randomUUID(), conversationId = UUID.randomUUID();
        var request = request("1", requestId, conversationId, AgentTaskType.CHAT);
        var error = new AtomicReference<Throwable>();
        var subscription = gateway.streamEvents(request).subscribe(ignored -> { }, error::set);
        var second = gateway.streamEvents(request("2", requestId, conversationId, AgentTaskType.CHAT));
        try {
            assertThatThrownBy(() -> gateway.streamEvents(request).blockLast())
                    .isInstanceOf(BusinessException.class).hasMessageContaining("会话正在执行");
            verify(duplicate).close();
            verify(first, never()).close();
            var other = second.subscribe(ignored -> { }, error::set);
            try {
                assertThat(gateway.interrupt("3", conversationId, requestId)).isFalse();
                assertThat(gateway.interrupt("1", UUID.randomUUID(), requestId)).isFalse();
                assertThat(gateway.interrupt("1", conversationId, requestId)).isTrue();
                assertThat(other.isDisposed()).isFalse();
                verify(otherActor, never()).close();
            } finally { other.dispose(); }
        } finally { subscription.dispose(); }
        verify(first).close();
        verify(otherActor).close();
    }

    private AgentScopeHarnessAgentGateway gateway(AgentScopeAgentFactory factory,
            AgentScopeRuntimeContextFactory contexts, AgentSessionTurns turns) {
        doAnswer(invocation -> {
            invocation.<HarnessAgent>getArgument(0).close();
            return null;
        }).when(factory).closeAgent(any(HarnessAgent.class));
        var gateway = new AgentScopeHarnessAgentGateway(factory, contexts, new ArtVerseProperties(),
                CircuitBreakerRegistry.ofDefaults(), null, null, turns);
        gateway.init();
        return gateway;
    }

    private AgentRunRequest request(String actor, UUID request, UUID conversation, AgentTaskType type) {
        return new AgentRunRequest(actor, 2L, 3L, type,
                List.of(new AgentMessage("user", "test")), Map.of(), null, "", request, conversation);
    }
}
