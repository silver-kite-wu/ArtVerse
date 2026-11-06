package com.artverse.application.automation;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentTaskType;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomationAgentToolsTest {
    final AutomationService service=mock(AutomationService.class);
    final AutomationStore store=mock(AutomationStore.class);
    final AutomationInputService inputs=mock(AutomationInputService.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final AutomationAgentTools tools=new AutomationAgentTools(service,store,inputs,mapper);
    MangaAgentRuntimeContext context(){return new MangaAgentRuntimeContext(1L,2L,3L,UUID.randomUUID(),UUID.randomUUID(),null,
            AgentTaskType.STORY_CHAT_READ,"read",1L);}
    AutomationDefinition definition(){return new AutomationDefinition("fixture","AGENT","STORY_CHAT","read only",null,mapper.createObjectNode().put("storyId",2).put("chapterId",3),null,null,null,null,null,null,null);}

    @Test void backgroundCannotListOrCreateMorePlans() {
        var context=context();when(store.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(anyString(),eq(1L),eq(1L),eq(context.requestId()),eq(2L),eq(3L),eq(context.conversationId()),eq("STORY_CHAT")))
                .thenReturn(List.of(Map.of("id",1L,"background",true,"active",true)));
        assertThatThrownBy(()->tools.list(context)).isInstanceOf(BusinessException.class).hasMessageContaining("后台定时任务");
        assertThatThrownBy(()->tools.create(context,Map.of("definition",definition()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
    }
    @Test void interactiveCreateDelegatesToTheTransactionalProposalBoundary() {
        var context=context();when(store.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(anyString(),eq(1L),eq(1L),eq(context.requestId()),eq(2L),eq(3L),eq(context.conversationId()),eq("STORY_CHAT")))
                .thenReturn(List.of(Map.of("id",1L,"background",false,"active",true)));
        var definition=definition();
        tools.create(context,Map.of("definition",definition));
        verify(service).proposeModelCommand(context,"create",null,null,definition);
        verify(service,never()).create(anyLong(),anyString(),any());
    }

    @Test void missingPersistentRunOrLostFenceNeverBecomesInteractive() {
        var context=context();when(store.jdbc()).thenReturn(jdbc);
        assertThatThrownBy(()->tools.requireInteractive(context)).hasMessageContaining("归属不完整");
        when(jdbc.queryForList(anyString(),eq(1L),eq(1L),eq(context.requestId()),eq(2L),eq(3L),eq(context.conversationId()),eq("STORY_CHAT")))
                .thenReturn(List.of(Map.of("id",1L,"background",false,"active",false)));
        assertThatThrownBy(()->tools.requireInteractive(context)).hasMessageContaining("RUN_NOT_ACTIVE");
        verifyNoInteractions(service);
    }

    @Test void absentIdentityAndUnsupportedPhaseAreRejectedBeforeSql() {
        assertThatThrownBy(()->tools.requireInteractive(null)).isInstanceOf(BusinessException.class);
        var unsupported=new MangaAgentRuntimeContext(1L,2L,3L,UUID.randomUUID(),UUID.randomUUID(),null,
                AgentTaskType.MANGA_REVIEW,"review",1L);
        assertThatThrownBy(()->tools.requireInteractive(unsupported)).hasMessageContaining("阶段");
        verifyNoInteractions(store,service);
    }
}
