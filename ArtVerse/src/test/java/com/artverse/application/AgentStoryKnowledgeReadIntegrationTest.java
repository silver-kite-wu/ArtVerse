package com.artverse.application;

import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.ai.EmbeddingClient;
import com.artverse.application.knowledge.KnowledgeFactService;
import com.artverse.application.knowledge.KnowledgeSchema;
import com.artverse.application.tools.*;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AgentStoryKnowledgeReadService.class, ChapterAccessService.class, KnowledgeFactService.class,
        KnowledgeSchema.class, KnowledgeCandidateService.class, KnowledgeService.class, MangaToolSupport.class,
        StoryContextReadTools.class, KnowledgeReadTools.class, ReferenceAssetReadTools.class,
        KnowledgeCandidateReadTools.class, AgentStoryKnowledgeReadIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AgentStoryKnowledgeReadIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean EmbeddingConfigService embeddingConfig() { return mock(EmbeddingConfigService.class); }
        @Bean EmbeddingClient embeddingClient() { return mock(EmbeddingClient.class); }
        @Bean ExecutorService indexExecutor() { return mock(ExecutorService.class); }
        @Bean TransactionTemplate transactions(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean AgentRunToolStatus statuses() { return mock(AgentRunToolStatus.class); }
    }
    @Autowired AgentStoryKnowledgeReadService reads;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeSchema schema;
    @Autowired KnowledgeCandidateService candidates;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired StoryContextReadTools storyTools;
    @Autowired KnowledgeReadTools knowledgeTools;
    @Autowired ReferenceAssetReadTools assetTools;
    @Autowired KnowledgeCandidateReadTools candidateTools;
    @TempDir Path workspace;
    User user;
    Story story;
    List<Long> chapterIds;
    CharacterProfile lin, shen;
    MangaAgentRuntimeContext context;
    Long secretId, futureId;

    @BeforeEach void fixture() {
        tx.executeWithoutResult(status -> {
            var name = "reads_" + UUID.randomUUID().toString().substring(0, 12);
            user = new User(); user.setUsername(name); user.setEmail(name + "@example.test");
            user.setPasswordHash("isolated-fixture"); em.persist(user);
            story = new Story(); story.setUser(user); story.setTitle("Chapter-projected reads");
            story.setDescription("Author premise"); em.persist(story);
            chapterIds = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                var chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(i);
                chapter.setNovelContent("Source chapter " + i); chapter.setDisplayTitle("Chapter " + i); em.persist(chapter);
                chapterIds.add(chapter.getId());
            }
            lin = new CharacterProfile(); lin.setStory(story); lin.setName("林舟"); lin.setDescription("UNPROJECTED AUTHOR SECRET"); em.persist(lin);
            shen = new CharacterProfile(); shen.setStory(story); shen.setName("沈岚"); em.persist(shen);
        });
        create("CHARACTER_CARD", "林舟", "旅行者", Map.of("character_id", lin.getId(), "reveal_chapter_id", chapterIds.getFirst()));
        create("CHARACTER_CARD", "沈岚", "守卫", Map.of("character_id", shen.getId(), "reveal_chapter_id", chapterIds.getFirst()));
        secretId = create("CHARACTER_RELATION", "密令", "秘密监视", Map.of("source_character_id", shen.getId(),
                "target_character_id", lin.getId(), "relation_type", "监视", "hidden_information", "隐藏密令",
                "known_by", List.of(shen.getId()), "reveal_chapter_id", chapterIds.getFirst())).id();
        futureId = create("WORLDVIEW", "未来规则", "未来秘密", Map.of("subtype", "RULE", "reveal_chapter_id", chapterIds.get(3))).id();
        context = new MangaAgentRuntimeContext(user.getId(), story.getId(), chapterIds.get(1), UUID.randomUUID(), UUID.randomUUID(),
                null, AgentTaskType.STORY_CHAT_READ, "story-chat:READ", 1L, null, "read-fixture");
    }

    @Test void directKnowledgeAndCharacterDetailsUseTheSameTemporalAndKnowingProjectionAsSearch() {
        var search = reads.search(context, "", null, null, lin.getId(), null, 50);
        assertThat(schema.write(search)).doesNotContain("隐藏密令", "秘密监视", "未来秘密");
        assertThatThrownBy(() -> reads.knowledge(context, secretId, null, lin.getId(), null, 8000)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reads.knowledge(context, futureId, null, shen.getId(), null, 8000)).isInstanceOf(BusinessException.class);
        assertThat(reads.knowledge(context, secretId, null, shen.getId(), null, 8000).contentJsonFragment()).contains("隐藏密令");
        var character = reads.character(context, lin.getId(), null, lin.getId(), null, 8000);
        assertThat(character.contentJsonFragment()).contains("旅行者").doesNotContain("UNPROJECTED AUTHOR SECRET", "隐藏密令", "未来秘密");
    }

    @Test void crossChapterReadsPreserveTheWriteTargetAndForeignStoryOrActorCannotRead() {
        var later = reads.knowledge(context, futureId, chapterIds.get(3), null, null, 8000);
        assertThat(later.contentJsonFragment()).contains("未来秘密");
        assertThat(later.anchorChapterId()).isEqualTo(chapterIds.get(3));
        assertThat(later.writeChapterId()).isEqualTo(context.chapterId());
        assertThat(context.chapterId()).isEqualTo(chapterIds.get(1));
        Long foreignChapter = tx.execute(status -> {
            var foreignStory = new Story(); foreignStory.setUser(user); foreignStory.setTitle("Other story"); em.persist(foreignStory);
            var chapter = new Chapter(); chapter.setStory(foreignStory); chapter.setChapterNumber(1); em.persist(chapter); return chapter.getId();
        });
        assertThatThrownBy(() -> reads.search(context, "", null, foreignChapter, null, null, 20)).hasMessageContaining("Cross-story");
        var forged = new MangaAgentRuntimeContext(user.getId() + 10000, context.storyId(), context.chapterId(), context.conversationId(),
                context.requestId(), null, context.taskType(), context.stepId(), 1L, null, "read-fixture");
        assertThatThrownBy(() -> reads.story(forged, null, 8000)).isInstanceOf(BusinessException.class);
    }

    @Test void listSnapshotsPageExactlyAndRejectChangedDataFiltersOrRequestIdentity() {
        var first = reads.listChapters(context, null, 2);
        var second = reads.listChapters(context, first.nextCursor(), 2);
        assertThat(first.items()).extracting(row -> row.get("chapter_number")).containsExactly(1, 2);
        assertThat(second.items()).extracting(row -> row.get("chapter_number")).containsExactly(3, 4);
        assertThat(second.hasMore()).isFalse();
        assertThatThrownBy(() -> reads.search(context, "", null, null, null, first.nextCursor(), 20)).hasMessageContaining("READ_SNAPSHOT_CHANGED");
        var otherRequest = new MangaAgentRuntimeContext(context.userId(), context.storyId(), context.chapterId(), context.conversationId(),
                UUID.randomUUID(), null, context.taskType(), context.stepId(), 1L, null, "read-fixture");
        assertThatThrownBy(() -> reads.listChapters(otherRequest, first.nextCursor(), 2)).hasMessageContaining("READ_SNAPSHOT_CHANGED");
        jdbc.update("UPDATE chapters SET display_title='Changed chapter' WHERE id=?", chapterIds.get(3));
        assertThatThrownBy(() -> reads.listChapters(context, first.nextCursor(), 2)).hasMessageContaining("READ_SNAPSHOT_CHANGED");
    }

    @Test void longDetailsHaveExplicitCompletePaginationAndRejectModifiedSource() {
        String description = "a".repeat(7999) + "😀" + "尾".repeat(2000);
        jdbc.update("UPDATE stories SET description=? WHERE id=?", description, story.getId());
        var first = reads.story(context, null, 8000);
        assertThat(first.truncated()).isTrue();
        var text = new StringBuilder(first.contentJsonFragment());
        var page = first;
        while (!page.nextCursor().isBlank()) {
            page = reads.story(context, page.nextCursor(), 8000);
            text.append(page.contentJsonFragment());
        }
        assertThat(schema.read(text.toString()).get("description")).isEqualTo(description);
        assertThat(page.end()).isEqualTo(page.totalLength());
        jdbc.update("UPDATE stories SET description='Changed' WHERE id=?", story.getId());
        assertThatThrownBy(() -> reads.story(context, first.nextCursor(), 8000)).hasMessageContaining("READ_SNAPSHOT_CHANGED");
    }

    @Test void referenceAssetsUseOnlyPersistedRevealedStageMetadata() {
        for (int index : List.of(0, 3)) jdbc.update("""
                INSERT INTO character_reference_images(story_id,character_profile_id,object_key,caption,appearance_version,reveal_chapter_id,from_chapter_id)
                VALUES (?,?,?,?,?,?,?)
                """, story.getId(), lin.getId(), "private/object/" + UUID.randomUUID() + ".png", "stage " + index,
                "appearance " + index, chapterIds.get(index), chapterIds.get(index));
        var current = reads.assets(context, lin.getId(), null, null, 20);
        assertThat(current.items()).hasSize(1);
        assertThat(schema.write(current)).contains("stage 0").doesNotContain("stage 3", "private/object/");
        assertThat(reads.assets(context, lin.getId(), chapterIds.get(3), null, 20).items()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM character_reference_images WHERE story_id=?", Long.class, story.getId())).isEqualTo(2);
    }

    @Test void candidatesAreAuthorAndChapterBoundAndReadsExposeStalenessWithoutChangingStatus() {
        long current = candidate(context.chapterId(), "Current proposal");
        long otherChapter = candidate(chapterIds.get(3), "Later proposal");
        var list = reads.listCandidates(context, null, null, null, 20);
        assertThat(list.items()).extracting(row -> row.get("candidate_id")).containsExactly(current);
        assertThat(list.items().getFirst().get("source_stale")).isEqualTo(false);
        assertThatThrownBy(() -> reads.candidate(context, otherChapter, null, 8000)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE chapters SET novel_content='Author changed source' WHERE id=?", context.chapterId());
        var result = reads.candidate(context, current, null, 8000);
        assertThat(result.contentJsonFragment()).contains("\"source_stale\":true", "\"is_confirmed_fact\":false")
                .doesNotContain("Author changed source", "Source chapter");
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_candidates WHERE id=?", String.class, current)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT version FROM knowledge_candidates WHERE id=?", Long.class, current)).isZero();
    }

    @Test void allEightNativeToolsPublishTypedSchemasAndExecuteRealReadServices() {
        long proposal = candidate(context.chapterId(), "Native candidate");
        var toolkit = new Toolkit();
        toolkit.registerTool(storyTools); toolkit.registerTool(knowledgeTools); toolkit.registerTool(assetTools); toolkit.registerTool(candidateTools);
        assertThat(toolkit.getToolSchemas()).extracting(tool -> tool.getName()).containsExactlyInAnyOrder(
                "get_story_context", "list_chapters", "search_story_knowledge", "get_knowledge_detail",
                "get_character_context", "list_reference_assets", "list_knowledge_candidates", "get_knowledge_candidate");
        var visible = reads.search(context, "", KnowledgeUnitType.CHARACTER_CARD, null, null, null, 20).items().getFirst();
        var inputs = new LinkedHashMap<String,Map<String,Object>>();
        inputs.put("get_story_context", Map.of()); inputs.put("list_chapters", Map.of("limit", 2));
        inputs.put("search_story_knowledge", Map.of("type", "CHARACTER_CARD"));
        inputs.put("get_knowledge_detail", Map.of("knowledge_id", visible.get("knowledge_id")));
        inputs.put("get_character_context", Map.of("character_id", lin.getId()));
        inputs.put("list_reference_assets", Map.of()); inputs.put("list_knowledge_candidates", Map.of("status", "PENDING"));
        inputs.put("get_knowledge_candidate", Map.of("candidate_id", proposal));
        var runtime = RuntimeContext.builder().put(MangaAgentRuntimeContext.class, context).build();
        for (var input : inputs.entrySet()) {
            var tool = toolkit.getTool(input.getKey());
            assertThat(tool.isReadOnly()).isTrue();
            assertThat(schema.write(tool.getParameters())).doesNotContain("runtime", "userId", "requestId");
            var block = new ToolUseBlock(UUID.randomUUID().toString(), input.getKey(), input.getValue(), schema.write(input.getValue()), Map.of());
            var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(block).input(input.getValue()).runtimeContext(runtime).build())
                    .block(Duration.ofSeconds(15));
            // Native DTO conversion leaves RUNNING for ReActAgent to finalize; the Harness case below proves SUCCESS.
            assertThat(result.getState()).as(input.getKey()).isEqualTo(ToolResultState.RUNNING);
            assertThat(result.getOutput()).isNotEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_candidates WHERE story_id=? AND status='PENDING'", Long.class, story.getId())).isEqualTo(1);
    }

    @Test void realHarnessModelReceivesAndConsumesAllEightReadToolResults() {
        candidate(context.chapterId(), "Harness candidate");
        var toolkit = new Toolkit();
        toolkit.registerTool(storyTools); toolkit.registerTool(knowledgeTools); toolkit.registerTool(assetTools); toolkit.registerTool(candidateTools);
        var names = List.of("get_story_context", "list_chapters", "search_story_knowledge", "get_knowledge_detail",
                "get_character_context", "list_reference_assets", "list_knowledge_candidates", "get_knowledge_candidate");
        var rounds = new AtomicInteger();
        Model model = new Model() {
            @Override public String getModelName() { return "isolated-eight-read-tools"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(tools).extracting(ToolSchema::getName).containsExactlyInAnyOrderElementsOf(names);
                int round = rounds.getAndIncrement();
                if (round > 0) {
                    assertThat(nativeJson(messages, "read-" + (round - 1))).isNotEmpty();
                    assertThat(messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                            .filter(result -> result.getId().startsWith("read-")).count()).isEqualTo(round);
                }
                ContentBlock block;
                if (round == names.size()) block = TextBlock.builder().text("All eight scoped read results consumed.").build();
                else {
                    Map<String,Object> input = switch (round) {
                        case 2 -> Map.of("type", "CHARACTER_CARD");
                        case 3 -> {
                            var item = ((List<?>)nativeJson(messages, "read-2").get("items")).getFirst();
                            yield Map.of("knowledge_id", ((Map<?,?>)item).get("knowledge_id"));
                        }
                        case 4 -> Map.of("character_id", lin.getId());
                        case 7 -> {
                            var item = ((List<?>)nativeJson(messages, "read-6").get("items")).getFirst();
                            yield Map.of("candidate_id", ((Map<?,?>)item).get("candidate_id"));
                        }
                        default -> Map.of();
                    };
                    block = new ToolUseBlock("read-" + round, names.get(round), input, schema.write(input), Map.of());
                }
                return Flux.just(ChatResponse.builder().content(List.of(block))
                        .finishReason(block instanceof ToolUseBlock ? "tool_calls" : "stop").build());
            }
        };
        var permissions = PermissionContextState.builder().mode(PermissionMode.DONT_ASK);
        names.forEach(name -> permissions.addAllowRule(name, new PermissionRule(name, null, PermissionBehavior.ALLOW, "read-test")));
        var agent = HarnessAgent.builder().name("eight-read-test").model(model).workspace(workspace).toolkit(toolkit)
                .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore())
                .permissionContext(permissions.build()).maxIters(12).disableCompaction().disableTranscript()
                .disableShellTool().disableFilesystemTools().disableWebTools().disableMemoryTools().disableMemoryHooks()
                .disableSubagents().disableDynamicSubagents().disableDynamicSkills().disableDefaultWorkspaceSkills()
                .disableWorkspaceContext().disableAtPathExpansion().disableToolsConfig().build();
        agent.getToolkit().removeTool("wait_async_results");
        try {
            var result = agent.call(List.of(Msg.builder().role(MsgRole.USER).content(TextBlock.builder()
                    .text("Read the story, chapters, knowledge, character, assets and candidates.").build()).build()),
                    RuntimeContext.builder().userId(context.userId().toString()).sessionId(context.requestId().toString())
                            .put(MangaAgentRuntimeContext.class, context).build()).block(Duration.ofSeconds(30));
            assertThat(result.getTextContent()).contains("All eight scoped read results consumed");
            assertThat(rounds).hasValue(9);
        } finally { agent.close(); }
    }

    private Map<String,Object> nativeJson(List<Msg> messages, String callId) {
        var result = messages.stream().filter(message -> message.getRole() == MsgRole.TOOL)
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .filter(block -> callId.equals(block.getId())).findFirst().orElseThrow();
        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        String json = result.getOutput().stream().map(block -> ((TextBlock)block).getText()).reduce("", String::concat);
        return schema.read(json);
    }

    private KnowledgeService.UnitView create(String type, String title, String body, Map<String,Object> data) {
        return knowledge.create(story.getId(), user.getId(), new KnowledgeService.UnitInput(type, title, body, "", data, 3, null, null));
    }
    private long candidate(Long chapterId, String title) {
        long candidateId = tx.execute(status -> {
            var candidate = new KnowledgeCandidate(); candidate.setUser(em.find(User.class, user.getId()));
            candidate.setStory(em.find(Story.class, story.getId())); candidate.setChapter(em.find(Chapter.class, chapterId));
            candidate.setSourceType("CHAPTER_CONTENT"); candidate.setSourceId(chapterId.toString()); candidate.setKnowledgeType(KnowledgeUnitType.WORLDVIEW);
            candidate.setTitle(title); candidate.setBody("Proposed rule"); candidate.setStructuredData(schema.write(Map.of("subtype", "RULE", "reveal_chapter_id", chapterId)));
            candidate.setProposedHash(ToolIdempotencyService.sha256(title)); em.persist(candidate); em.flush(); return candidate.getId();
        });
        var current = candidates.detail(story.getId(), candidateId, user.getId());
        jdbc.update("UPDATE knowledge_candidates SET source_hash=?,source_snapshot=?::jsonb WHERE id=?",
                current.currentSourceHash(), schema.write(current.currentSource()), candidateId);
        return candidateId;
    }
}
