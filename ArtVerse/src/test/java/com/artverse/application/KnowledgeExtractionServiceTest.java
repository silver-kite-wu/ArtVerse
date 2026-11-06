package com.artverse.application;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeExtractionServiceTest {

    @Test
    void blankChapterSupersedesPendingCandidatesWithoutCallingModel() {
        Fixture fixture = fixture("", "");
        Runnable ownership = mock(Runnable.class);
        when(fixture.candidateService.replaceChapterExtraction(any(), eq(fixture.batch), eq(List.of())))
                .thenReturn(List.of());

        int count = fixture.service.extract(event(), ownership);

        assertThat(count).isZero();
        verify(ownership).run();
        verify(fixture.candidateService).replaceChapterExtraction(any(), eq(fixture.batch), eq(List.of()));
        verify(fixture.apiKeyService, never()).requireActiveUserProviderConfig(any(), any(), any());
        verify(fixture.gateway, never()).generateStructured(any(), any());
    }

    @Test
    void nonBlankChapterUsesByokAndPersistsOnlyStructuredCandidates() {
        Fixture fixture = fixture("角色阿青明确害怕海水。", "");
        Runnable ownership = mock(Runnable.class);
        UserProviderConfig provider = new UserProviderConfig(
                "llm", "deepseek", "User LLM", "sk-user",
                "https://api.deepseek.com", "deepseek-chat", 42L);
        AgentModelSpec spec = new AgentModelSpec(
                "deepseek", "https://api.deepseek.com", "deepseek-chat", "hash");
        KnowledgeCandidateService.ExtractedCandidate candidate =
                new KnowledgeCandidateService.ExtractedCandidate(
                        "CHARACTER_CARD", "阿青的弱点", "阿青害怕海水。", "怕水",
                        Map.of(), 4, 1, null);
        when(fixture.apiKeyService.requireActiveUserProviderConfig(
                fixture.user, ApiKeyService.SLOT_LLM, "An active user-owned LLM configuration is required for knowledge extraction."))
                .thenReturn(provider);
        when(fixture.modelSpecFactory.fromProviderConfig(provider)).thenReturn(spec);
        when(fixture.gateway.generateStructured(any(AgentRunRequest.class),
                eq(KnowledgeExtractionService.ExtractionResult.class)))
                .thenReturn(Mono.just(new KnowledgeExtractionService.ExtractionResult(List.of(candidate))));
        when(fixture.candidateService.replaceChapterExtraction(any(), eq(fixture.batch), eq(List.of(candidate))))
                .thenReturn(Collections.singletonList(null));

        int count = fixture.service.extract(event(), ownership);

        assertThat(count).isEqualTo(1);
        verify(ownership).run();
        verify(fixture.candidateService).replaceChapterExtraction(any(), eq(fixture.batch), eq(List.of(candidate)));
        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(fixture.gateway).generateStructured(request.capture(), eq(KnowledgeExtractionService.ExtractionResult.class));
        assertThat(request.getValue().messages().getFirst().content()).contains("角色阿青明确害怕海水。");
        assertThat(request.getValue().variables()).containsEntry("source_hash", fixture.batch.sourceHash())
                .containsEntry("source_version", 0L);
    }

    @Test
    void completedActionReturnsOriginalBatchWithoutCallingProviderOrReplacingAgain() {
        Fixture fixture = fixture("章节已经变更", "");
        var completed = new KnowledgeCandidateService.ExtractionBatch(11L, fixture.batch.source(), "source-hash", true, List.of());
        when(fixture.candidateService.beginChapterExtraction(eq(7L), eq(1L), any())).thenReturn(completed);
        when(fixture.candidateService.extractionResults(completed)).thenReturn(List.of());
        assertThat(fixture.service.extract(event(), () -> {})).isZero();
        verifyNoInteractions(fixture.gateway, fixture.apiKeyService);
        verify(fixture.candidateService, never()).replaceChapterExtraction(any(), any(), any());
    }

    @Test
    void oversizedMaterialFailsBeforeProviderAndDoesNotReplaceCandidates() {
        Fixture fixture = fixture("长".repeat(48_001), "");
        assertThatThrownBy(() -> fixture.service.extract(event(), () -> {}))
                .isInstanceOf(BusinessException.class).hasMessageContaining("48000");
        verifyNoInteractions(fixture.gateway, fixture.apiKeyService);
        verify(fixture.candidateService, never()).replaceChapterExtraction(any(), any(), any());
    }

    @Test
    void missingStructuredResponsePreservesExistingCandidates() {
        Fixture fixture = fixture("原文", "");
        UserProviderConfig provider = new UserProviderConfig("llm", "test", "test", "test-key", "http://localhost", "test", 42L);
        when(fixture.apiKeyService.requireActiveUserProviderConfig(any(), any(), any())).thenReturn(provider);
        when(fixture.gateway.generateStructured(any(), eq(KnowledgeExtractionService.ExtractionResult.class))).thenReturn(Mono.empty());
        assertThatThrownBy(() -> fixture.service.extract(event(), () -> {}))
                .isInstanceOf(BusinessException.class).hasMessageContaining("完整结构化结果");
        verify(fixture.candidateService, never()).replaceChapterExtraction(any(), any(), any());
    }

    private Fixture fixture(String novel, String scenes) {
        UserRepository userRepository = mock(UserRepository.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AgentModelSpecFactory modelSpecFactory = mock(AgentModelSpecFactory.class);
        AgentScopeHarnessAgentGateway gateway = mock(AgentScopeHarnessAgentGateway.class);
        KnowledgeCandidateService candidateService = mock(KnowledgeCandidateService.class);
        User user = new User();
        user.setId(1L);
        var source = new KnowledgeCandidateService.ExtractionSource(7L, 1L, 3L, 1, 0L, novel, scenes);
        var batch = new KnowledgeCandidateService.ExtractionBatch(11L, source, "source-hash", false, List.of());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(candidateService.beginChapterExtraction(eq(7L), eq(1L), any())).thenReturn(batch);
        KnowledgeExtractionService service = new KnowledgeExtractionService(
                userRepository, apiKeyService, modelSpecFactory,
                gateway, candidateService, new ObjectMapper(), new ArtVerseProperties());
        return new Fixture(service, apiKeyService, modelSpecFactory, gateway,
                candidateService, user, batch);
    }

    private AgentOutboxService.OutboxEvent event() {
        return new AgentOutboxService.OutboxEvent(
                11L, "CHAPTER", "7", "CHAPTER_CONTENT_CHANGED",
                Map.of("user_id", 1L, "story_id", 3L, "chapter_id", 7L),
                1, 2L, OffsetDateTime.now().plusSeconds(90));
    }

    private record Fixture(
            KnowledgeExtractionService service,
            ApiKeyService apiKeyService,
            AgentModelSpecFactory modelSpecFactory,
            AgentScopeHarnessAgentGateway gateway,
            KnowledgeCandidateService candidateService,
            User user,
            KnowledgeCandidateService.ExtractionBatch batch
    ) {
    }
}
