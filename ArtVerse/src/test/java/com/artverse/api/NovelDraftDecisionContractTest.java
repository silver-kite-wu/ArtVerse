package com.artverse.api;

import com.artverse.application.*;
import com.artverse.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NovelDraftDecisionContractTest {
    @Test
    void snakeCaseReviewRequestSavesWithoutRequiringModelConfiguration() throws Exception {
        CurrentUserService users = mock(CurrentUserService.class);
        User user = new User(); user.setId(3L);
        when(users.requireCurrentUser()).thenReturn(user);
        NovelDraftReviewService review = mock(NovelDraftReviewService.class);
        ApiKeyService keys = mock(ApiKeyService.class);
        var controller = new StoryChatController(mock(StoryChatAgentService.class), keys, users,
                mock(MangaAgentEventReplayService.class), review);
        UUID conversation = UUID.randomUUID(), request = UUID.randomUUID(), artifact = UUID.randomUUID();
        when(review.decide(3L, 1L, conversation, request, artifact, "confirm", "作者确认的正文", 6L, "hash"))
                .thenReturn(new NovelContentArtifactService.CommitView(artifact, true, 7L, 10L, 2, "new-hash", "COMMITTED"));
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        mvc.perform(post("/api/chapters/1/story-chat/conversations/{conversation}/runs/{request}/artifacts/{artifact}/decision", conversation, request, artifact)
                        .contentType("application/json").content("""
                                {"decision":"confirm","content":"作者确认的正文","base_version":6,"expected_content_hash":"hash"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chapter_version").value(7))
                .andExpect(jsonPath("$.changed").value(true)).andExpect(jsonPath("$.status").value("COMMITTED"));
        verifyNoInteractions(keys);
    }
}
