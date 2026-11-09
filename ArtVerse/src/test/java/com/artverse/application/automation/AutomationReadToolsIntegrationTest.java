package com.artverse.application.automation;

import com.artverse.agent.*;
import com.artverse.application.*;
import com.artverse.application.tools.*;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.fasterxml.jackson.databind.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
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

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Flyway/PostgreSQL, native reflection tools and signed pagination; no user database or providers. */
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({AgentToolEffectService.class,AutomationStore.class,AutomationService.class,AutomationReadService.class,AutomationReadTokens.class,
        AutomationScheduleCalculator.class,AutomationReadToolsIntegrationTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class AutomationReadToolsIntegrationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"stringtype=unspecified");
        registry.add("spring.datasource.username",postgres::getUsername);registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("spring.flyway.enabled",()->"true");
    }
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);}
        @Bean ArtVerseProperties properties(){var properties=new ArtVerseProperties();properties.getSecrets().setEncryptionKey("read-token-fixture-signing-material");return properties;}
        @Bean MangaAgentRunService runs(){return mock(MangaAgentRunService.class);}
        @Bean NovelDraftReviewService reviews(){return mock(NovelDraftReviewService.class);}
        @Bean AutomationInputService inputs(){
            var inputs=mock(AutomationInputService.class);
            when(inputs.validateAndNormalize(anyLong(),any())).thenAnswer(invocation->invocation.getArgument(1));
            when(inputs.chapterIds(any(),anyString())).thenAnswer(invocation->{JsonNode payload=invocation.getArgument(0);
                if(payload.has("chapterIds")){var result=new ArrayList<Long>();payload.path("chapterIds").forEach(id->result.add(id.asLong()));return result;}
                return List.of(payload.path("chapterId").asLong());});
            when(inputs.config(anyLong(),any(),anyBoolean())).thenReturn(new UserProviderConfig("llm","fixture","Fixture model","fixture-secret-must-never-serialize","https://private-endpoint.example.test/v1","fixture-model",7L));
            return inputs;
        }
    }
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AutomationStore store;
    @Autowired AutomationReadService reads;
    @Autowired AutomationReadTokens tokens;
    @Autowired AutomationInputService inputs;

    @Test void previewIsSignedShortLivedAndCreatesNoPlanProposalRunOrQuotaReservation() {
        var f=fixture();long[] before=counts();
        var preview=reads.preview(f.context(),definition(f));
        assertThat(preview.authorizationGranted()).isFalse();assertThat(preview.quotaReserved()).isFalse();
        assertThat(preview.occurrences()).hasSize(1);assertThat(preview.expiresAt()).isEqualTo(preview.observedAt().plusSeconds(300));
        reads.requirePreview(f.context(),preview.previewRef(),preview.definitionHash());
        assertThat(counts()).containsExactly(before);
        String serialized=store.json(preview);
        assertThat(serialized).contains("Fixture model","fixture-model").doesNotContain("fixture-secret-must-never-serialize","private-endpoint","apiKey","api_key","base_url");
        assertThatThrownBy(()->reads.requirePreview(f.context(),preview.previewRef(),"changed")).hasMessageContaining("定义已变化");
        var claims=new LinkedHashMap<String,Object>(mapper.convertValue(tokens.verify(preview.previewRef()),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        claims.put("expires_at",Instant.now().minusSeconds(1).toString());
        assertThatThrownBy(()->reads.requirePreview(f.context(),tokens.sign(claims),preview.definitionHash())).hasMessageContaining("STALE_VERSION");
        String[] parts=preview.previewRef().split("\\.");
        assertThatThrownBy(()->reads.requirePreview(f.context(),parts[0]+".invalid",preview.definitionHash())).hasMessageContaining("引用无效");
    }

    @Test void detailsAreStoryAndActorScopedAndExposeOnlyModelDisplayFields() {
        var f=fixture();UUID id=plan(f);var own=reads.get(f.context(),id);
        assertThat(own.automationId()).isEqualTo(id);assertThat(own.version()).isZero();
        assertThat(own.definition().targets().chapterIds()).containsExactly(f.chapter());
        String serialized=store.json(own);
        assertThat(serialized).contains("Fixture model").doesNotContain("fixture-secret","private-endpoint","lease_owner","fencing_token");
        var foreign=fixture();UUID foreignPlan=plan(foreign);
        assertThatThrownBy(()->reads.get(f.context(),foreignPlan)).isInstanceOf(BusinessException.class);
        UUID sameActorForeignStory=plan(f,foreign.story(),foreign.chapter());
        assertThatThrownBy(()->reads.get(f.context(),sameActorForeignStory)).hasMessageContaining("不属于当前作品");
        when(inputs.config(eq(f.user()),any(),anyBoolean())).thenThrow(new BusinessException(400,"provider body must not leak"));
        var unavailable=reads.get(f.context(),id);
        assertThat(unavailable.model().available()).isFalse();assertThat(store.json(unavailable)).doesNotContain("provider body");
    }

    @Test void moreThanOneHundredSameTimestampRunsHaveStableCompletePagesAndNoForeignRows() {
        var f=fixture();UUID id=plan(f),other=plan(f);var expected=new HashSet<UUID>();
        for(int n=0;n<121;n++)expected.add(run(f,id));
        run(f,other);var foreign=fixture();run(foreign,plan(foreign));
        Set<UUID> seen=new HashSet<>();String cursor=null;int pages=0;
        do {
            var page=reads.runs(f.context(),id,cursor,25);pages++;
            for(var item:page.items()){assertThat(item.automationId()).isEqualTo(id);assertThat(seen.add(item.runId())).isTrue();}
            assertThat(page.hasMore()).isEqualTo(page.nextCursor()!=null);cursor=page.nextCursor();
        }while(cursor!=null);
        assertThat(pages).isEqualTo(5);assertThat(seen).isEqualTo(expected);
    }

    @Test void changedSnapshotsTamperedCursorsForeignPlanAndInvalidLimitsAreRejected() {
        var f=fixture();UUID id=plan(f),other=plan(f);run(f,id);run(f,id);
        var page=reads.runs(f.context(),id,null,1);assertThat(page.hasMore()).isTrue();
        assertThatThrownBy(()->reads.runs(f.context(),other,page.nextCursor(),1)).hasMessageContaining("不属于当前计划");
        assertThatThrownBy(()->reads.runs(f.context(),id,page.nextCursor()+"x",1)).hasMessageContaining("引用无效");
        assertThatThrownBy(()->reads.runs(f.context(),id,null,51)).hasMessageContaining("1–50");
        assertThatThrownBy(()->reads.runs(f.context(),id,null,0)).hasMessageContaining("1–50");
        run(f,id);
        assertThatThrownBy(()->reads.runs(f.context(),id,page.nextCursor(),1)).hasMessageContaining("STALE_VERSION");
    }

    @Test void allFourReadToolsDenyBackgroundAndSummarySources() {
        var f=fixture();UUID plan=plan(f),run=run(f,plan);
        jdbc.update("UPDATE ai_conversations SET legacy_import_key=? WHERE conversation_uuid=?","automation:"+UUID.randomUUID(),f.context().conversationId());
        assertThatThrownBy(()->reads.get(f.context(),plan)).hasMessageContaining("后台");
        assertThatThrownBy(()->reads.runs(f.context(),plan,null,20)).hasMessageContaining("后台");
        assertThatThrownBy(()->reads.runDetail(f.context(),run)).hasMessageContaining("后台");
        assertThatThrownBy(()->reads.preview(f.context(),definition(f))).hasMessageContaining("后台");
        jdbc.update("UPDATE ai_conversations SET legacy_import_key=NULL WHERE conversation_uuid=?",f.context().conversationId());
        jdbc.update("UPDATE manga_agent_runs SET run_attributes_json=jsonb_build_object('review_result','{}'::jsonb) WHERE id=?",f.agent());
        assertThatThrownBy(()->reads.get(f.context(),plan)).hasMessageContaining("审核总结");
    }

    @Test void runDetailsReturnRealArtifactReferencesAndImageStatesWithoutRawErrorsOrLeases() {
        var f=fixture();UUID plan=plan(f),run=run(f,plan);
        long child=child(f);UUID artifact=UUID.randomUUID();
        jdbc.update("INSERT INTO automation_run_execution(run_id,target_key,execution_kind,agent_run_id) VALUES (?,'agent','STORY_CHAT',?)",run,child);
        jdbc.update("INSERT INTO manga_agent_run_artifacts(artifact_uuid,run_id,artifact_type,status,schema_version,payload,checksum) VALUES (?,?,'NOVEL_CONTENT_DRAFT','VALIDATED','1','{\"content\":\"secret draft payload\"}'::jsonb,?)",artifact,child,"a".repeat(64));
        UUID task=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO scheduled_manga_generation(id,user_id,chapter_id,requested_at,timezone_id,state,chapter_version,model_name,scenes_snapshot,input_snapshot,snapshot_hash,prompt_version,idempotency_key)
                VALUES (?,?,?,now(),'Asia/Shanghai','SUCCEEDED',0,'fixture','','{}',?,'fixture',?)
                """,task,f.user(),f.chapter(),"b".repeat(64),task.toString());
        jdbc.update("INSERT INTO scheduled_manga_panel(task_id,image_number,prompt_snapshot,state,error_code,error_message) VALUES (?,1,'private prompt','FAILED','RESULT_UNCERTAIN','raw provider body secret')",task);
        jdbc.update("INSERT INTO automation_run_execution(run_id,target_key,execution_kind,scheduled_manga_id) VALUES (?,'chapter:fixture','MANGA_IMAGE',?)",run,task);
        jdbc.update("UPDATE automation_run SET error_code='arbitrary-provider-string',error_message='private endpoint and credential',lease_owner='private-worker' WHERE id=?",run);
        var detail=reads.runDetail(f.context(),run);
        assertThat(detail.artifacts()).singleElement().satisfies(value->assertThat(value.artifactId()).isEqualTo(artifact));
        assertThat(detail.images()).singleElement().satisfies(value->{assertThat(value.state()).isEqualTo("FAILED");assertThat(value.errorCode()).isEqualTo("RESULT_UNCERTAIN");});
        assertThat(detail.run().errorCode()).isEqualTo("UNCLASSIFIED");
        assertThat(store.json(detail)).doesNotContain("raw provider","private-worker","private endpoint","secret draft payload","private prompt","arbitrary-provider-string","fencing_token");
    }

    @Test void nativeSchemasHideIdentityAndAllFourToolsExecuteTheActualReadServices() throws Exception {
        var f=fixture();UUID plan=plan(f),run=run(f,plan);
        var support=mock(MangaToolSupport.class);when(support.resolveContext(any())).thenReturn(f.context());
        var facade=new AutomationReadAgentTools(mock(AutomationAgentTools.class),support);facade.setReadService(reads,mapper);
        Toolkit toolkit=new Toolkit();toolkit.registerTool(facade);
        for(String name:List.of("preview_automation","get_automation","list_automation_runs","get_automation_run")) {
            assertThat(toolkit.getTool(name).isReadOnly()).isTrue();
            assertThat(store.json(toolkit.getTool(name).getParameters())).doesNotContain("user_id","execution_owner","fencing_token","story_id");
        }
        assertThat(invoke(toolkit,"get_automation",Map.of("automation_id",plan.toString()))).contains(plan.toString());
        assertThat(invoke(toolkit,"list_automation_runs",Map.of("automation_id",plan.toString(),"limit",1))).contains(run.toString(),"has_more");
        assertThat(invoke(toolkit,"get_automation_run",Map.of("run_id",run.toString()))).contains(run.toString(),"frozen_definition");
        Map<String,Object> definition=Map.of("name","Native preview","executor","STORY_CHAT","instruction","Read this chapter",
                "schedule",Map.of("kind","ONCE","local_date_time",LocalDateTime.now().plusDays(1).withNano(0).toString(),"timezone","Asia/Shanghai"),
                "payload",Map.of("chapter_id",f.chapter(),"model_config_id",7,"model","fixture-model"));
        assertThat(invoke(toolkit,"preview_automation",Map.of("definition",definition))).contains("preview_ref","authorization_granted","quota_reserved");
        harnessConsumesReads(f, toolkit, plan, run, definition);
    }

    private void harnessConsumesReads(Fixture f, Toolkit toolkit, UUID plan, UUID run, Map<String,Object> definition) {
        var names = List.of("get_automation", "list_automation_runs", "get_automation_run", "preview_automation");
        var inputs = List.of(Map.<String,Object>of("automation_id", plan.toString()), Map.<String,Object>of("automation_id", plan.toString()),
                Map.<String,Object>of("run_id", run.toString()), Map.<String,Object>of("definition", definition));
        var round = new java.util.concurrent.atomic.AtomicInteger();
        io.agentscope.core.model.Model model = new io.agentscope.core.model.Model() {
            public String getModelName() { return "isolated-automation-reads"; }
            public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(List<Msg> messages,
                    List<io.agentscope.core.model.ToolSchema> tools, io.agentscope.core.model.GenerateOptions options) {
                int index = round.getAndIncrement();
                if (index > 0) {
                    var result = messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                            .filter(value -> ("automation-read-" + (index - 1)).equals(value.getId())).findFirst().orElseThrow();
                    assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
                    assertThat(store.json(result)).doesNotContain("fixture-secret-must-never-serialize", "private-endpoint");
                }
                ContentBlock output = index == names.size() ? TextBlock.builder().text("计划、运行和预览已读取，未执行任何任务。").build()
                        : new ToolUseBlock("automation-read-" + index, names.get(index), inputs.get(index), store.json(inputs.get(index)), Map.of());
                return reactor.core.publisher.Flux.just(io.agentscope.core.model.ChatResponse.builder().content(List.of(output))
                        .finishReason(output instanceof ToolUseBlock ? "tool_calls" : "stop").build());
            }
        };
        var permissions = io.agentscope.core.permission.PermissionContextState.builder().mode(io.agentscope.core.permission.PermissionMode.DONT_ASK);
        names.forEach(name -> permissions.addAllowRule(name, new io.agentscope.core.permission.PermissionRule(name, null,
                io.agentscope.core.permission.PermissionBehavior.ALLOW, "read-fixture")));
        var agent = io.agentscope.harness.agent.HarnessAgent.builder().name("automation-reads").model(model).workspace(workspace).toolkit(toolkit)
                .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore()).permissionContext(permissions.build()).maxIters(8)
                .disableCompaction().disableTranscript().disableShellTool().disableFilesystemTools().disableWebTools()
                .disableMemoryTools().disableMemoryHooks().disableSubagents().disableDynamicSubagents().disableDynamicSkills()
                .disableDefaultWorkspaceSkills().disableWorkspaceContext().disableAtPathExpansion().disableToolsConfig().build();
        var before = counts();
        try {
            var result = agent.call(List.of(Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("查看计划并预览").build()).build()),
                    RuntimeContext.builder().userId(Long.toString(f.user())).sessionId(f.context().requestId().toString()).build()).block(Duration.ofSeconds(30));
            assertThat(result.getTextContent()).contains("未执行任何任务");
            assertThat(round).hasValue(5); assertThat(counts()).containsExactly(before);
        } finally { agent.close(); }
    }

    private String invoke(Toolkit toolkit,String name,Map<String,Object> input) throws Exception {
        var call=new ToolUseBlock("call-"+UUID.randomUUID(),name,input,mapper.writeValueAsString(input),Map.of());
        var result=toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).input(input).runtimeContext(RuntimeContext.empty()).build()).block(Duration.ofSeconds(10));
        // Native DTO conversion is finalized by the agent loop, not Toolkit.callTool.
        assertThat(result.getState()).isEqualTo(ToolResultState.RUNNING);
        return result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText).reduce("",String::concat);
    }
    private Fixture fixture(){return new TransactionTemplate(transactions).execute(status->{
        var user=new User();user.setUsername("read_"+UUID.randomUUID());user.setEmail(user.getUsername()+"@example.test");user.setPasswordHash("fixture");user.setRole(Role.USER);em.persist(user);
        var story=new Story();story.setUser(user);story.setTitle("Read fixture");em.persist(story);
        var chapter=new Chapter();chapter.setStory(story);chapter.setChapterNumber(1);chapter.setNovelContent("Source");em.persist(chapter);
        var conversation=new MangaAgentConversation();conversation.setUser(user);conversation.setStory(story);conversation.setChapter(chapter);conversation.setConversationUuid(UUID.randomUUID());conversation.setConversationType(AiConversationType.STORY_CHAT);conversation.setTitle("Read fixture");em.persist(conversation);
        var run=new MangaAgentRun();run.setUser(user);run.setStory(story);run.setChapter(chapter);run.setConversation(conversation);run.setInputMessage("Read plans");run.setRunType(AgentRunType.STORY_CHAT);run.setRouteKey("READ");run.setStatus(MangaAgentRunStatus.RUNNING);run.setOwnerInstanceId("fixture-owner");run.setFencingToken(1L);run.setLeaseUntil(OffsetDateTime.now().plusMinutes(5));em.persist(run);em.flush();
        return new Fixture(user.getId(),story.getId(),chapter.getId(),run.getId(),new MangaAgentRuntimeContext(user.getId(),story.getId(),chapter.getId(),conversation.getConversationUuid(),run.getRequestId(),null,AgentTaskType.STORY_CHAT_READ,"story-chat:READ",1L,null,"fixture-owner"));
    });}
    private long child(Fixture f){return new TransactionTemplate(transactions).execute(status->{
        var user=em.getReference(User.class,f.user());var story=em.getReference(Story.class,f.story());var chapter=em.getReference(Chapter.class,f.chapter());
        var conversation=new MangaAgentConversation();conversation.setUser(user);conversation.setStory(story);conversation.setChapter(chapter);conversation.setConversationUuid(UUID.randomUUID());conversation.setConversationType(AiConversationType.STORY_CHAT);conversation.setTitle("Child");em.persist(conversation);
        var run=new MangaAgentRun();run.setUser(user);run.setStory(story);run.setChapter(chapter);run.setConversation(conversation);run.setInputMessage("Child read");run.setRunType(AgentRunType.STORY_CHAT);run.setRouteKey("READ");run.setStatus(MangaAgentRunStatus.WAITING_USER);em.persist(run);em.flush();return run.getId();
    });}
    private AutomationDefinition definition(Fixture f){return definition(f,f.story(),f.chapter());}
    private AutomationDefinition definition(Fixture f,long story,long chapter){return new AutomationDefinition("Read fixture","AGENT","STORY_CHAT","Read current chapter",
            new AutomationDefinition.Schedule("ONCE",LocalDateTime.now().plusDays(1).withNano(0).toString(),null,"Asia/Shanghai",List.of()),mapper.createObjectNode().put("storyId",story).put("chapterId",chapter).put("modelConfigId",7).put("model","fixture-model"),
            new AutomationDefinition.Limits(1,8,600),"RUN_LATEST","SKIP",null,null,"CONVERSATION",f.context().conversationId());}
    private UUID plan(Fixture f){return plan(f,f.story(),f.chapter());}
    private UUID plan(Fixture f,long story,long chapter){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,idempotency_key,request_hash) VALUES (?,?,'Read fixture','AGENT','STORY_CHAT',?::jsonb,'Asia/Shanghai',?,'fixture')",id,f.user(),store.json(definition(f,story,chapter)),id.toString());return id;}
    private UUID run(Fixture f,UUID plan){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,definition_snapshot,state,created_at) VALUES (?,?,?,0,'MANUAL',now(),?,?::jsonb,'SUCCEEDED','2026-11-01T00:00:00Z')",id,plan,f.user(),id.toString(),store.json(definition(f)));return id;}
    private long[] counts(){return new long[]{count("automation"),count("automation_run"),count("manga_agent_run_artifacts"),count("agent_usage_ledger")};}
    private long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    private record Fixture(long user,long story,long chapter,long agent,MangaAgentRuntimeContext context){}
}
