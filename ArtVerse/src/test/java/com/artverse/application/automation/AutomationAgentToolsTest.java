package com.artverse.application.automation;

import com.artverse.agent.MangaAgentRuntimeContext;
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
    MangaAgentRuntimeContext context(){return new MangaAgentRuntimeContext(1L,2L,3L,UUID.randomUUID(),UUID.randomUUID(),null);}
    AutomationDefinition definition(){return new AutomationDefinition("fixture","AGENT","STORY_CHAT","read only",null,mapper.createObjectNode().put("storyId",2).put("chapterId",3),null,null,null,null,null,null,null);}

    @Test void backgroundCannotListOrCreateMorePlans() {
        var context=context();when(store.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(context.conversationId()),eq(context.userId()))).thenReturn(true);
        assertThatThrownBy(()->tools.list(context)).isInstanceOf(BusinessException.class).hasMessageContaining("后台定时任务");
        assertThatThrownBy(()->tools.create(context,Map.of("definition",definition()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
    }
    @Test void interactiveCannotCreatePlanForChapterOutsideCurrentStory() {
        var context=context();when(store.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(context.conversationId()),eq(context.userId()))).thenReturn(false);
        when(inputs.chapterIds(any(),eq("STORY_CHAT"))).thenReturn(List.of(3L));
        when(jdbc.queryForList(anyString(),eq(3L),eq(2L),eq(1L))).thenReturn(List.of());
        assertThatThrownBy(()->tools.create(context,Map.of("definition",definition()))).isInstanceOf(BusinessException.class).hasMessageContaining("当前作品");
        verifyNoInteractions(service);
    }
}
