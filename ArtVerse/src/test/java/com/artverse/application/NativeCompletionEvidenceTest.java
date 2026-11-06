package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeCompletionEvidenceTest {
    final MangaAgentRunRepository repository = mock(MangaAgentRunRepository.class);
    final AgentRunLeaseService leases = mock(AgentRunLeaseService.class);
    final ObjectMapper mapper = new ObjectMapper();
    final MangaAgentRunService runs = new MangaAgentRunService(repository, mock(MangaAgentRunEventRepository.class), mapper, null, null, leases);
    final MangaAgentRun run = new MangaAgentRun();
    final UUID requestId = UUID.randomUUID();
    final UUID conversationId = UUID.randomUUID();
    final AgentAnswerDelivery delivery = new AgentAnswerDelivery(UUID.randomUUID(), "question-1", 3L, "accepted-hash", "{\"answer\":\"yes\"}", 7L);
    final AgentRunRequest request = new AgentRunRequest("1", 2L, 3L, AgentTaskType.STORY_CHAT_READ, List.of(),
            Map.of("execution_owner", "original", "fencing_token", 7L, "step_id", "story-chat:READ"), null, "", requestId, conversationId);

    @BeforeEach void setup() throws Exception {
        User user = new User(); user.setId(1L);
        Story story = new Story(); story.setId(2L);
        Chapter chapter = new Chapter(); chapter.setId(3L); chapter.setStory(story);
        MangaAgentConversation conversation = new MangaAgentConversation(); conversation.setId(4L); conversation.setConversationUuid(conversationId);
        run.setUser(user); run.setStory(story); run.setChapter(chapter); run.setConversation(conversation);
        run.setStatus(MangaAgentRunStatus.RUNNING); run.setFencingToken(7L); run.setOwnerInstanceId("original");
        var question = new LinkedHashMap<String, Object>();
        question.put("action_id", delivery.actionId().toString()); question.put("canonical_result", delivery.canonicalResult());
        question.put("answer_hash", delivery.answerHash()); question.put("answer", "yes"); question.put("pending_version", 3L);
        question.put("delivery_fencing_token", 7L); question.put("delivery_owner", "original");
        question.put("delivery_status", "NATIVE_RECORDED"); question.put("step_id", "story-chat:READ");
        question.put("task_type", "STORY_CHAT_READ"); question.put("profile", "local-tools-v1");
        run.setRunAttributesJson(mapper.writeValueAsString(Map.of("external_questions", Map.of("question-1", question))));
        when(repository.findForUpdate(1L, 3L, requestId)).thenReturn(Optional.of(run));
        when(repository.findByUserIdAndChapterIdAndRequestId(1L, 3L, requestId)).thenReturn(Optional.of(run));
        when(leases.owns(run, 7L)).thenReturn(true);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT r.id"), any(Object[].class)))
                .thenAnswer(invocation -> List.of(Map.of("id", 1L, "attributes", run.getRunAttributesJson())));
        runs.setAutomationClaimJdbc(jdbc);
    }

    @Test void theLastAssistantAloneDoesNotProveANativeFinalResult() {
        assertThatThrownBy(() -> runs.requireNativeCompletion(request, delivery, 8L, "assistant-1", "finished"))
                .hasMessageContaining("完成凭据");
        verify(repository, never()).saveAndFlush(run);
    }

    @Test void completionReceiptBindsActionCheckpointResultAndOriginalOwner() {
        runs.recordNativeCompletion(request, delivery, 8L, "assistant-1", "finished");
        runs.requireNativeCompletion(request, delivery, 8L, "assistant-1", "finished");
        assertThatThrownBy(() -> runs.requireNativeCompletion(request, delivery, 9L, "assistant-1", "finished"))
                .hasMessageContaining("完成凭据");
        assertThatThrownBy(() -> runs.requireNativeCompletion(request, delivery, 8L, "assistant-1", "different final"))
                .hasMessageContaining("完成凭据");
        assertThatThrownBy(() -> runs.recordNativeCompletion(request, delivery, 8L, "assistant-2", "finished"))
                .hasMessageContaining("证据已变化");
        run.setOwnerInstanceId("replacement");
        assertThatThrownBy(() -> runs.requireNativeCompletion(request, delivery, 8L, "assistant-1", "finished"))
                .hasMessageContaining("投递执行权");
    }
}
