package com.artverse.application.automation;

import com.artverse.application.*;
import com.artverse.domain.*;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.artverse.common.BusinessException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AgentAutomationExecutorTest {
    @Test void selectedOptionReachesTheContinuationWithoutManufacturingAnEmptyAnswer() throws Exception {
        var json = new ObjectMapper();
        var store = mock(AutomationStore.class);
        var inputs = mock(AutomationInputService.class);
        var conversations = mock(AiConversationService.class);
        var runs = mock(MangaAgentRunRepository.class);
        var novel = mock(StoryChatAgentService.class);
        var runService = mock(MangaAgentRunService.class);
        var executor = new AgentAutomationExecutor(store, inputs, conversations, runs,
                runService, mock(MangaAgentService.class), novel);
        UUID id = UUID.randomUUID();
        var definition = new AutomationDefinition("Check", "AGENT", "STORY_CHAT", "Read", null,
                json.readTree("{\"chapterId\":7}"), null, null, null, null, null, null, null);
        var response = json.readTree("{\"decision\":\"answer\",\"selectedOptionId\":\"b\",\"toolCallId\":\"call-1\",\"pendingVersion\":2}");
        var run = new AutomationStore.Run(id, UUID.randomUUID(), 3L, 1L, "MANUAL", Instant.now(),
                definition, null, "RUNNING", 1, Instant.now(), "worker", Instant.now().plusSeconds(60),
                1L, null, null, null, null, response, Instant.now(), null, null, null, "action", "hash");
        var user = new User(); user.setId(3L);
        var config = new UserProviderConfig("llm", "", "", "", "", "");
        var conversation = new MangaAgentConversation(); conversation.setConversationUuid(UUID.randomUUID());
        var child = new MangaAgentRun(); child.setStatus(MangaAgentRunStatus.WAITING_USER);
        when(store.owned(run)).thenReturn(true);
        when(inputs.user(3L)).thenReturn(user);
        when(inputs.config(eq(3L), any(), eq(false))).thenReturn(config);
        when(conversations.automationConversation(eq(user), eq(AiConversationType.STORY_CHAT), eq(7L), eq(id), anyString()))
                .thenReturn(conversation);
        when(runs.findByUserIdAndChapterIdAndRequestId(3L, 7L, id)).thenReturn(Optional.of(child));
        when(store.consumeResponse(run)).thenReturn(true);
        when(runService.isNativeAnswerRecorded(conversation, id, "call-1", 2L)).thenReturn(false, true);

        executor.execute(run);

        verify(novel).resumeBackground(conversation, id, "answer", null, null, "call-1", 2L, "b", config,
                new com.artverse.agent.AutomationExecutionClaim(id,"worker",1L));
        verify(store).consumeResponse(run);
    }

    @Test void missingNativeEvidenceDoesNotClearTheResponse() throws Exception {
        var f=fixture();
        f.executor().execute(f.run());
        verify(f.store(),never()).consumeResponse(any());
        verify(f.novel()).resumeBackground(eq(f.conversation()),eq(f.run().id()),eq("answer"),isNull(),isNull(),eq("call-1"),eq(2L),eq("b"),any(),eq(parentClaim(f)));
    }

    @Test void failedCleanupReconcilesOnlyTheSameDurableConsumedAction() throws Exception {
        var f=fixture();
        when(f.runService().isNativeAnswerRecorded(any(),any(),any(),any())).thenReturn(false,true);
        when(f.store().responseConsumed(f.run())).thenReturn(true);
        f.executor().execute(f.run());
        verify(f.store()).consumeResponse(f.run());
        verify(f.store()).responseConsumed(f.run());
    }

    @Test void failedCleanupWithoutProofPreservesTheResponseAndReportsConflict() throws Exception {
        var f=fixture();
        when(f.runService().isNativeAnswerRecorded(any(),any(),any(),any())).thenReturn(false,true);
        assertThatThrownBy(()->f.executor().execute(f.run())).isInstanceOf(BusinessException.class)
                .hasMessageContaining("保留当前响应");
        verify(f.store()).responseConsumed(f.run());
    }

    @Test void completedNativeAnswerIsConsumedWithoutLoadingAProviderOrResumingTheModel() throws Exception {
        var f=fixture();f.child().setStatus(MangaAgentRunStatus.SUCCEEDED);
        when(f.runService().isNativeAnswerRecorded(any(),any(),any(),any())).thenReturn(true);
        when(f.store().consumeResponse(f.run())).thenReturn(true);
        f.executor().execute(f.run());
        verify(f.inputs(),never()).config(anyLong(),any(),anyBoolean());
        verifyNoInteractions(f.novel());
    }

    @Test void databaseLeaseDecisionWinsOverAStaleLocalTimestamp() throws Exception {
        var f=fixture();f.child().setStatus(MangaAgentRunStatus.RUNNING);
        f.child().setLeaseUntil(java.time.OffsetDateTime.now().minusDays(1));
        when(f.runService().hasActiveExecutionLease(f.child())).thenReturn(true);
        f.executor().execute(f.run());
        verifyNoInteractions(f.novel());
        verify(f.inputs(),never()).config(anyLong(),any(),anyBoolean());
    }

    @Test void modelFailureStillConsumesTheAnswerIfNativeFailurePersistenceRecordedIt() throws Exception {
        var f=fixture();
        when(f.runService().isNativeAnswerRecorded(any(),any(),any(),any())).thenReturn(false,true);
        when(f.store().consumeResponse(f.run())).thenReturn(true);
        var failure=new IllegalStateException("scripted model failure");
        doThrow(failure).when(f.novel()).resumeBackground(any(),any(),anyString(),any(),any(),any(),any(),any(),any(),eq(parentClaim(f)));
        assertThatThrownBy(()->f.executor().execute(f.run())).isSameAs(failure);
        verify(f.store()).consumeResponse(f.run());
    }

    @Test void mangaOptionUsesExactCallVersionAndOnlyClearsWithNativeEvidence() throws Exception {
        var f=fixture("MANGA_AGENT");
        when(f.runService().isNativeAnswerRecorded(any(),any(),any(),any())).thenReturn(false,true);
        when(f.store().consumeResponse(f.run())).thenReturn(true);
        f.executor().execute(f.run());
        verify(f.manga()).resumeBackground(eq(f.conversation()),eq(f.run().id()),isNull(),eq("call-1"),eq(2L),eq("b"),any(),eq(parentClaim(f)));
        verify(f.store()).consumeResponse(f.run());verifyNoInteractions(f.novel());
    }

    @Test void completedMangaAnswerConsumesItsReceiptWithoutProviderAccess() throws Exception {
        var f=fixture("MANGA_AGENT");f.child().setStatus(MangaAgentRunStatus.SUCCEEDED);
        when(f.runService().isNativeAnswerRecorded(any(),any(),any(),any())).thenReturn(true);
        when(f.store().consumeResponse(f.run())).thenReturn(true);
        f.executor().execute(f.run());
        verify(f.inputs(),never()).config(anyLong(),any(),anyBoolean());verifyNoInteractions(f.manga(),f.novel());
        verify(f.store()).consumeResponse(f.run());
    }

    @Test void mangaFailureRecordsTheNativeAnswerBeforeRethrowingWithoutReplayingIt() throws Exception {
        var f=fixture("MANGA_AGENT");
        when(f.runService().isNativeAnswerRecorded(any(),any(),any(),any())).thenReturn(false,true);
        when(f.store().consumeResponse(f.run())).thenReturn(true);
        var failure=new IllegalStateException("scripted manga failure");
        doThrow(failure).when(f.manga()).resumeBackground(any(),any(),any(),any(),any(),any(),any(),eq(parentClaim(f)));
        assertThatThrownBy(()->f.executor().execute(f.run())).isSameAs(failure);
        verify(f.store()).consumeResponse(f.run());
    }

    private Fixture fixture() throws Exception {
        return fixture("STORY_CHAT");
    }
    private com.artverse.agent.AutomationExecutionClaim parentClaim(Fixture f) {
        return new com.artverse.agent.AutomationExecutionClaim(f.run().id(),f.run().leaseOwner(),f.run().fencingToken());
    }
    private Fixture fixture(String executorKey) throws Exception {
        var json=new ObjectMapper();var store=mock(AutomationStore.class);var inputs=mock(AutomationInputService.class);
        var conversations=mock(AiConversationService.class);var runs=mock(MangaAgentRunRepository.class);
        var runService=mock(MangaAgentRunService.class);var novel=mock(StoryChatAgentService.class);
        var manga=mock(MangaAgentService.class);
        var executor=new AgentAutomationExecutor(store,inputs,conversations,runs,runService,manga,novel);
        UUID id=UUID.randomUUID();
        var definition=new AutomationDefinition("Check","AGENT",executorKey,"Read",null,
                json.readTree("{\"chapterId\":7}"),null,null,null,null,null,null,null);
        var response=json.readTree("{\"decision\":\"answer\",\"selectedOptionId\":\"b\",\"toolCallId\":\"call-1\",\"pendingVersion\":2}");
        var run=new AutomationStore.Run(id,UUID.randomUUID(),3,1,"MANUAL",Instant.now(),definition,null,"RUNNING",1,
                Instant.now(),"worker",Instant.now().plusSeconds(60),1,null,null,null,null,response,Instant.now(),null,null,null,"action","hash");
        var user=new User();user.setId(3L);
        var conversation=new MangaAgentConversation();conversation.setConversationUuid(UUID.randomUUID());
        var child=new MangaAgentRun();child.setStatus(MangaAgentRunStatus.WAITING_USER);
        when(store.owned(run)).thenReturn(true);when(inputs.user(3L)).thenReturn(user);
        when(inputs.config(eq(3L),any(),eq(false))).thenReturn(new UserProviderConfig("llm","","","","",""));
        when(conversations.automationConversation(any(),any(),eq(7L),eq(id),anyString())).thenReturn(conversation);
        when(runs.findByUserIdAndChapterIdAndRequestId(3L,7L,id)).thenReturn(Optional.of(child));
        return new Fixture(executor,store,inputs,runService,novel,manga,run,conversation,child);
    }

    private record Fixture(AgentAutomationExecutor executor,AutomationStore store,AutomationInputService inputs,
                           MangaAgentRunService runService,StoryChatAgentService novel,MangaAgentService manga,AutomationStore.Run run,
                           MangaAgentConversation conversation,MangaAgentRun child) { }
}
