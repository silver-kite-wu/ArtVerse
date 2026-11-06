package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.config.ArtVerseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentBudgetServiceTest {
    @Test void everyTaskTypeHasAnExplicitFiniteBudget() {
        var service = service(new ArtVerseProperties());
        for (var task : AgentTaskType.values()) {
            var limit = service.limits(task, BusinessSkillSelection.empty());
            assertThat(limit.models()).isPositive().isLessThan(100);
            assertThat(limit.tools()).isBetween(0L, 100L);
        }
        assertThat(service.limits(AgentTaskType.MANGA_DIRECTOR, null).tools()).isEqualTo(4);
        assertThat(service.limits(AgentTaskType.STORY_CHAT_WRITE, null).models()).isEqualTo(8);
        assertThat(service.limits(AgentTaskType.KNOWLEDGE_EXTRACTION, null).models()).isEqualTo(2);
        assertThat(service.limits(AgentTaskType.CHAT, null).tools()).isZero();
    }

    @Test void rejectsOversizedInputWithoutOpeningATransaction() {
        var properties = new ArtVerseProperties(); properties.getAgent().setMaxInputTokens(3);
        var transactions = mock(PlatformTransactionManager.class);
        var service = new AgentBudgetService(mock(JdbcTemplate.class), transactions, properties);
        var request = new AgentRunRequest("1",2L,3L,AgentTaskType.CHAT,
                List.of(new AgentMessage("user", "四个汉字")), Map.of(), null, "");
        assertThatThrownBy(() -> service.validateAndRecordInput(request)).hasMessageContaining("INPUT_TOKEN_ESTIMATE");
        verifyNoInteractions(transactions);
    }

    @Test void defaultInputBudgetAcceptsTheObservedLargeChatPayload() {
        var properties = new ArtVerseProperties();
        var service = service(properties);
        var request = new AgentRunRequest("1", 2L, 3L, AgentTaskType.CHAT,
                List.of(new AgentMessage("user", "x".repeat(75_476))), Map.of(), null, "");

        assertThatCode(() -> service.validateAndRecordInput(request)).doesNotThrowAnyException();
        assertThat(properties.getAgent().getMaxInputTokens() + properties.getAgent().getMaxOutputTokens())
                .isEqualTo(256_000);
        assertThat(properties.getAgent().getMaxInputBytes()).isGreaterThanOrEqualTo(896_000);
        assertThat(properties.getAgent().getMaxOutputTokens()).isGreaterThanOrEqualTo(32_000);
    }

    @Test void enforcesOutputEstimateAndUtf8BytesWithoutCallingThemActualTokens() {
        var properties = new ArtVerseProperties(); properties.getAgent().setMaxOutputBytes(5);
        var service = service(properties);
        var usage = service.measureOutput("漫画");
        assertThat(usage.estimatedTokens()).isEqualTo(2);
        assertThat(usage.bytes()).isEqualTo(6);
        assertThatThrownBy(() -> service.requireOutputWithinLimit(usage)).hasMessageContaining("OUTPUT_BYTE");
    }

    private AgentBudgetService service(ArtVerseProperties properties) {
        return new AgentBudgetService(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class), properties);
    }
}
