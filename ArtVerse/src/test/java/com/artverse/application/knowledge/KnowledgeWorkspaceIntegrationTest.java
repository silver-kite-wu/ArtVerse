package com.artverse.application.knowledge;

import com.artverse.ai.EmbeddingClient;
import com.artverse.application.*;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.artverse.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({KnowledgeSchema.class,KnowledgeFactService.class,KnowledgeProjectionService.class,KnowledgeService.class,KnowledgeCandidateService.class,KnowledgeWorkspaceIntegrationTest.Config.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class KnowledgeWorkspaceIntegrationTest {
    @Container static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->db.getJdbcUrl()+"&stringtype=unspecified");r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);r.add("spring.flyway.enabled",()->true);
    }
    @TestConfiguration static class Config {
        @Bean EmbeddingConfigService config(){return mock(EmbeddingConfigService.class);}
        @Bean EmbeddingClient embedding(){return mock(EmbeddingClient.class);}
        @Bean ExecutorService indexExecutor(){return mock(ExecutorService.class);}
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager tm){return new TransactionTemplate(tm);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean ObjectStorageService storage(){return mock(ObjectStorageService.class);}
        @Bean ArtVerseProperties properties(){return new ArtVerseProperties();}
    }
    @Autowired EntityManager em;@Autowired TransactionTemplate tx;@Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeFactService facts;@Autowired KnowledgeProjectionService projections;@Autowired KnowledgeSchema schema;
    @Autowired KnowledgeService knowledge;@Autowired KnowledgeCandidateService candidates;@Autowired KnowledgeCandidateRepository candidateRepository;
    @Autowired ExecutorService indexExecutor;@Autowired EmbeddingClient embedding;
    @Autowired org.springframework.context.ApplicationContext context;
    ReaderCharacterChatService readerChat;
    User user;Story story;List<Long> chapters;CharacterProfile lin,shen;KnowledgeService.UnitView linCard,shenCard;
    @BeforeEach void fixture() {
        tx.executeWithoutResult(s->{user=new User();user.setUsername("kb_"+UUID.randomUUID());user.setEmail(user.getUsername()+"@example.test");user.setPasswordHash("isolated-fixture");user.setRole(Role.USER);em.persist(user);
            story=new Story();story.setUser(user);story.setTitle("知识库隔离回归");em.persist(story);chapters=new ArrayList<>();
            for(int i=1;i<=12;i++){Chapter c=new Chapter();c.setStory(story);c.setChapterNumber(i);c.setNovelContent("第"+i+"章原文");em.persist(c);chapters.add(c.getId());}
            lin=new CharacterProfile();lin.setStory(story);lin.setName("林舟");lin.setDescription("旅行者");em.persist(lin);
            shen=new CharacterProfile();shen.setStory(story);shen.setName("沈岚");shen.setDescription("守卫");em.persist(shen);
        });
        linCard=create("CHARACTER_CARD","林舟","旅行者",Map.of("character_id",lin.getId(),"reveal_chapter_id",ch(1),"first_appearance_chapter_id",ch(1)));
        shenCard=create("CHARACTER_CARD","沈岚","守卫",Map.of("character_id",shen.getId(),"reveal_chapter_id",ch(1)));
    }
    Long ch(int i){return chapters.get(i-1);}
    KnowledgeService.UnitView create(String type,String title,String body,Map<String,Object> data){return knowledge.create(story.getId(),user.getId(),new KnowledgeService.UnitInput(type,title,body,"",data,3,null,null));}
    KnowledgeFactService.Fact save(long unit,String kind,Map<String,Object> payload,int reveal,int from,Integer to){return knowledge.saveFactCommand(story.getId(),unit,user.getId(),new KnowledgeFactService.Command(null,null,kind,payload,ch(reveal),ch(from),to==null?null:ch(to),"FACT",null));}
    Map<String,Object> map(Object v){return (Map<String,Object>)v;}
    @Test void chronologyPreventsFutureLevelsAndDescriptionEditsPreserveStagesAndExitIsNotDeath() {
        save(linCard.id(),"LEVEL_STATE",Map.of("character_id",lin.getId(),"dimension","境界","system","灵修","label","炼气九层","evidence","第三章"),3,3,9);
        save(linCard.id(),"LEVEL_STATE",Map.of("character_id",lin.getId(),"dimension","境界","system","灵修","label","筑基","evidence","第九章"),9,9,null);
        assertThat(schema.write(projections.characters(story.getId(),ch(5)))).contains("炼气九层").doesNotContain("筑基");
        var data=new LinkedHashMap<>(linCard.structuredData());data.put("exit_chapter_id",ch(7));
        knowledge.updateVersioned(story.getId(),linCard.id(),user.getId(),3,new KnowledgeService.UnitInput("CHARACTER_CARD","林舟","旅行者更新","",data,3,null,null));
        assertThat(schema.write(projections.characters(story.getId(),ch(5)))).contains("炼气九层").doesNotContain("exit_chapter_id");
        assertThat(jdbc.queryForObject("SELECT terminal_confirmed FROM character_profiles WHERE id=?",Boolean.class,lin.getId())).isFalse();
        assertThat(schema.write(projections.characters(story.getId(),ch(10)))).contains("筑基");
    }
    @Test void ledgerUsesExactDecimalsUnknownBalancesAndOneAtomicTransfer() {
        long coins=facts.defineEntity(story.getId(),user.getId(),"CURRENCY","金币");
        Map<String,Object> gain=Map.of("character_id",lin.getId(),"asset_entity_id",coins,"action","GAIN","transaction_id","coins:"+story.getId(),"quantity","0.123456789123456789","evidence","得到金币");
        var f=save(linCard.id(),"ASSET_CHANGE",gain,5,5,null);var again=save(linCard.id(),"ASSET_CHANGE",gain,5,5,null);assertThat(again.id()).isEqualTo(f.id());
        var account=projections.holdings(story.getId(),lin.getId(),facts.all(story.getId(),ch(5),null)).getFirst();
        assertThat(account.get("balance")).isNull();assertThat(account.get("knownDelta")).isEqualTo("0.123456789123456789");
        long sword=facts.defineEntity(story.getId(),user.getId(),"ITEM","青铜剑");
        var event=create("TIMELINE","赠剑","林舟将剑送给沈岚",Map.of("participant_character_ids",List.of(lin.getId(),shen.getId()),"reveal_chapter_id",ch(7)));
        long eventId=facts.project(story.getId(),null,null).stream().filter(r->Objects.equals(r.get("id"),event.id())).mapToLong(r->((Number)r.get("factId")).longValue()).findFirst().orElseThrow();
        save(linCard.id(),"HOLDING_STATE",Map.of("character_id",lin.getId(),"asset_entity_id",sword,"quantity","1","evidence","拥有一把剑"),5,5,null);
        save(linCard.id(),"ASSET_CHANGE",Map.of("character_id",lin.getId(),"target_character_id",shen.getId(),"asset_entity_id",sword,"quantity","1","action","TRANSFER","transaction_id","sword:"+story.getId(),"source_event_id",eventId,"evidence","赠剑"),7,7,null);
        assertThat(projections.holdings(story.getId(),lin.getId(),facts.all(story.getId(),ch(7),null))).anySatisfy(a->{if(Objects.equals(a.get("assetEntityId"),sword))assertThat(a.get("balance")).isEqualTo("0");});
        assertThat(projections.holdings(story.getId(),shen.getId(),facts.all(story.getId(),ch(7),null))).anySatisfy(a->{assertThat(a.get("knownDelta")).isEqualTo("1");assertThat(a.get("balance")).isNull();});
        assertThat(projections.events(story.getId(),ch(7),lin.getId(),"NARRATIVE")).extracting(r->r.get("factId")).contains(eventId);
    }
    @Test void graphKeepsDirectionAndParallelEdgesAndRejectsForeignCharacters() {
        create("CHARACTER_RELATION","监视","沈岚监视林舟",Map.of("source_character_id",shen.getId(),"target_character_id",lin.getId(),"relation_type","监视","reveal_chapter_id",ch(2)));
        create("CHARACTER_RELATION","信任","林舟信任沈岚",Map.of("source_character_id",lin.getId(),"target_character_id",shen.getId(),"relation_type","信任","reveal_chapter_id",ch(3)));
        var graph=facts.graph(story.getId(),user.getId(),ch(5),null,false);assertThat(graph.edges()).hasSize(2);
        assertThat(graph.edges()).anySatisfy(e->{assertThat(e.get("source")).isEqualTo(shen.getId());assertThat(map(e.get("data")).get("relation_type")).isEqualTo("监视");});
        assertThatThrownBy(()->create("CHARACTER_RELATION","错误","错误",Map.of("source_character_id",lin.getId(),"target_character_id",99999999,"relation_type","信任"))).isInstanceOf(BusinessException.class);
    }
    @Test void flashbackOrderingDoesNotRevealEarlierAndPlansStayPrivate() {
        create("TIMELINE","十年前真相","未来秘密",Map.of("story_time","十年前","story_order",-10,"reveal_chapter_id",ch(12)));
        create("FORESHADOWING","玉佩","玉佩微光",Map.of("setup","玉佩微光","status","SEEDED","reveal_chapter_id",ch(2),"intended_payoff","未来秘密"));
        assertThat(schema.write(projections.events(story.getId(),ch(8),null,"STORY"))).doesNotContain("十年前真相","未来秘密");
        assertThat(schema.write(projections.foreshadowing(story.getId(),ch(8)))).contains("SEEDED").doesNotContain("未来秘密");
        assertThat(projections.events(story.getId(),ch(12),null,"STORY")).hasSize(1);
    }
    @Test void oldArrayCandidateCanBeViewedAndApprovedWithoutManualJsonRepair() {
        var candidate=candidates.replaceChapterExtraction(ch(2),user.getId(),List.of(new KnowledgeCandidateService.ExtractedCandidate("WORLDVIEW","月下规则","原文规则","",Map.of("subtype","RULE"),3,null,null))).getFirst();
        // Simulate a candidate persisted before the adapter existed, without changing its version.
        jdbc.update("UPDATE knowledge_candidates SET structured_data=CAST(? AS jsonb) WHERE id=?",schema.write(Map.of("subtype","RULE","limits",List.of("仅限夜间","需触碰目标"),"costs",List.of(),"exceptions",List.of())),candidate.id());
        var detail=candidates.detail(story.getId(),candidate.id(),user.getId());
        assertThat(detail.version()).isEqualTo(candidate.version());assertThat(detail.validationIssues()).isEmpty();
        assertThat(detail.structuredData()).containsEntry("limits","仅限夜间\n需触碰目标").containsEntry("costs",null).containsEntry("exceptions",null);
        assertThat(facts.browse(story.getId(),user.getId(),"WORLDVIEW","PENDING","",null,0,30).items().getFirst().get("structuredData")).isEqualTo(detail.structuredData());
        var approved=candidates.approve(story.getId(),candidate.id(),user.getId(),candidate.version());
        var stored=jdbc.queryForMap("SELECT structured_data FROM knowledge_units WHERE id=?",approved.approvedKnowledgeUnitId());
        assertThat(schema.read(stored.get("structured_data").toString())).containsEntry("limits","仅限夜间\n需触碰目标").containsEntry("costs",null).containsEntry("exceptions",null);
    }
    @Test void proseFormAndJsonImportUseTheSamePersistentContract() {
        var input=schema.read("{\"subtype\":\"RULE\",\"limits\":[\"限制一\",\"限制二\"],\"costs\":[],\"exceptions\":{\"条件\":\"月圆\"}}");
        var unit=create("WORLDVIEW","夜行规则","规则正文",input);
        var stored=schema.read(jdbc.queryForObject("SELECT structured_data::text FROM knowledge_units WHERE id=?",String.class,unit.id()));
        assertThat(stored).containsEntry("limits","限制一\n限制二").containsEntry("costs",null).containsEntry("exceptions","条件：月圆");
        var form=new LinkedHashMap<>(unit.structuredData());form.put("limits","限制修改\n第二行");form.put("costs","");
        knowledge.updateVersioned(story.getId(),unit.id(),user.getId(),unit.version(),new KnowledgeService.UnitInput("WORLDVIEW",unit.title(),unit.body(),"",form,3,null,null));
        assertThat(schema.write(facts.project(story.getId(),null,null))).contains("限制修改","第二行","月圆").doesNotContain("[object Object]");
    }
    @Test void candidatesFilterByTypeAndMissingSetupCanBeEditedBeforeApproval() {
        var proposed=candidates.replaceChapterExtraction(ch(2),user.getId(),List.of(
            new KnowledgeCandidateService.ExtractedCandidate("FORESHADOWING","玉佩","玉佩发光","",Map.of(),3,null,null),
            new KnowledgeCandidateService.ExtractedCandidate("TIMELINE","旅途","人物出发","",Map.of(),3,null,null)));
        assertThat(facts.browse(story.getId(),user.getId(),"FORESHADOWING","PENDING","玉佩",null,0,30).items()).hasSize(1);
        var c=candidates.detail(story.getId(),proposed.getFirst().id(),user.getId());
        assertThat(c.validationIssues()).extracting(KnowledgeSchema.Issue::path).contains("setup");
        assertThatThrownBy(()->candidates.approve(story.getId(),c.id(),user.getId(),c.version())).isInstanceOf(BusinessException.class);
        var d=new LinkedHashMap<>(c.structuredData());d.put("setup","玉佩发光");
        var fixed=candidates.edit(story.getId(),c.id(),user.getId(),c.version(),new KnowledgeService.UnitInput("FORESHADOWING",c.title(),c.body(),"",d,3,2,null),null,null);
        var approved=candidates.approve(story.getId(),c.id(),user.getId(),fixed.version());assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedKnowledgeUnitId()).isNotNull();
    }
    @Test void sourceChangesBlockApprovalAndConcurrentApprovalCommitsOnlyOneResult() throws Exception {
        var c=candidates.proposeCharacterProfile(lin,user.getId());
        jdbc.update("UPDATE character_profiles SET description='改变后的描述' WHERE id=?",lin.getId());
        assertThatThrownBy(()->candidates.approve(story.getId(),c.id(),user.getId(),c.version())).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getStatus()).isEqualTo(409));
        var detail=candidates.detail(story.getId(),c.id(),user.getId());var acknowledged=candidates.acknowledgeSource(story.getId(),c.id(),user.getId(),detail.version(),detail.currentSourceHash());
        CountDownLatch start=new CountDownLatch(1);try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<String> call=()->{start.await();try {return candidates.approve(story.getId(),c.id(),user.getId(),acknowledged.version()).status();}catch(BusinessException e){return String.valueOf(e.getStatus());}};
            Future<String> a=executor.submit(call),b=executor.submit(call);start.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("APPROVED","409");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_outbox_events WHERE aggregate_type='KNOWLEDGE_CANDIDATE' AND aggregate_id=? AND event_type='KNOWLEDGE_CANDIDATE_APPROVED'",Integer.class,c.id().toString())).isEqualTo(1);
    }
    @Test void symmetricRelationsCanonicalizeAndDuplicatesRollbackTheNewRoot() {
        create("CHARACTER_RELATION","朋友","二人是朋友",Map.of("source_character_id",shen.getId(),"target_character_id",lin.getId(),"direction","SYMMETRIC","relation_type","朋友","reveal_chapter_id",ch(2)));
        int before=jdbc.queryForObject("SELECT count(*) FROM knowledge_units WHERE story_id=?",Integer.class,story.getId());
        assertThatThrownBy(()->create("CHARACTER_RELATION","朋友","二人是朋友",Map.of("source_character_id",lin.getId(),"target_character_id",shen.getId(),"direction","SYMMETRIC","relation_type","朋友","reveal_chapter_id",ch(2)))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_units WHERE story_id=?",Integer.class,story.getId())).isEqualTo(before);
        var graph=facts.graph(story.getId(),user.getId(),ch(5),null,false);assertThat(graph.edges()).hasSize(1);assertThat(graph.edges().getFirst().get("source")).isEqualTo(lin.getId());
    }
    @Test void sameNamesStaySeparateAndForeignReferencesAndWrongCardOwnersAreRejected() {
        var second=create("CHARACTER_CARD","林舟","同名的另一个人",Map.of("reveal_chapter_id",ch(3)));
        assertThat(second.structuredData().get("character_id")).isNotEqualTo(lin.getId());
        assertThat(projections.characters(story.getId(),ch(5))).filteredOn(c->"林舟".equals(c.get("title"))).hasSize(2);
        Long foreign=tx.execute(s->{Story other=new Story();other.setUser(user);other.setTitle("其他作品");em.persist(other);Chapter c=new Chapter();c.setStory(other);c.setChapterNumber(1);em.persist(c);return c.getId();});
        assertThatThrownBy(()->create("TIMELINE","错误","错误",Map.of("reveal_chapter_id",foreign))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->save(linCard.id(),"LEVEL_STATE",Map.of("character_id",shen.getId(),"dimension","境界","system","灵修","label","筑基","evidence","错误绑定"),3,3,null)).isInstanceOf(BusinessException.class);
    }
    @Test void foreshadowingProgressionRetainsEarlyStateAndEditingTargetsLatestStage() {
        var root=create("FORESHADOWING","玉佩","玉佩微光",Map.of("setup","玉佩微光","status","SEEDED","reveal_chapter_id",ch(2)));
        var previous=facts.all(story.getId(),null,null).stream().filter(f->f.unitId()==root.id()).findFirst().orElseThrow();
        var event=create("TIMELINE","揭示","玉佩真相",Map.of("reveal_chapter_id",ch(12)));
        long eventId=facts.project(story.getId(),null,null).stream().filter(r->Objects.equals(r.get("id"),event.id())).mapToLong(r->((Number)r.get("factId")).longValue()).findFirst().orElseThrow();
        Map<String,Object> data=Map.of("setup","玉佩微光","status","RESOLVED","resolved_event_id",eventId,"resolution_evidence","第十二章揭晓");
        var resolved=knowledge.saveFactCommand(story.getId(),root.id(),user.getId(),new KnowledgeFactService.Command(null,null,"FORESHADOWING",Map.of("title","玉佩","body","玉佩微光","structured",data),ch(12),ch(12),null,"FACT",null,previous.id()));
        assertThat(schema.write(projections.foreshadowing(story.getId(),ch(8)))).contains("SEEDED").doesNotContain("RESOLVED","玉佩真相");
        knowledge.updateVersioned(story.getId(),root.id(),user.getId(),2,new KnowledgeService.UnitInput("FORESHADOWING","玉佩修正","微光的来源已查明","",data,3,null,null));
        assertThat(facts.fact(story.getId(),resolved.id()).revision()).isEqualTo(2);
        assertThat(facts.fact(story.getId(),resolved.id()).fromChapterId()).isEqualTo(ch(12));
        assertThat(schema.write(projections.foreshadowing(story.getId(),ch(8)))).contains("SEEDED").doesNotContain("来源已查明");
        assertThatThrownBy(()->create("FORESHADOWING","过早回收","过早回收",new LinkedHashMap<>(){{putAll(data);put("reveal_chapter_id",ch(8));}})).isInstanceOf(BusinessException.class);
    }
    @Test void knownByImagesAndFutureMetadataObeyTheSameChapterBoundary() {
        create("CHARACTER_RELATION","监视","秘密监视",Map.of("source_character_id",shen.getId(),"target_character_id",lin.getId(),"relation_type","监视","known_by",List.of(shen.getId()),"hidden_information","密令","reveal_chapter_id",ch(2)));
        assertThat(schema.write(facts.project(story.getId(),ch(5),lin.getId()))).doesNotContain("密令","秘密监视");
        assertThat(schema.write(facts.project(story.getId(),ch(5),shen.getId()))).contains("密令");
        jdbc.update("INSERT INTO character_reference_images(story_id,character_profile_id,object_key,is_cover,reveal_chapter_id) VALUES (?,?,?,true,?)",story.getId(),lin.getId(),"fixture/"+story.getId()+"/future.png",ch(9));
        assertThat(schema.write(projections.characters(story.getId(),ch(5)))).doesNotContain("future.png");
        assertThat(schema.write(projections.characters(story.getId(),ch(10)))).contains("future.png");
        var early=save(linCard.id(),"LEVEL_STATE",Map.of("character_id",lin.getId(),"dimension","境界","system","灵修","label","炼气","evidence","第三章"),3,3,9);
        assertThat(facts.all(story.getId(),ch(5),null).stream().filter(f->f.id()==early.id()).findFirst().orElseThrow().toChapterId()).isNull();
        assertThatThrownBy(()->facts.requireChapterUnreferenced(story.getId(),ch(9))).isInstanceOf(BusinessException.class);
    }
    @Test void borrowedEquipmentAndDistinctInstancesStaySeparateWithoutChangingOwnership() {
        long sword=facts.defineEntity(story.getId(),user.getId(),"ITEM","剑");
        for(String custody:List.of("OWNED","BORROWED")) save(linCard.id(),"HOLDING_STATE",Map.of("character_id",lin.getId(),"asset_entity_id",sword,"custody",custody,"instance_id",custody,"quantity","1","evidence","明确归属"),3,3,null);
        var accounts=projections.holdings(story.getId(),lin.getId(),facts.all(story.getId(),ch(5),null));assertThat(accounts).hasSize(2);assertThat(accounts).extracting(a->a.get("custody")).containsExactlyInAnyOrder("OWNED","BORROWED");
    }
    @Test void narrativeSortingHappensBeforePaginationAndEventCyclesAreExplicit() {
        List<Long> ids=new ArrayList<>();
        for(int i=0;i<4;i++){var event=create("TIMELINE","事件"+i,"经过",Map.of("reveal_chapter_id",ch(12-i),"story_order",i));ids.add(event.id());}
        var page=facts.browse(story.getId(),user.getId(),"TIMELINE","ACTIVE","",null,0,2,"","STORY",null,"");assertThat(page.items()).extracting(r->r.get("id")).containsExactly(ids.get(0),ids.get(1));
        assertThat(facts.browse(story.getId(),user.getId(),"TIMELINE","ACTIVE","",null,0,2,"","NARRATIVE",null,"").items()).extracting(r->r.get("id")).containsExactly(ids.get(3),ids.get(2));
        var a=facts.all(story.getId(),null,null).stream().filter(f->f.unitId()==ids.get(0)).findFirst().orElseThrow();
        var b=facts.all(story.getId(),null,null).stream().filter(f->f.unitId()==ids.get(1)).findFirst().orElseThrow();
        Map<String,Object> pa=new LinkedHashMap<>(a.payload());Map<String,Object> da=new LinkedHashMap<>(KnowledgeFactService.structured(pa));da.put("depends_on_event_ids",List.of(b.id()));pa.put("structured",da);
        knowledge.saveFactCommand(story.getId(),a.unitId(),user.getId(),new KnowledgeFactService.Command(a.id(),a.revision(),a.kind(),pa,a.revealChapterId(),null,null,"FACT",null));
        Map<String,Object> pb=new LinkedHashMap<>(b.payload());Map<String,Object> dbb=new LinkedHashMap<>(KnowledgeFactService.structured(pb));dbb.put("depends_on_event_ids",List.of(a.id()));pb.put("structured",dbb);
        assertThatThrownBy(()->knowledge.saveFactCommand(story.getId(),b.unitId(),user.getId(),new KnowledgeFactService.Command(b.id(),b.revision(),b.kind(),pb,b.revealChapterId(),null,null,"FACT",null))).isInstanceOf(BusinessException.class);
        dbb.put("causal_mode","TIME_LOOP");knowledge.saveFactCommand(story.getId(),b.unitId(),user.getId(),new KnowledgeFactService.Command(b.id(),b.revision(),b.kind(),pb,b.revealChapterId(),null,null,"FACT",null));
    }
    EmbeddingSpace activeSpace(){return tx.execute(s->{UserEmbeddingConfig c=new UserEmbeddingConfig();c.setUser(user);c.setModel("isolated-model");c.setStatus(EmbeddingConfigStatus.VERIFIED);em.persist(c);EmbeddingSpace space=new EmbeddingSpace();space.setUser(user);space.setConfig(c);space.setConfigVersion(1);space.setModelIdentifier("isolated-model");space.setDimensions(3);em.persist(space);jdbc.update("INSERT INTO story_embedding_spaces(story_id,embedding_space_id) VALUES (?,?)",story.getId(),space.getId());return space;});}
    @Test void rollbackLeavesNoApprovalFactsRevisionsOutboxOrIndexDispatchThenCommitDispatches() throws Exception {
        Thread.sleep(2200);activeSpace();clearInvocations(indexExecutor);
        var c=candidates.replaceChapterExtraction(ch(2),user.getId(),List.of(new KnowledgeCandidateService.ExtractedCandidate("FORESHADOWING","回滚","玉佩", "",Map.of("setup","玉佩"),3,null,null))).getFirst();
        int roots=jdbc.queryForObject("SELECT count(*) FROM knowledge_units WHERE story_id=?",Integer.class,story.getId());
        tx.executeWithoutResult(s->{candidates.approve(story.getId(),c.id(),user.getId(),c.version());s.setRollbackOnly();});
        assertThat(candidates.detail(story.getId(),c.id(),user.getId()).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_units WHERE story_id=?",Integer.class,story.getId())).isEqualTo(roots);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_jobs j JOIN knowledge_units u ON u.id=j.knowledge_unit_id WHERE u.story_id=?",Integer.class,story.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_outbox_events WHERE aggregate_id=? AND event_type='KNOWLEDGE_CANDIDATE_APPROVED'",Integer.class,c.id().toString())).isZero();
        Thread.sleep(2200);verifyNoInteractions(indexExecutor);
        candidates.approve(story.getId(),c.id(),user.getId(),c.version());verify(indexExecutor,timeout(4000)).submit(any(Runnable.class));
    }
    @Test void staleIndexJobCannotReplaceChunksAndWholeStoryCleanupReleasesHistoricalLinks() {
        EmbeddingSpace space=activeSpace();var unit=create("TIMELINE","索引","旧事实",Map.of("reveal_chapter_id",ch(2)));
        knowledge.updateVersioned(story.getId(),unit.id(),user.getId(),1,new KnowledgeService.UnitInput("TIMELINE","索引","新事实","",Map.of("reveal_chapter_id",ch(2)),3,null,null));
        long job=jdbc.queryForObject("SELECT id FROM knowledge_index_jobs WHERE knowledge_unit_id=? ORDER BY id DESC LIMIT 1",Long.class,unit.id());
        jdbc.update("UPDATE knowledge_index_jobs SET source_version=1,next_run_at=now()-interval '1 minute' WHERE id=?",job);
        jdbc.update("INSERT INTO knowledge_unit_chunks(knowledge_unit_id,embedding_space_id,source_version,chunk_index,priority,content,content_hash,embedding) VALUES (?,?,2,0,10,'新索引','test','[1,0,0]')",unit.id(),space.getId());
        tx.executeWithoutResult(s->org.springframework.test.util.ReflectionTestUtils.invokeMethod(knowledge,"process",job));
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_index_jobs WHERE id=?",String.class,job)).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_unit_chunks WHERE knowledge_unit_id=?",String.class,unit.id())).isEqualTo("新索引");
        facts.deleteForStory(story.getId(),user.getId());
        tx.executeWithoutResult(s->{jdbc.update("DELETE FROM stories WHERE id=?",story.getId());});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_facts WHERE story_id=?",Integer.class,story.getId())).isZero();
    }
    @Test void readerSnapshotsExcludeUnpublishedSourcesAndOtherCharactersSecretsAndFutureDeath() {
        readerChat=new ReaderCharacterChatService(context.getBean(ChapterRepository.class),context.getBean(CharacterProfileRepository.class),context.getBean(ReaderCharacterStageSnapshotRepository.class),context.getBean(ReaderCharacterConversationRepository.class),context.getBean(ReaderCharacterConversationMemberRepository.class),context.getBean(ReaderCharacterFriendRepository.class),context.getBean(ReaderStoryProgressRepository.class),context.getBean(ReaderCharacterMessageRepository.class),mock(ApiKeyService.class),mock(com.artverse.agent.AgentModelSpecFactory.class),mock(com.artverse.agent.gateway.AgentScopeHarnessAgentGateway.class),facts,schema);
        create("CHARACTER_RELATION","密令","守卫的秘密",Map.of("source_character_id",shen.getId(),"target_character_id",lin.getId(),"relation_type","监视","hidden_information","隐藏密令","known_by",List.of(shen.getId()),"reveal_chapter_id",ch(1)));
        create("TIMELINE","草稿秘密","尚未发布的事件",Map.of("reveal_chapter_id",ch(3)));
        jdbc.update("UPDATE character_profiles SET terminal_confirmed=true,terminal_chapter_number=12 WHERE id=?",lin.getId());
        jdbc.update("UPDATE chapters SET novel_is_published=true WHERE id IN (?,?)",ch(1),ch(5));
        tx.executeWithoutResult(s->{Chapter anchor=em.find(Chapter.class,ch(5));CharacterProfile profile=em.find(CharacterProfile.class,lin.getId());
            ReaderCharacterStageSnapshot snapshot=org.springframework.test.util.ReflectionTestUtils.invokeMethod(readerChat,"buildSnapshot",profile,anchor);
            assertThat(snapshot.getKnownFacts()).doesNotContain("隐藏密令","草稿秘密","尚未发布的事件");
            var options=readerChat.listCharacters(story.getId(),ch(5),user);
            assertThat(schema.write(options)).doesNotContain("TERMINAL_FUTURE","DEAD");
        });
    }
    @Test void upgradingPopulatedV52PreservesLegacyCardsAndMigrationRerunDoesNotDuplicateFacts() {
        String database="kb_legacy_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE DATABASE "+database);
        String url="jdbc:postgresql://"+db.getHost()+":"+db.getMappedPort(5432)+"/"+database+"?stringtype=unspecified";
        var old=org.flywaydb.core.Flyway.configure().dataSource(url,db.getUsername(),db.getPassword()).target("52").load();old.migrate();
        JdbcTemplate legacy=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,db.getUsername(),db.getPassword()));
        long uid=legacy.queryForObject("INSERT INTO users(username,email,password_hash) VALUES ('legacy','legacy@example.test','isolated-fixture') RETURNING id",Long.class);
        long sid=legacy.queryForObject("INSERT INTO stories(title,user_id) VALUES ('旧作品',?) RETURNING id",Long.class,uid);
        long cid=legacy.queryForObject("INSERT INTO character_profiles(story_id,name,description) VALUES (?,'旧人物','旧描述') RETURNING id",Long.class,sid);
        legacy.update("INSERT INTO knowledge_units(story_id,character_profile_id,type,title,body,structured_data,content_hash) VALUES (?,?,'CHARACTER_CARD','旧人物','旧描述','{\"aliases\":[\"旧别名\"]}','legacy-hash')",sid,cid);
        var latest=org.flywaydb.core.Flyway.configure().dataSource(url,db.getUsername(),db.getPassword()).load();latest.migrate();
        assertThat(legacy.queryForObject("SELECT scope FROM knowledge_fact_revisions",String.class)).isEqualTo("LEGACY_UNSCOPED");
        assertThat(legacy.queryForObject("SELECT payload::text FROM knowledge_fact_revisions",String.class)).contains("旧别名","旧描述");
        assertThat(latest.migrate().migrationsExecuted).isZero();assertThat(legacy.queryForObject("SELECT count(*) FROM knowledge_facts",Integer.class)).isEqualTo(1);
        var adapter=new KnowledgeFactService(legacy,schema);var projection=adapter.project(sid,null,null).getFirst();assertThat(map(projection.get("structuredData")).get("character_id")).isEqualTo(cid);
        assertThat(adapter.all(sid,null,null)).hasSize(1);
    }
    @Test void approvingCoreCandidateDoesNotAttributeUnchangedLevelFactsToTheNewSource() {
        var level=save(linCard.id(),"LEVEL_STATE",Map.of("character_id",lin.getId(),"dimension","境界","system","灵修","label","炼气","evidence","第三章"),3,3,null);
        var candidate=candidates.proposeCharacterProfile(lin,user.getId());candidates.approve(story.getId(),candidate.id(),user.getId(),candidate.version());
        assertThat(facts.evidence(story.getId(),user.getId(),level.id())).extracting(r->r.get("source_type")).containsOnly("AUTHOR");
        var core=facts.all(story.getId(),null,null).stream().filter(f->f.unitId()==linCard.id()&&f.kind().equals("CHARACTER_CORE")).findFirst().orElseThrow();
        assertThat(facts.evidence(story.getId(),user.getId(),core.id())).extracting(r->r.get("source_type")).contains("CHARACTER_PROFILE");
    }
    @Test void foreshadowingFiltersApplyBeforePaginationAndKeepCountsConsistent() {
        create("FORESHADOWING","玉佩","第一条",Map.of("setup","玉佩发光","related_character_ids",List.of(lin.getId()),"storyline","远行","expected_resolution_chapter_id",ch(8),"expected_resolution_end_chapter_id",ch(12)));
        create("FORESHADOWING","古剑","第二条",Map.of("setup","古剑低鸣","related_character_ids",List.of(shen.getId()),"storyline","守城"));
        var selected=facts.browse(story.getId(),user.getId(),"FORESHADOWING","ACTIVE","",null,0,1,"","NARRATIVE",null,"远行",lin.getId(),3,ch(10),ch(12));
        assertThat(selected.total()).isEqualTo(1);assertThat(selected.counts().get("FORESHADOWING")).isEqualTo(1);assertThat(selected.items().getFirst().get("title")).isEqualTo("玉佩");
        assertThat(facts.browse(story.getId(),user.getId(),"FORESHADOWING","ACTIVE","",null,0,1,"","NARRATIVE",null,"",null,4,null,null).total()).isZero();
        assertThatThrownBy(()->facts.browse(story.getId(),user.getId(),"FORESHADOWING","ACTIVE","",null,0,1,"","NARRATIVE",null,"",null,null,ch(12),ch(2))).isInstanceOf(BusinessException.class);
    }
    @Test void coreFactRevisionSynchronizesProfileAndStableEntityWithoutLosingStages() {
        save(linCard.id(),"IDENTITY_STATE",Map.of("character_id",lin.getId(),"dimension","身份","label","旅行者","evidence","第一章"),1,1,null);
        var core=facts.all(story.getId(),null,null).stream().filter(f->f.unitId()==linCard.id()&&f.kind().equals("CHARACTER_CORE")).findFirst().orElseThrow();
        var payload=new LinkedHashMap<>(core.payload());payload.put("title","林舟改名");payload.put("body","修订后的描述");
        knowledge.saveFactCommand(story.getId(),linCard.id(),user.getId(),new KnowledgeFactService.Command(core.id(),core.revision(),core.kind(),payload,ch(1),ch(1),null,"FACT",null));
        assertThat(jdbc.queryForObject("SELECT name FROM character_profiles WHERE id=?",String.class,lin.getId())).isEqualTo("林舟改名");
        assertThat(jdbc.queryForObject("SELECT name FROM knowledge_entities WHERE character_profile_id=?",String.class,lin.getId())).isEqualTo("林舟改名");
        assertThat(schema.write(projections.characters(story.getId(),null))).contains("修订后的描述","旅行者");
    }
    @Test void pendingRelationPreviewIsExplicitAndConfirmationTurnsItIntoAnOfficialEdge() {
        var candidate=candidates.replaceChapterExtraction(ch(2),user.getId(),List.of(new KnowledgeCandidateService.ExtractedCandidate("CHARACTER_RELATION","监视","沈岚监视林舟","",Map.of("source_character_id",shen.getId(),"target_character_id",lin.getId(),"relation_type","监视"),3,2,null))).getFirst();
        assertThat(facts.graph(story.getId(),user.getId(),ch(5),null,false).edges()).isEmpty();
        assertThat(facts.graph(story.getId(),user.getId(),ch(5),null,true).edges()).singleElement().satisfies(e->assertThat(e.get("pending")).isEqualTo(true));
        candidates.approve(story.getId(),candidate.id(),user.getId(),candidate.version());
        assertThat(facts.graph(story.getId(),user.getId(),ch(5),null,false).edges()).singleElement().satisfies(e->assertThat(e.get("pending")).isEqualTo(false));
    }
}
