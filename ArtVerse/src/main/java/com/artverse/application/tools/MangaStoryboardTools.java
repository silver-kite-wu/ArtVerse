package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.SceneService;
import com.artverse.application.StructuredStoryboardService;
import com.artverse.application.StoryboardArtifactService;
import com.artverse.application.ToolIdempotencyService;
import com.artverse.domain.Chapter;
import com.artverse.config.ArtVerseProperties;
import com.artverse.guard.GenerationGuardService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MangaStoryboardTools {

    private final SceneService sceneService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final AgentToolAuditService agentToolAuditService;
    private final ToolIdempotencyService idempotencyService;
    private final MangaToolSupport support;
    private final ObjectMapper objectMapper;
    private final StoryboardArtifactService artifactService;
    private final ArtVerseProperties properties;

    @Tool(
            name = "generate_storyboard",
            description = "Generate storyboard scenes from the chapter source content and save them to the chapter.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> generateStoryboard(RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return agentToolAuditService.around("generate_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            return idempotencyService.execute(context.requestId(), context.stepId(),
                    "generate_storyboard", "no-arguments", () -> {
                Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
                support.requireDestructiveWriteConfirmation(context, chapter, "generate_storyboard_overwrite");
                return generationGuardService.executeSceneGeneration(
                        context.userId(), context.chapterId(), () -> {
                            if (!properties.getAgent().isStoryboardTwoPhaseEnabled()) {
                                List<String> legacy = sceneService.generateScenes(
                                        context.chapterId(), context.workflowConfig());
                                return legacyResult(chapter, legacy);
                            }
                            List<String> scenes = sceneService.generateScenesDraft(
                                    context.chapterId(), context.workflowConfig());
                            StoryboardArtifactService.ArtifactView draft = artifactService.createTextDraft(scenes, context);
                            StoryboardArtifactService.ArtifactView committed = artifactService.commit(draft.artifactId(), context);
                            return buildResultMap(chapter, scenes, committed);
                        });
            });
        });
    }

    @Tool(
            name = "save_storyboard",
            description = "Save edited storyboard scenes to the chapter.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> saveStoryboard(
            @ToolParam(name = "scenes", description = "Complete storyboard scene list") List<String> scenes,
            RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        String inputHash = contentHash(scenes);
        return agentToolAuditService.around("save_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            return idempotencyService.execute(context.requestId(), context.stepId(),
                    "save_storyboard", inputHash, () -> {
                Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
                support.requireDestructiveWriteConfirmation(context, chapter, "save_storyboard_overwrite");
                if (!properties.getAgent().isStoryboardTwoPhaseEnabled()) {
                    return legacyResult(chapter, sceneService.updateScenes(context.chapterId(), scenes));
                }
                StoryboardArtifactService.ArtifactView draft = artifactService.createTextDraft(scenes, context);
                StoryboardArtifactService.ArtifactView committed = artifactService.commit(draft.artifactId(), context);
                return buildResultMap(chapter, scenes, committed);
            });
        });
    }

    @Tool(
            name = "save_structured_storyboard",
            description = "Save storyboard pages as structured page/panel data. Input may be a list of pages or an object with pages. Each page must contain 4-6 panels.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> saveStructuredStoryboard(
            @ToolParam(name = "pages", description = "Storyboard pages with panels") Object pages,
            RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        String inputHash = contentHash(pages);
        return agentToolAuditService.around("save_structured_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            return idempotencyService.execute(context.requestId(), context.stepId(),
                    "save_structured_storyboard", inputHash, () -> {
                Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
                support.requireDestructiveWriteConfirmation(context, chapter, "save_structured_storyboard_overwrite");
                List<String> normalized = structuredStoryboardService.normalize(pages, chapter.getImageCount());
                if (!properties.getAgent().isStoryboardTwoPhaseEnabled()) {
                    return legacyResult(chapter,
                            sceneService.updateScenes(context.chapterId(), normalized));
                }
                StoryboardArtifactService.ArtifactView draft = artifactService.createStructuredDraft(pages, context);
                StoryboardArtifactService.ArtifactView committed = artifactService.commit(draft.artifactId(), context);
                return buildResultMap(chapter, normalized, committed);
            });
        });
    }

    @Tool(
            name = "draft_structured_storyboard",
            description = "Create and validate a storyboard draft without changing the chapter. Returns an artifact_id and evaluator result.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> draftStructuredStoryboard(
            @ToolParam(name = "pages", description = "Storyboard pages with 4-6 panels per page") Object pages,
            RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        String inputHash = contentHash(pages);
        return agentToolAuditService.around("draft_structured_storyboard", context.userId(), context.chapterId(),
                runtimeContext, () -> idempotencyService.execute(context.requestId(), context.stepId(),
                        "draft_structured_storyboard", inputHash, () -> {
                            StoryboardArtifactService.ArtifactView artifact =
                                    artifactService.createStructuredDraft(pages, context);
                            return Map.of(
                                    "artifact_id", artifact.artifactId().toString(),
                                    "saved", false,
                                    "validated", "VALIDATED".equals(artifact.status()),
                                    "status", artifact.status(),
                                    "evaluation", artifact.evaluation(),
                                    "scenes_count", ((List<?>) artifact.payload().get("scenes")).size()
                            );
                        }));
    }

    @Tool(
            name = "commit_storyboard",
            description = "Commit one validated storyboard artifact. This is the only chapter write operation in the new storyboard workflow.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> commitStoryboard(
            @ToolParam(name = "artifact_id", description = "Validated storyboard artifact UUID") String artifactId,
            RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        UUID parsed;
        try {
            parsed = UUID.fromString(artifactId);
        } catch (Exception error) {
            throw new IllegalArgumentException("artifact_id must be a UUID");
        }
        return agentToolAuditService.around("commit_storyboard", context.userId(), context.chapterId(),
                runtimeContext, () -> idempotencyService.execute(context.requestId(), context.stepId(),
                        "commit_storyboard", contentHash(artifactId), () -> {
                            Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
                            support.requireDestructiveWriteConfirmation(context, chapter, "commit_storyboard_overwrite");
                            StoryboardArtifactService.ArtifactView artifact = artifactService.commit(parsed, context);
                            @SuppressWarnings("unchecked")
                            List<String> scenes = (List<String>) artifact.payload().get("scenes");
                            return buildResultMap(chapter, scenes, artifact);
                        }));
    }

    private Map<String, Object> buildResultMap(Chapter chapter, List<String> scenes,
                                               StoryboardArtifactService.ArtifactView artifact) {
        return Map.of(
                "chapter_display_name", support.chapterDisplayName(chapter),
                "saved", true,
                "changed", true,
                "scenes_count", scenes.size(),
                "scenes", scenes,
                "artifact_id", artifact.artifactId().toString(),
                "artifact_status", artifact.status(),
                "evaluation", artifact.evaluation()
        );
    }

    private Map<String, Object> legacyResult(Chapter chapter, List<String> scenes) {
        return Map.of(
                "chapter_display_name", support.chapterDisplayName(chapter),
                "saved", true,
                "changed", true,
                "scenes_count", scenes.size(),
                "scenes", scenes,
                "compatibility_mode", true
        );
    }

    private String contentHash(Object pages) {
        if (pages == null) {
            return ToolIdempotencyService.sha256("pages:null");
        }
        try {
            String json = objectMapper.writeValueAsString(pages);
            return ToolIdempotencyService.sha256("pages:" + json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize pages for idempotency hash; falling back to type+toString", e);
            return ToolIdempotencyService.sha256("pages:" + pages.getClass().getName() + ":" + pages);
        }
    }
}
