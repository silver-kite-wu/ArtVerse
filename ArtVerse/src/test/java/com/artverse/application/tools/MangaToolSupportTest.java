package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.UserProviderConfig;
import com.artverse.domain.Chapter;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaToolSupportTest {

    @Test
    void existingStoryboardRequiresApplicationConfirmation() {
        AgentRunToolStatus status = mock(AgentRunToolStatus.class);
        MangaToolSupport support = new MangaToolSupport(status);
        UUID requestId = UUID.randomUUID();
        MangaAgentRuntimeContext context = new MangaAgentRuntimeContext(
                1L, 3L, 7L, UUID.randomUUID(), requestId, blankWorkflowConfig());
        Chapter chapter = new Chapter();
        chapter.setScenesText("existing storyboard");
        when(status.isMutationAuthorized(1L, 7L, requestId)).thenReturn(false);

        assertThatThrownBy(() -> support.requireDestructiveWriteConfirmation(
                context, chapter, "save_storyboard_overwrite"))
                .isInstanceOf(AgentUserInputRequiredException.class);

        verify(status).requestUserInput(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.argThat(request ->
                        "MUTATION_CONFIRMATION".equals(request.purpose())));
    }

    @Test
    void confirmedRequestMayOverwriteStoryboard() {
        AgentRunToolStatus status = mock(AgentRunToolStatus.class);
        MangaToolSupport support = new MangaToolSupport(status);
        UUID requestId = UUID.randomUUID();
        MangaAgentRuntimeContext context = new MangaAgentRuntimeContext(
                1L, 3L, 7L, UUID.randomUUID(), requestId, blankWorkflowConfig());
        Chapter chapter = new Chapter();
        chapter.setScenesText("existing storyboard");
        when(status.isMutationAuthorized(1L, 7L, requestId)).thenReturn(true);

        support.requireDestructiveWriteConfirmation(context, chapter, "save_storyboard_overwrite");
    }

    private static UserProviderConfig blankWorkflowConfig() {
        return new UserProviderConfig("workflow", "", "", "", "", "");
    }
}
