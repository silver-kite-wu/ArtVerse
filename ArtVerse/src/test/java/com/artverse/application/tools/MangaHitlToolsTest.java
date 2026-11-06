package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentToolAttempt;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.MangaAgentRunService;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class MangaHitlToolsTest {
    @Test void externalPauseBecomesProductWaitingRequestWithoutExecutingTheTool() {
        var support=mock(MangaToolSupport.class);var audit=mock(AgentToolAuditService.class);
        var runs=mock(MangaAgentRunService.class);
        var tools=new MangaHitlTools(audit,support,runs);
        var context=new MangaAgentRuntimeContext(1L,2L,3L,UUID.randomUUID(),UUID.randomUUID(),null);
        var runtime=RuntimeContext.builder().put(MangaAgentRuntimeContext.class,context).build();
        when(support.resolveContext(runtime)).thenReturn(context);
        var raw=List.of("确认","放弃");
        var options=List.of(new AgentUserInputRequest.Option("confirm","确认","",true),new AgentUserInputRequest.Option("discard","放弃","",false));
        when(support.normalizeOptions(raw)).thenReturn(options);
        var pending=new AgentUserInputRequest("确认草稿？",options,false,"artifact_id=fixture","USER_QUESTION","original-call",1L);
        when(runs.recordExternalQuestion(eq(context),eq("original-call"),any()))
                .thenReturn(new MangaAgentRunService.ExternalQuestion(pending,true))
                .thenReturn(new MangaAgentRunService.ExternalQuestion(pending,false));
        var call=new io.agentscope.core.message.ToolUseBlock("original-call","ask_user",
                Map.of("question","确认草稿？","options",raw,"reason","artifact_id=fixture","allow_free_text",false),"{}",Map.of());
        tools.captureExternalInput(call,runtime);
        tools.captureExternalInput(call,runtime);
        verify(support,times(2)).requestUserInput(context,pending);
        verify(audit).around(eq("ask_user"),eq(1L),eq(3L),argThat(auditRuntime -> {
            AgentToolAttempt attempt = auditRuntime.get(AgentToolAttempt.class);
            return attempt != null && attempt.callId().equals("original-call")
                    && attempt.toolName().equals("ask_user")
                    && attempt.requestId().equals(context.requestId());
        }),any());
    }
}
