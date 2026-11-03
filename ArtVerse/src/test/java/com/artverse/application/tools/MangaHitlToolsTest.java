package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.AgentUserInputRequest;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class MangaHitlToolsTest {
    @Test void externalPauseBecomesProductWaitingRequestWithoutExecutingTheTool() {
        var support=mock(MangaToolSupport.class);var audit=mock(AgentToolAuditService.class);
        var tools=new MangaHitlTools(audit,support);
        var runtime=RuntimeContext.empty();var context=new MangaAgentRuntimeContext(1L,2L,3L,UUID.randomUUID(),UUID.randomUUID(),null);
        when(support.resolveContext(runtime)).thenReturn(context);
        var raw=List.of("确认","放弃");
        var options=List.of(new AgentUserInputRequest.Option("confirm","确认","",true),new AgentUserInputRequest.Option("discard","放弃","",false));
        when(support.normalizeOptions(raw)).thenReturn(options);
        tools.captureExternalInput(Map.of("question","确认草稿？","options",raw,"reason","artifact_id=fixture","allow_free_text",false),runtime);
        verify(support).requestUserInput(context,new AgentUserInputRequest("确认草稿？",options,false,"artifact_id=fixture","BUSINESS_CONFIRMATION"));
        verify(audit).around(eq("ask_user"),eq(1L),eq(3L),eq(runtime),any());
    }
}
