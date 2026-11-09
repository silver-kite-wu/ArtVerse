package com.artverse.agent.gateway.tools;

import com.artverse.agent.*;
import com.artverse.ai.EmbeddingClient;
import com.artverse.application.*;
import com.artverse.application.knowledge.*;
import com.artverse.application.imageproposal.*;
import com.artverse.application.imageproposal.ImageProposalViews.*;
import com.artverse.application.imagequeue.*;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.artverse.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

/** Production migrations, proposal services, native tools and real SQL; image business executors are substitutes. */
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({CreativeProposalArtifacts.class,SettingsProposalService.class,KnowledgeProposalService.class,
        KnowledgeCandidateService.class,KnowledgeService.class,KnowledgeSchema.class,KnowledgeFactService.class,KnowledgeProjectionService.class,
        AgentToolEffectService.class,MangaAgentRunService.class,MangaAgentRunSnapshotReader.class,AgentRunLeaseService.class,
        ImageProposalTokens.class,ImageProposalService.class,AgentArtifactAccessService.class,CreativeProposalIntegrationTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED) @Testcontainers(disabledWithoutDocker = true)
class CreativeProposalIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl()+"&stringtype=unspecified");r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("spring.flyway.enabled",()->true);
    }
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);}
        @Bean TransactionTemplate tx(PlatformTransactionManager manager){return new TransactionTemplate(manager);}
        @Bean ArtVerseProperties properties(){return new ArtVerseProperties();}
        @Bean ImageQueueProperties queue(){var p=new ImageQueueProperties();p.setProducerEnabled(true);return p;}
        @Bean ImageRequestInputs inputs(){return mock(ImageRequestInputs.class);}
        @Bean ImageRequestSubmissionService submission(){return mock(ImageRequestSubmissionService.class);}
        @Bean ImageRequestQueries queries(){return mock(ImageRequestQueries.class);}
        @Bean ImageRecoveryService recovery(){return mock(ImageRecoveryService.class);}
        @Bean EmbeddingConfigService embeddingConfig(){return mock(EmbeddingConfigService.class);}
        @Bean EmbeddingClient embedding(){return mock(EmbeddingClient.class);}
        @Bean ExecutorService indexExecutor(){return mock(ExecutorService.class);}
        @Bean ObjectStorageService storage(){return mock(ObjectStorageService.class);}
    }
    @Autowired EntityManager em;@Autowired JdbcTemplate jdbc;@Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;@Autowired KnowledgeSchema json;@Autowired SettingsProposalService settings;
    @Autowired CreativeProposalArtifacts artifacts;@Autowired KnowledgeProposalService knowledge;
    @Autowired KnowledgeCandidateService candidates;@Autowired KnowledgeService formal;
    @Autowired ImageProposalService image;@Autowired ImageRequestInputs inputs;@Autowired ImageRequestSubmissionService submissions;
    @Autowired ImageRequestQueries queries;@Autowired ImageRecoveryService recovery;@Autowired ImageQueueProperties queue;
    @Autowired MangaImageRepository images;@Autowired AgentRunLeaseService leases;@Autowired MangaAgentRunService runs;
    @Autowired StoryRepository stories;@Autowired CharacterProfileRepository profiles;
    @Autowired AgentArtifactAccessService artifactReads;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;

    @BeforeEach void imageSubstitutes() {
        reset(inputs,submissions,queries,recovery);queue.setProducerEnabled(true);
        when(inputs.provider(anyLong(),anyMap())).thenReturn(new UserProviderConfig("image","fixture","Fixture","private-fixture-secret","https://private.fixture.invalid","image-fixture",1L));
        when(inputs.preview(anyLong(),anyLong(),anyInt(),nullable(Long.class),nullable(String.class))).thenAnswer(i->Map.of("image_count",2,"image_number",i.getArgument(2),"scene","【第1格】一【第2格】二【第3格】三【第4格】四","reference_images",List.of(),"color_mode","color","prompt","Fixture only"));
        when(inputs.freezeReferences(anyList())).thenReturn(List.of());
        when(submissions.submit(anyLong(),anyLong(),nullable(Integer.class),anyString(),anyMap(),anyString())).thenAnswer(i->new ImageRequestSubmissionService.Accepted(manifest(i.getArgument(0),i.getArgument(1),UUID.fromString(i.getArgument(5)),null),false));
    }

    @Test void settingsAreExactReviewedAtomicAndIdempotentWithSeparateCandidate() {
        var f=fixture();var p=settings.story(f.c(),0,Map.of("title","Reviewed title","manga_style","watercolor"),null);
        assertThat(title(f)).isEqualTo("Creative fixture");assertThat(candidateCount(f)).isZero();
        var result=decideSettings(f,p,"confirm");assertThat(title(f)).isEqualTo("Reviewed title");assertThat(candidateCount(f)).isEqualTo(1);
        assertThat(result.get("status")).isEqualTo("COMMITTED");assertThat(receipt(result)).containsEntry("saved_version","1").containsEntry("confirmed_fact",false);
        assertThat(decideSettings(f,p,"confirm")).isEqualTo(result);assertThat(candidateCount(f)).isEqualTo(1);
        assertThatThrownBy(()->decideSettings(f,p,"discard")).hasMessageContaining("另一项决定");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_units WHERE story_id=?",Integer.class,f.story())).isZero();
    }
    @Test void forbiddenFieldsInvalidStylesAndStaleEditorVersionCannotPartiallyApply() {
        var f=fixture();assertThatThrownBy(()->settings.story(f.c(),0,Map.of("is_published","true"),null)).hasMessageContaining("白名单");
        assertThatThrownBy(()->settings.story(f.c(),0,Map.of("manga_style","unknown"),null)).hasMessageContaining("风格");
        var p=settings.story(f.c(),0,Map.of("title","Stale","description","Stale description"),null);
        tx.executeWithoutResult(status->{var story=stories.findById(f.story()).orElseThrow();story.setDescription("Author editor changed");stories.saveAndFlush(story);});
        assertThatThrownBy(()->decideSettings(f,p,"confirm")).hasMessageContaining("已被编辑");
        assertThat(title(f)).isEqualTo("Creative fixture");assertThat(candidateCount(f)).isZero();
        assertThat(artifacts.owned(f.user(),id(p),false).get("status")).isEqualTo("DRAFT");
    }
    @Test void characterCreateAndUpdateUseStableIdentityAndNeverMergeNames() {
        var f=fixture();var first=settings.character(f.c(),"CREATE",null,null,Map.of("name","同名","description","第一位"),null);
        var result=decideSettings(f,first,"confirm");long character=Long.parseLong(receipt(result).get("character_id").toString());
        assertThat(receipt(decideSettings(f,first,"confirm")).get("character_id")).isEqualTo(Long.toString(character));
        var second=settings.character(f.c(),"CREATE",null,null,Map.of("name","同名","description","第二位"),null);decideSettings(f,second,"confirm");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM character_profiles WHERE story_id=?",Integer.class,f.story())).isEqualTo(2);
        var update=settings.character(f.c(),"UPDATE",character,0L,Map.of("description","修改后"),null);
        assertThat(jdbc.queryForObject("SELECT description FROM character_profiles WHERE id=?",String.class,character)).isEqualTo("第一位");
        decideSettings(f,update,"confirm");assertThat(jdbc.queryForObject("SELECT version FROM character_profiles WHERE id=?",Long.class,character)).isEqualTo(1);
        assertThatThrownBy(()->settings.character(f.c(),"UPDATE",character,0L,Map.of("name","stale"),null)).hasMessageContaining("基准版本");
        assertThatThrownBy(()->settings.character(f.c(),"CREATE",null,null,Map.of("name","x","terminal_confirmed","true"),null)).hasMessageContaining("白名单");
    }
    @Test void artifactFailureRollsBackBothCharacterAndKnowledgeCandidate() {
        var f=fixture();var p=settings.character(f.c(),"CREATE",null,null,Map.of("name","回滚"),null);
        jdbc.execute("ALTER TABLE manga_agent_run_artifacts ADD CONSTRAINT c_test_rollback CHECK (artifact_type<>'CHARACTER_CHANGE' OR status<>'COMMITTED') NOT VALID");
        try {assertThatThrownBy(()->decideSettings(f,p,"confirm")).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally {jdbc.execute("ALTER TABLE manga_agent_run_artifacts DROP CONSTRAINT c_test_rollback");}
        assertThat(candidateCount(f)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM character_profiles WHERE story_id=?",Integer.class,f.story())).isZero();
        assertThat(decideSettings(f,p,"confirm").get("status")).isEqualTo("COMMITTED");
    }
    @Test void independentAiCandidateDoesNotSupersedeExtractionAndChecksSourceAgainOnApproval() {
        var f=fixture();long extraction=tx.execute(s->{var c=new KnowledgeCandidate();c.setUser(em.getReference(User.class,f.user()));c.setStory(em.getReference(Story.class,f.story()));c.setChapter(em.getReference(Chapter.class,f.chapter()));c.setSourceType("CHAPTER_EXTRACTION");c.setSourceId(Long.toString(f.chapter()));c.setKnowledgeType(KnowledgeUnitType.WORLDVIEW);c.setTitle("Existing batch");c.setBody("Existing body");c.setProposedHash("a".repeat(64));em.persist(c);return c.getId();});
        var draft=knowledge.draft(f.c(),knowledgeInput(f),null,null,source(f),"Fixture source",null);long id=Long.parseLong(draft.get("candidate_id").toString());
        assertThat(knowledge.draft(f.c(),knowledgeInput(f),null,null,source(f),"Fixture source",null)).isEqualTo(draft);
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_candidates WHERE id=?",String.class,extraction)).isEqualTo("PENDING");assertThat(candidateCount(f)).isEqualTo(2);
        jdbc.update("UPDATE chapters SET novel_content='Changed source',version=version+1 WHERE id=?",f.chapter());
        assertThatThrownBy(()->candidates.approve(f.story(),id,f.user(),Long.parseLong(draft.get("version").toString()))).hasMessageContaining("来源内容已变更");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_units WHERE story_id=?",Integer.class,f.story())).isZero();
    }
    @Test void knowledgeDraftValidatesTargetBaselineAndCanBeApprovedThroughExistingReview() {
        var f=fixture();assertThatThrownBy(()->knowledge.draft(f.c(),knowledgeInput(f),null,null,"0".repeat(64),"Fixture source",null)).hasMessageContaining("来源已变化");
        assertThatThrownBy(()->knowledge.draft(f.c(),knowledgeInput(f),123456L,1,source(f),"Fixture source",null)).hasMessageContaining("目标");
        var d=knowledge.draft(f.c(),knowledgeInput(f),null,null,source(f),"Fixture source",null);long candidate=Long.parseLong(d.get("candidate_id").toString());
        var approved=candidates.approve(f.story(),candidate,f.user(),Long.parseLong(d.get("version").toString()));assertThat(approved.status()).isEqualTo("APPROVED");assertThat(approved.approvedKnowledgeUnitId()).isNotNull();
        assertThat(knowledge.draft(f.c(),knowledgeInput(f),null,null,source(f),"Fixture source",null).get("candidate_id")).isEqualTo(Long.toString(candidate));
        assertThat(candidateCount(f)).isEqualTo(1);
    }
    @Test void stoppedSummaryBackgroundAndForeignActorsCannotProposeOrApprove() {
        var f=fixture();var p=settings.story(f.c(),0,Map.of("title","Never"),null);var foreign=fixture();
        assertThatThrownBy(()->settings.decide(foreign.user(),id(p),"confirm",p.get("commandHash").toString())).hasMessageContaining("不存在");
        jdbc.update("UPDATE ai_conversations SET legacy_import_key='automation:fixture' WHERE conversation_uuid=?",f.c().conversationId());
        assertThatThrownBy(()->settings.story(f.c(),0,Map.of("title","Never"),null)).hasMessageContaining("SCOPE_DENIED");
        assertThatThrownBy(()->decideSettings(f,p,"confirm")).hasMessageContaining("SCOPE_DENIED");
        jdbc.update("UPDATE ai_conversations SET legacy_import_key=null WHERE conversation_uuid=?",f.c().conversationId());
        jdbc.update("UPDATE manga_agent_runs SET run_attributes_json=jsonb_set(run_attributes_json,'{review_summary_continuation}','{}') WHERE id=?",f.run());
        assertThatThrownBy(()->image.models(f.c(),null,null,20)).hasMessageContaining("SCOPE_DENIED");
        assertThat(title(f)).isEqualTo("Creative fixture");
    }
    @Test void modelsUseSignedSnapshotPagesWithoutCredentialsOrEndpointDisclosure() throws Exception {
        var f=fixture();var page=image.models(f.c(),null,null,1);assertThat(page.hasMore()).isTrue();
        assertThat(mapper.writeValueAsString(page)).doesNotContain("private-fixture","api_key","base_url","encrypted-fixture");
        assertThat(image.models(f.c(),null,page.nextCursor(),1).items()).hasSize(1);
        var other=fixture();assertThatThrownBy(()->image.models(other.c(),null,page.nextCursor(),1)).hasMessageContaining("快照");
        jdbc.update("UPDATE user_api_keys SET label='Changed config' WHERE id=?",page.items().getFirst().configId());
        assertThatThrownBy(()->image.models(f.c(),null,page.nextCursor(),1)).hasMessageContaining("快照");
    }
    @Test void previewIsReadOnlyAndProposalRefRejectsTamperingScopeAndChangedInputs() {
        var f=fixture();var preview=image.preview(f.c(),selection(f,Mode.FIRST));
        assertThat(preview.authorizationGranted()).isFalse();assertThat(preview.quotaReserved()).isFalse();assertThat(preview.estimatedCost()).isEqualTo("UNKNOWN");
        assertThat(artifactCount(f)).isZero();verifyNoInteractions(submissions,recovery);
        assertThatThrownBy(()->image.propose(f.c(),preview.previewRef()+"x",null)).hasMessageContaining("引用无效");
        var other=fixture();assertThatThrownBy(()->image.propose(other.c(),preview.previewRef(),null)).hasMessageContaining("范围");
        when(inputs.freezeReferences(anyList())).thenReturn(List.of("Changed reference bytes"));
        assertThatThrownBy(()->image.propose(f.c(),preview.previewRef(),null)).hasMessageContaining("已变化");assertThat(artifactCount(f)).isZero();
    }
    @Test void imageAuthorConfirmationRevalidatesAndStableRetrySurvivesLostSubmissionResponse() {
        var f=fixture();var proposal=image.propose(f.c(),image.preview(f.c(),selection(f,Mode.FIRST)).previewRef(),null);verifyNoInteractions(submissions);
        doThrow(new IllegalStateException("response lost")).when(submissions).submit(anyLong(),anyLong(),nullable(Integer.class),anyString(),anyMap(),anyString());
        assertThatThrownBy(()->decideImage(f,proposal,"confirm")).hasMessageContaining("response lost");
        assertThat(artifacts.view(artifacts.owned(f.user(),id(proposal),false)).get("status")).isEqualTo("APPROVED_PENDING");
        assertThatThrownBy(()->decideImage(f,proposal,"discard")).hasMessageContaining("已批准");
        doAnswer(i->new ImageRequestSubmissionService.Accepted(manifest(f.user(),f.chapter(),UUID.fromString(i.getArgument(5)),null),false)).when(submissions).submit(anyLong(),anyLong(),nullable(Integer.class),anyString(),anyMap(),anyString());
        var committed=decideImage(f,proposal,"confirm");assertThat(receipt(committed)).containsEntry("accepted",true).containsEntry("generation_completed",false);
        var captor=org.mockito.ArgumentCaptor.forClass(String.class);verify(submissions,times(2)).submit(eq(f.user()),eq(f.chapter()),isNull(),eq("MANGA_GENERATE"),anyMap(),captor.capture());
        assertThat(captor.getAllValues()).containsOnly(captor.getValue());assertThat(decideImage(f,proposal,"confirm")).isEqualTo(committed);
    }
    @Test void imageDecisionRejectsChangedConfigTargetAndInputBeforeApproval() {
        var f=fixture();var p=image.propose(f.c(),image.preview(f.c(),selection(f,Mode.FIRST)).previewRef(),null);
        when(inputs.freezeReferences(anyList())).thenReturn(List.of("changed"));
        assertThatThrownBy(()->decideImage(f,p,"confirm")).hasMessageContaining("已变化");verifyNoInteractions(submissions);
        assertThat(artifacts.view(artifacts.owned(f.user(),id(p),false)).get("status")).isEqualTo("DRAFT");
        when(inputs.freezeReferences(anyList())).thenReturn(List.of());addImages(f);
        assertThatThrownBy(()->decideImage(f,p,"confirm")).hasMessageContaining("模式不匹配");verifyNoInteractions(submissions);
    }
    @Test void partialRegenerationKeepsBothStableRequestsAndOnlyOneFinalReceipt() {
        var f=fixture();addImages(f);var p=image.propose(f.c(),image.preview(f.c(),selection(f,Mode.REGENERATE)).previewRef(),null);
        var attempted=new ArrayList<String>();var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{String key=i.getArgument(5);attempted.add(key);if((int)i.getArgument(2)==2&&fail.getAndSet(false))throw new IllegalStateException("second unavailable");return new ImageRequestSubmissionService.Accepted(manifest(f.user(),f.chapter(),UUID.fromString(key),null),false);}).when(submissions).submit(anyLong(),anyLong(),anyInt(),anyString(),anyMap(),anyString());
        assertThatThrownBy(()->decideImage(f,p,"confirm")).hasMessageContaining("second unavailable");
        var partial=artifacts.view(artifacts.owned(f.user(),id(p),false));assertThat((List<?>)receipt(partial).get("accepted_requests")).hasSize(1);
        var result=decideImage(f,p,"confirm");assertThat(result.get("status")).isEqualTo("COMMITTED");assertThat((List<?>)receipt(result).get("accepted_requests")).hasSize(2);
        assertThat(attempted).hasSize(4);assertThat(attempted.get(0)).isEqualTo(attempted.get(2));assertThat(attempted.get(1)).isEqualTo(attempted.get(3));
    }
    @Test void queryIsScopedReadOnlyAndRecoveryUsesOnlyExactCurrentUserActions() throws Exception {
        var f=fixture();var m=requestFixture(f);var r=image.request(f.c(),m.rootId(),null);
        assertThat(r.complete()).isFalse();assertThat(r.images().getFirst().status()).isEqualTo("NEEDS_REVIEW");
        assertThat(r.images().getFirst().recoveryActions()).containsExactly(RecoveryAction.RECHECK,RecoveryAction.REQUEST_REVIEW);
        assertThat(mapper.writeValueAsString(r)).doesNotContain("private-fixture","raw prompt","api_key","ADMIN_DECISION","ABANDON_AND_REGENERATE");
        verify(queries,never()).get(anyLong(),any());verify(queries,never()).panel(any(),any());
        var p=image.proposeRecovery(f.c(),m.rootId(),m.panels().getFirst().operationId(),RecoveryAction.RECHECK,4,null);verifyNoInteractions(recovery);
        assertThat(decideImage(f,p,"confirm").get("status")).isEqualTo("COMMITTED");
        verify(recovery,times(1)).action(eq(f.user()),eq(m.rootId()),eq(m.panels().getFirst().operationId()),argThat(b->b.get("action").equals("RECHECK")&&!b.containsKey("confirm_external_ended")),eq(false));
        assertThatThrownBy(()->image.proposeRecovery(f.c(),m.rootId(),m.panels().getFirst().operationId(),RecoveryAction.RESUME_SAFE,4,null)).hasMessageContaining("允许动作");
        var other=fixture();assertThatThrownBy(()->image.request(other.c(),m.rootId(),null)).hasMessageContaining("SCOPE_DENIED");
    }

    @Test void concurrentAuthorConfirmationCreatesOneProfileAndReplaysTheSameReceipt() throws Exception {
        var f=fixture();var p=settings.character(f.c(),"CREATE",null,null,Map.of("name","并发审核"),null);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->decideSettings(f,p,"confirm"));var second=pool.submit(()->decideSettings(f,p,"confirm"));
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM character_profiles WHERE story_id=?",Integer.class,f.story())).isEqualTo(1);assertThat(candidateCount(f)).isEqualTo(1);
    }

    @Test void versionMigrationUpgradesExistingV58RowsWithoutChangingTheirContent() {
        // A second database inside this disposable container; never a developer or user database.
        String name="creative_upgrade_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE DATABASE "+name);
        String url="jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+name;
        org.flywaydb.core.Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).target("58").load().migrate();
        var old=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));
        long actor=old.queryForObject("INSERT INTO users(username,email,password_hash) VALUES ('upgrade_fixture','upgrade@example.test','isolated') RETURNING id",Long.class);
        long story=old.queryForObject("INSERT INTO stories(title,description,user_id) VALUES ('Preserved title','Preserved description',?) RETURNING id",Long.class,actor);
        long character=old.queryForObject("INSERT INTO character_profiles(story_id,name,description) VALUES (?,'Preserved character','Preserved profile') RETURNING id",Long.class,story);
        org.flywaydb.core.Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).load().migrate();
        assertThat(old.queryForMap("SELECT title,description,version FROM stories WHERE id=?",story)).containsEntry("title","Preserved title").containsEntry("description","Preserved description").containsEntry("version",0L);
        assertThat(old.queryForMap("SELECT name,description,version FROM character_profiles WHERE id=?",character)).containsEntry("name","Preserved character").containsEntry("description","Preserved profile").containsEntry("version",0L);
    }

    @Test void allEightToolsRoundTripThroughProductionRegistryNativeHarnessSqlAndRealAuthorDecisions() throws Exception {
        var f=fixture();var m=requestFixture(f);var selection=selection(f,Mode.FIRST);
        var support=new com.artverse.application.tools.MangaToolSupport(mock(AgentRunToolStatus.class));
        var audit=new AgentToolAuditService(mock(AgentRunToolStatus.class));
        var read=mock(AgentStoryKnowledgeReadService.class);var plans=mock(com.artverse.application.automation.AutomationAgentTools.class);
        var groups=new AgentToolGroupSupport(new com.artverse.application.tools.MangaHitlTools(audit,support,runs),
                new com.artverse.application.tools.MangaContextTools(mock(com.artverse.application.tools.MangaChapterContextReader.class)),
                new com.artverse.application.tools.StoryChatContextTools(support,audit,mock(ChapterAccessService.class)),
                new com.artverse.application.tools.StoryboardAgentTools(mock(com.artverse.application.tools.MangaStoryboardTools.class),mapper),
                new com.artverse.application.tools.StoryboardCommitAgentTools(mock(com.artverse.application.tools.MangaStoryboardTools.class)),
                new com.artverse.application.tools.StoryChatNovelContentTools(support,audit,mock(NovelContentArtifactService.class)),
                new com.artverse.application.tools.AutomationReadAgentTools(plans,support),new com.artverse.application.tools.AutomationManageAgentTools(plans,support,mapper,audit),
                new com.artverse.application.tools.StoryContextReadTools(read,support),new com.artverse.application.tools.KnowledgeReadTools(read,support),
                new com.artverse.application.tools.ReferenceAssetReadTools(read,support),new com.artverse.application.tools.KnowledgeCandidateReadTools(read,support),
                new com.artverse.application.tools.ArtifactReadAgentTools(support,audit,mock(AgentArtifactAccessService.class)),
                new com.artverse.application.tools.ArtifactDraftAgentTools(support,audit,mock(AgentArtifactAccessService.class)),
                new com.artverse.application.tools.NovelHistoryAgentTools(support,audit,mock(NovelHistoryAgentService.class)),
                new com.artverse.application.tools.SettingsDraftAgentTools(support,audit,settings),new com.artverse.application.tools.KnowledgeDraftAgentTools(support,audit,knowledge),
                new com.artverse.application.tools.ImageProposalAgentTools(support,audit,image));
        var registry=new AgentToolConfigurationRegistry(List.of(new MangaContextToolsStrategy(groups),new MangaConversationToolsStrategy(groups),new MangaDirectorToolsStrategy(groups),new MangaStoryboardToolsStrategy(groups),new StoryChatReadToolsStrategy(groups),new StoryChatWriteToolsStrategy(groups),new NoAgentToolsStrategy()));
        var request=new AgentRunRequest(Long.toString(f.user()),f.story(),f.chapter(),AgentTaskType.STORY_CHAT_READ,List.of(new AgentMessage("user","请修改设定和人物、追加知识候选、准备漫画生图恢复提案")),Map.of("step_id",f.c().stepId(),"fencing_token",f.c().fencingToken(),"execution_owner",f.c().executionOwner()),new AgentModelSpec("fixture","https://fixture.invalid","fixture","hash"),"",f.c().requestId(),f.c().conversationId(),BusinessSkillSelection.empty());
        var toolkit=new io.agentscope.core.tool.Toolkit();registry.configure(toolkit,request);
        var budget=new AgentBudgetService(jdbc,tx.getTransactionManager(),new ArtVerseProperties());
        var names=List.of("list_generation_models","draft_story_settings","draft_character_profile","draft_knowledge_change","preview_manga_generation","propose_manga_generation","get_generation_request","propose_generation_recovery");
        var results=new LinkedHashMap<String,JsonNode>();var round=new java.util.concurrent.atomic.AtomicInteger();
        io.agentscope.core.model.Model scripted=new io.agentscope.core.model.Model() {
            public String getModelName(){return "creative-sql-native-fixture";}
            public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,List<io.agentscope.core.model.ToolSchema> schemas,io.agentscope.core.model.GenerateOptions options) {
                int n=round.getAndIncrement();
                if(n>0) {
                    var result=messages.stream().flatMap(msg->msg.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream()).filter(r->("creative-"+(n-1)).equals(r.getId())).findFirst().orElseThrow();
                    String text=result.getOutput().stream().filter(io.agentscope.core.message.TextBlock.class::isInstance).map(io.agentscope.core.message.TextBlock.class::cast).map(io.agentscope.core.message.TextBlock::getText).reduce("",String::concat);
                    assertThat(result.getState()).as(text).isEqualTo(io.agentscope.core.message.ToolResultState.SUCCESS);
                    assertThat(text).doesNotContain("private-fixture","api_key","base_url","execution_binding");
                    try{var node=mapper.readTree(text);results.put(names.get(n-1),node.has("data")?node.path("data"):node);}catch(Exception error){throw new AssertionError(text,error);}
                }
                io.agentscope.core.message.ContentBlock output;
                if(n==names.size()) output=io.agentscope.core.message.TextBlock.builder().text("提案已准备，等待作者审核。").build();
                else {
                    Map<String,Object> input=switch(n) {
                        case 0->Map.of("limit",20);
                        case 1->Map.of("base_version",0,"patch",Map.of("title","Harness reviewed title"));
                        case 2->Map.of("mode","CREATE","patch",Map.of("name","Harness character","description","Fixture profile"));
                        case 3->Map.of("type","WORLDVIEW","title","Harness rule","body","Fixture rule","structured_data",Map.of("subtype","RULE","reveal_chapter_id",f.chapter()),"source_hash",source(f),"evidence","Fixture source");
                        case 4->Map.of("selection",mapper.convertValue(selection,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
                        case 5->Map.of("preview_ref",results.get("preview_manga_generation").path("preview_ref").asText());
                        case 6->Map.of("request_id",m.rootId().toString());
                        case 7->Map.of("request_id",m.rootId().toString(),"operation_id",m.panels().getFirst().operationId().toString(),"action","RECHECK","expected_recovery_version",4);
                        default->throw new AssertionError();
                    };
                    try{var call=new io.agentscope.core.message.ToolUseBlock("creative-"+n,names.get(n),input,mapper.writeValueAsString(input),Map.of());assertThat(budget.reserveToolCalls(request,List.of(call))).isTrue();output=call;}catch(Exception error){throw new AssertionError(error);}
                }
                return reactor.core.publisher.Flux.just(io.agentscope.core.model.ChatResponse.builder().content(List.of(output)).finishReason(output instanceof io.agentscope.core.message.ToolUseBlock?"tool_calls":"stop").build());
            }
        };
        var permissions=io.agentscope.core.permission.PermissionContextState.builder().mode(io.agentscope.core.permission.PermissionMode.DONT_ASK);
        names.forEach(name->permissions.addAllowRule(name,new io.agentscope.core.permission.PermissionRule(name,null,io.agentscope.core.permission.PermissionBehavior.ALLOW,"fixture")));
        var harness=io.agentscope.harness.agent.HarnessAgent.builder().name("creative-sql").model(scripted).workspace(workspace).toolkit(toolkit).stateStore(new io.agentscope.core.state.InMemoryAgentStateStore()).permissionContext(permissions.build()).maxIters(12)
                .disableCompaction().disableTranscript().disableShellTool().disableFilesystemTools().disableWebTools().disableMemoryTools().disableMemoryHooks().disableSubagents().disableDynamicSubagents().disableDynamicSkills().disableDefaultWorkspaceSkills().disableWorkspaceContext().disableAtPathExpansion().disableToolsConfig().build();
        try {
            var answer=harness.call(List.of(io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.USER).content(io.agentscope.core.message.TextBlock.builder().text("Prepare reviewed creative proposals").build()).build()),io.agentscope.core.agent.RuntimeContext.builder().put(MangaAgentRuntimeContext.class,f.c()).userId(Long.toString(f.user())).sessionId(UUID.randomUUID().toString()).build()).block(java.time.Duration.ofSeconds(45));
            assertThat(answer.getTextContent()).contains("等待作者审核");assertThat(round).hasValue(9);
        } finally {harness.close();}
        assertThat(title(f)).isEqualTo("Creative fixture");verifyNoInteractions(submissions,recovery);
        for(String name:List.of("draft_story_settings","draft_character_profile","propose_manga_generation","propose_generation_recovery")) {
            var p=mapper.convertValue(results.get(name),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            if(name.startsWith("draft_"))decideSettings(f,p,"confirm");else decideImage(f,p,"confirm");
        }
        long candidate=results.get("draft_knowledge_change").path("candidate_id").asLong();candidates.approve(f.story(),candidate,f.user(),results.get("draft_knowledge_change").path("version").asLong());
        assertThat(title(f)).isEqualTo("Harness reviewed title");assertThat(candidateCount(f)).isEqualTo(3);
        var receipts=jdbc.queryForList("SELECT metadata::text AS metadata FROM agent_usage_ledger WHERE request_id=? AND usage_kind='TOOL_CALL' AND jsonb_exists(metadata,'domain_effect')",f.c().requestId());
        assertThat(receipts).hasSize(5);
        for(var row:receipts) {var node=mapper.readTree(row.get("metadata").toString());assertThat(node.path("domain_effect").path("status").asText()).isEqualTo("DRAFT");assertThat(node.path("author_review_receipt").path("status").asText()).isEqualTo("COMMITTED");}
        var approvedImage=results.get("propose_manga_generation");var readReceipt=artifactReads.get(f.c(),UUID.fromString(approvedImage.path("artifactId").asText()),null,8000);
        assertThat(readReceipt.get("content").toString()).contains("COMMITTED","accepted_requests").doesNotContain("commands","provider_hash","execution_binding");
    }

    private Fixture fixture() {return tx.execute(s->{
        var u=new User();u.setUsername("creative_"+UUID.randomUUID());u.setEmail(u.getUsername()+"@example.test");u.setPasswordHash("isolated");u.setRole(Role.USER);em.persist(u);
        var story=new Story();story.setUser(u);story.setTitle("Creative fixture");em.persist(story);
        var chapter=new Chapter();chapter.setStory(story);chapter.setChapterNumber(1);chapter.setNovelContent("Fixture source");chapter.setScenesText("Fixture storyboard");chapter.setImageCount(4);em.persist(chapter);
        var conversation=new MangaAgentConversation();conversation.setConversationUuid(UUID.randomUUID());conversation.setTitle("Creative fixture");conversation.setConversationType(AiConversationType.STORY_CHAT);conversation.setUser(u);conversation.setStory(story);conversation.setChapter(chapter);em.persist(conversation);
        var run=new MangaAgentRun();run.setUser(u);run.setStory(story);run.setChapter(chapter);run.setConversation(conversation);run.setRunType(AgentRunType.STORY_CHAT);run.setRoute(com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION);run.setRouteKey("READ");run.setInputMessage("Prepare creative proposals");run.setRunAttributesJson("{\"story_chat_execution\":{\"profile\":\"local-tools-v1\",\"route\":\"READ\",\"task_type\":\"STORY_CHAT_READ\",\"step_id\":\"story-chat:READ\",\"version\":1}}");em.persist(run);
        for(int i=0;i<2;i++){var config=new UserApiKey();config.setUser(u);config.setSlot("image");config.setProvider("fixture");config.setLabel("Image fixture "+i);config.setModel("image-fixture");config.setBaseUrl("https://private.fixture.invalid");config.setApiKey("encrypted-fixture");config.setActive(true);em.persist(config);}
        em.flush();leases.claim(run);jdbc.update("UPDATE manga_agent_runs SET lease_until=clock_timestamp()+interval '10 minutes' WHERE id=?",run.getId());
        return new Fixture(u.getId(),story.getId(),chapter.getId(),run.getId(),new MangaAgentRuntimeContext(u.getId(),story.getId(),chapter.getId(),conversation.getConversationUuid(),run.getRequestId(),null,AgentTaskType.STORY_CHAT_READ,"story-chat:READ",run.getFencingToken(),null,run.getOwnerInstanceId()));
    });}
    private record Fixture(long user,long story,long chapter,long run,MangaAgentRuntimeContext c) {}
    private Selection selection(Fixture f,Mode mode) {var model=image.models(f.c(),null,null,20).items().getFirst();return new Selection(List.of(1,2),model.configId(),model.configVersion(),"image-fixture",mode,null,"1024x1024");}
    private Map<String,Object> decideSettings(Fixture f,Map<String,Object> p,String d) {return settings.decide(f.user(),id(p),d,p.get("commandHash").toString());}
    private Map<String,Object> decideImage(Fixture f,Map<String,Object> p,String d) {return image.decide(f.user(),id(p),d,p.get("commandHash").toString());}
    private UUID id(Map<String,Object> p){return UUID.fromString(p.get("artifactId").toString());}
    @SuppressWarnings("unchecked") private Map<String,Object> receipt(Map<String,Object> p){return (Map<String,Object>)p.get("receipt");}
    private String title(Fixture f){return jdbc.queryForObject("SELECT title FROM stories WHERE id=?",String.class,f.story());}
    private int candidateCount(Fixture f){return jdbc.queryForObject("SELECT count(*) FROM knowledge_candidates WHERE story_id=?",Integer.class,f.story());}
    private int artifactCount(Fixture f){return jdbc.queryForObject("SELECT count(*) FROM manga_agent_run_artifacts WHERE run_id=?",Integer.class,f.run());}
    private String source(Fixture f){return json.hash(Map.of("chapter_id",f.chapter(),"chapter_number",1,"chapter_version",0,"novel","Fixture source","storyboard","Fixture storyboard","complete",true));}
    private KnowledgeService.UnitInput knowledgeInput(Fixture f){return new KnowledgeService.UnitInput("WORLDVIEW","Candidate rule","Fixture rule","",Map.of("subtype","RULE","reveal_chapter_id",f.chapter()),3,null,null);}
    private void addImages(Fixture f){tx.executeWithoutResult(s->{for(int i=1;i<=2;i++){var image=new MangaImage();image.setChapter(em.getReference(Chapter.class,f.chapter()));image.setImageNumber(i);image.setImagePath("fixture-"+i+".png");em.persist(image);}em.flush();});}
    private Manifest requestFixture(Fixture f){var root=UUID.randomUUID();var op=UUID.randomUUID();var m=manifest(f.user(),f.chapter(),root,op);when(queries.manifest(anyLong(),eq(root))).thenReturn(m);when(queries.readOnlySubmission(m)).thenReturn("ACCEPTED");var state=new LinkedHashMap<String,Object>();state.put("status","NEEDS_REVIEW");state.put("status_source","db");state.put("recovery_version",4L);state.put("recovery_actions",List.of("RECHECK","REQUEST_REVIEW","ADMIN_DECISION","ABANDON_AND_REGENERATE"));state.put("prompt","raw prompt");when(queries.readOnlyPanel(eq(m),any())).thenReturn(state);return m;}
    private Manifest manifest(long user,long chapter,UUID root,UUID op){return new Manifest(1,user,root,"fixture","MANGA_GENERATE","CHAPTER_SERIAL","SERIAL_MODEL",chapter,null,null,new UserProviderConfig("image","fixture","Fixture","private-fixture-secret","https://private.fixture.invalid","image-fixture",1L),"1024x1024","color",List.of(),List.of(new Panel(op==null?UUID.randomUUID():op,1,"raw prompt",null,null,null,"hash",List.of(),"hash")),java.time.OffsetDateTime.now());}
}
