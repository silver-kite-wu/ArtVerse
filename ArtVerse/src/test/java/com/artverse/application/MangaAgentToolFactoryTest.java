package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolSuspendException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MangaAgentToolFactoryTest {

    @Test
    void generateStoryboardUsesGenerationGuard() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentToolAuditService auditService = new AgentToolAuditService(new AgentRunToolStatus());
        Chapter chapter = chapterWithOwner(7L, 1L);

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));
        when(sceneService.generateScenes(7L, "coze-key")).thenReturn(List.of("scene 1"));
        when(generationGuardService.executeSceneGeneration(eq(1L), eq(7L), any()))
                .thenAnswer(invocation -> invocation.<Callable<Map<String, Object>>>getArgument(2).call());

        MangaAgentToolFactory.Tools tools = tools(
                chapterRepository,
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                generationGuardService,
                auditService,
                1L
        );

        Map<String, Object> result = tools.generateStoryboard();

        assertThat(result).containsEntry("scenes_count", 1);
        verify(generationGuardService).executeSceneGeneration(eq(1L), eq(7L), any());
        verify(sceneService).generateScenes(7L, "coze-key");
    }

    @Test
    void generateStoryboardRejectsDifferentUserBeforeCallingGuard() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentToolAuditService auditService = new AgentToolAuditService(new AgentRunToolStatus());

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapterWithOwner(7L, 1L)));

        MangaAgentToolFactory.Tools tools = tools(
                chapterRepository,
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                generationGuardService,
                auditService,
                2L
        );

        assertThatThrownBy(tools::generateStoryboard)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Forbidden");
        verifyNoInteractions(generationGuardService, sceneService);
    }

    @Test
    void saveStoryboardRejectsDifferentUser() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentToolAuditService auditService = new AgentToolAuditService(new AgentRunToolStatus());

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapterWithOwner(7L, 1L)));

        MangaAgentToolFactory.Tools tools = tools(
                chapterRepository,
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                generationGuardService,
                auditService,
                2L
        );

        assertThatThrownBy(() -> tools.saveStoryboard(List.of("scene 1")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Forbidden");
        verifyNoInteractions(sceneService);
    }

    @Test
    void saveStructuredStoryboardNormalizesAndSavesScenes() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentToolAuditService auditService = new AgentToolAuditService(new AgentRunToolStatus());
        Chapter chapter = chapterWithOwner(7L, 1L);
        Object pages = List.of(Map.of("panels", List.of("a", "b", "c", "d")));
        List<String> scenes = List.of("第1页：【第1格】a【第2格】b【第3格】c【第4格】d");

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));
        when(structuredStoryboardService.normalize(pages, 1)).thenReturn(scenes);
        when(sceneService.updateScenes(7L, scenes)).thenReturn(scenes);

        MangaAgentToolFactory.Tools tools = tools(
                chapterRepository,
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                generationGuardService,
                auditService,
                1L
        );

        Map<String, Object> result = tools.saveStructuredStoryboard(pages);

        assertThat(result).containsEntry("scenes_count", 1);
        verify(structuredStoryboardService).normalize(pages, 1);
        verify(sceneService).updateScenes(7L, scenes);
    }

    @Test
    void askUserStoresWaitingInputAndSuspendsRun() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentRunToolStatus runStatus = new AgentRunToolStatus();
        AgentToolAuditService auditService = new AgentToolAuditService(runStatus);
        java.util.UUID requestId = java.util.UUID.randomUUID();

        try (AgentRunToolStatus.RunScope ignored = runStatus.start(1L, 7L, requestId)) {
            MangaAgentToolFactory.Tools tools = tools(
                    chapterRepository,
                    mangaImageRepository,
                    sceneService,
                    structuredStoryboardService,
                    generationGuardService,
                    auditService,
                    runStatus,
                    1L
            );

            assertThatThrownBy(() -> tools.askUser(
                    "选择数据库？",
                    List.of(Map.of("label", "PostgreSQL", "recommended", true), Map.of("label", "MySQL")),
                    true,
                    "需要持久化方案"
            )).isInstanceOf(ToolSuspendException.class);
        }

        AgentUserInputRequest waiting = runStatus.waitingInput(1L, 7L, requestId);
        assertThat(waiting).isNotNull();
        assertThat(waiting.question()).isEqualTo("选择数据库？");
        assertThat(waiting.options()).extracting(AgentUserInputRequest.Option::label)
                .containsExactly("PostgreSQL", "MySQL");
        assertThat(waiting.allowFreeText()).isTrue();
    }

    @Test
    void askUserUsesRuntimeContextRequestIdWhenAvailable() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentRunToolStatus runStatus = new AgentRunToolStatus();
        AgentToolAuditService auditService = new AgentToolAuditService(runStatus);
        UUID requestId = UUID.randomUUID();

        try (AgentRunToolStatus.RunScope ignored = runStatus.start(1L, 7L, requestId)) {
            MangaAgentToolFactory.Tools tools = tools(
                    chapterRepository,
                    mangaImageRepository,
                    sceneService,
                    structuredStoryboardService,
                    generationGuardService,
                    auditService,
                    runStatus,
                    1L
            );
            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .sessionId("u-1-story-3-chapter-7-manga-director")
                    .userId("1")
                    .put(com.artverse.agents.AgentRunContext.class, new com.artverse.agents.AgentRunContext(requestId))
                    .build();

            assertThatThrownBy(() -> tools.askUser(
                    "閫夋嫨鏁版嵁搴擄紵",
                    List.of(Map.of("label", "PostgreSQL", "recommended", true), Map.of("label", "MySQL")),
                    true,
                    "闇€瑕佹寔涔呭寲鏂规",
                    runtimeContext
            )).isInstanceOf(ToolSuspendException.class);
        }

        assertThat(runStatus.waitingInput(1L, 7L, requestId)).isNotNull();
    }

    private MangaAgentToolFactory.Tools tools(ChapterRepository chapterRepository,
                                             MangaImageRepository mangaImageRepository,
                                             SceneService sceneService,
                                             StructuredStoryboardService structuredStoryboardService,
                                             GenerationGuardService generationGuardService,
                                             AgentToolAuditService auditService,
                                             Long userId) {
        return tools(chapterRepository, mangaImageRepository, sceneService, structuredStoryboardService,
                generationGuardService, auditService, new AgentRunToolStatus(), userId);
    }

    private MangaAgentToolFactory.Tools tools(ChapterRepository chapterRepository,
                                             MangaImageRepository mangaImageRepository,
                                             SceneService sceneService,
                                             StructuredStoryboardService structuredStoryboardService,
                                             GenerationGuardService generationGuardService,
                                             AgentToolAuditService auditService,
                                             AgentRunToolStatus runStatus,
                                             Long userId) {
        MangaAgentToolFactory factory = new MangaAgentToolFactory(
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                new ChapterAccessService(chapterRepository),
                generationGuardService,
                auditService,
                runStatus
        );
        return (MangaAgentToolFactory.Tools) factory.create("coze-key", 7L, userId);
    }

    private Chapter chapterWithOwner(Long chapterId, Long ownerId) {
        User user = new User();
        user.setId(ownerId);
        Story story = new Story();
        story.setId(3L);
        story.setTitle("Story");
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(chapterId);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setImageCount(1);
        chapter.setColorMode(ColorMode.BW);
        chapter.setNovelContent("source");
        return chapter;
    }
}
