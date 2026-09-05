package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolAuditServiceTest {

    @Test
    void extractsRequestIdFromRuntimeContext() {
        AgentRunToolStatus status = new AgentRunToolStatus(redisTemplate());
        AgentToolAuditService service = new AgentToolAuditService(status);
        UUID requestId = UUID.randomUUID();

        try (AgentRunToolStatus.RunScope ignored = status.start(1L, 7L, requestId)) {
            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .sessionId("u-1-story-2-chapter-7-manga-director")
                    .userId("1")
                    .put(MangaAgentRuntimeContext.class, new MangaAgentRuntimeContext(
                            1L, 2L, 7L, UUID.randomUUID(), requestId,
                            new UserProviderConfig("workflow", "", "", "", "", "")))
                    .build();

            Callable<Map<String, Object>> action = () -> Map.of("saved", true);
            Map<String, Object> result =
                    service.around("save_structured_storyboard", 1L, 7L, runtimeContext, action);
            assertThat(result)
                    .containsEntry("success", true)
                    .containsEntry("saved", true)
                    .containsKeys("data", "errorCode", "retryable", "auditId", "resultHash");
            assertThat(ignored.state().events()).hasSize(1);
            assertThat(ignored.state().events().getFirst().stepId()).isEqualTo("manga-director");
            assertThat(ignored.state().events().getFirst().status()).isEqualTo("SUCCEEDED");
            assertThat(ignored.state().events().getFirst().auditId()).isNotBlank();
            assertThat(ignored.state().events().getFirst().resultHash()).isNotBlank();
        }
    }

    private RedisTemplate<String, Object> redisTemplate() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations).set(anyString(), any(), any(Duration.class));
        when(valueOperations.get(anyString())).thenReturn(null);
        return redisTemplate;
    }
}
