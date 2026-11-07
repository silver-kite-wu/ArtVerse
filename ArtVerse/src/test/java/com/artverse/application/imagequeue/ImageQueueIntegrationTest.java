package com.artverse.application.imagequeue;

import com.artverse.ai.*;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.artverse.storage.InMemoryObjectStorageService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.core.*;
import org.testcontainers.containers.*;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

/** Real isolated PostgreSQL/Redis/RabbitMQ; object storage is an in-memory test double; the paid provider is always a Mockito substitute. */
@SpringBootTest @ActiveProfiles("test") @Testcontainers(disabledWithoutDocker = true) @org.springframework.test.annotation.DirtiesContext
class ImageQueueIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("image_queue_test").withUsername("image_test").withPassword("isolated-test-password").withImagePullPolicy(image -> false);
    @Container static final GenericContainer<?> redis=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379).withCommand("redis-server","--appendonly","yes","--maxmemory-policy","noeviction").withImagePullPolicy(image -> false);
    @Container static final GenericContainer<?> rabbit=new GenericContainer<>("public.ecr.aws/docker/library/rabbitmq:4.1.8-management").withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER","image-test").withEnv("RABBITMQ_DEFAULT_PASS","isolated-test-broker-password").withEnv("RABBITMQ_DEFAULT_VHOST","artverse")
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*",1)).withImagePullPolicy(image -> false);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"stringtype=unspecified");p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);
        p.add("spring.data.redis.host",redis::getHost);p.add("spring.data.redis.port",()->redis.getMappedPort(6379));p.add("spring.data.redis.timeout",()->"500ms");
        p.add("spring.rabbitmq.host",rabbit::getHost);p.add("spring.rabbitmq.port",()->rabbit.getMappedPort(5672));p.add("spring.rabbitmq.username",()->"image-test");p.add("spring.rabbitmq.password",()->"isolated-test-broker-password");
        p.add("artverse.secrets.encryption-key",()->"isolated-image-queue-test-encryption-key");p.add("artverse.auth.risk.hmac-key",()->"isolated-image-queue-test-risk-key");
        p.add("artverse.image-queue.producer-enabled",()->true);p.add("artverse.image-queue.worker-enabled",()->false);p.add("artverse.image-queue.coordinator-enabled",()->false);
        p.add("artverse.automations.enabled",()->false);p.add("artverse.scheduled-manga.poll-interval-ms",()->3600000);
    }
    @Autowired ImageRequestSubmissionService submissions;
    @Autowired ImageCommandListener listener;
    @Autowired ImageRedisState states;
    @Autowired ImageRequestQueries queries;
    @Autowired ImageGenerationObjects objects;
    @Autowired ImageGenerationEvidence evidence;
    @Autowired ImageExecutionLeases leases;
    @Autowired ImageQueueProperties props;
    @Autowired ImageRecoveryService recovery;
    @Autowired ImageCommandPublisher publisher;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("imageParallelContainer") org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer parallelContainer;
    @Autowired ObjectMapper mapper;
    @Autowired ConnectionFactory connections;
    @Autowired StringRedisTemplate strings;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository users;
    @Autowired StoryRepository stories;
    @Autowired ChapterRepository chapters;
    @Autowired MangaImageRepository images;
    @Autowired ImageGenRecordRepository records;
    @Autowired ImageRequestEvents events;
    @Autowired com.artverse.application.MangaImageStorageService sharedImages;
    @Autowired com.artverse.application.imageproposal.ImageProposalService creativeImages;
    @Autowired com.artverse.application.CreativeProposalArtifacts creativeArtifacts;
    @Autowired com.artverse.application.ApiKeyService apiKeys;
    @Autowired com.artverse.application.AgentRunLeaseService agentLeases;
    @Autowired InMemoryObjectStorageService objectStorage;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockBean Image2Client model;
    @MockBean ImageExecutionFaults faults;
    long user,chapter;
    private static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=");

    @BeforeEach void fixture() throws Exception {
        reset(model,faults);when(model.generateScheduled(any(),any())).thenReturn(Mono.just(new GeneratedImage(PNG,"image/png")));
        records.deleteAll();images.deleteAll();strings.getConnectionFactory().getConnection().serverCommands().flushDb();
        var admin=new RabbitAdmin(connections);admin.initialize();
        for(String q:List.of("work.parallel.q","work.serial.q","quarantine.q","advance.q","advance.wait.5s.q","recovery.q","recovery.wait.30s.q","recovery.wait.120s.q"))admin.purgeQueue(ImageMessagingConfig.PREFIX+q);
        objectStorage.clear();
        tx.executeWithoutResult(status->{
            User owner=new User();owner.setUsername("iq_"+UUID.randomUUID().toString().replace("-",""));owner.setEmail(owner.getUsername()+"@example.test");owner.setPasswordHash("test-only");users.saveAndFlush(owner);user=owner.getId();
            Story story=new Story();story.setUser(owner);story.setTitle("Isolated image queue test");stories.saveAndFlush(story);
            Chapter c=new Chapter();c.setStory(story);c.setChapterNumber(1);c.setImageCount(4);c.setScenesText("[\"【第1格】山【第2格】水【第3格】云【第4格】人\",\"【第1格】山【第2格】水【第3格】云【第4格】人\",\"【第1格】山【第2格】水【第3格】云【第4格】人\",\"【第1格】山【第2格】水【第3格】云【第4格】人\"]");chapter=chapters.saveAndFlush(c).getId();
        });
    }
    private Map<String,Object> body() { return new TreeMap<>(Map.of("provider","openai","base_url","https://api.openai.com/v1","api_key","test-only-never-sent","model","test-image","prompt","A test image")); }
    private Manifest submit(int number) { return submissions.submit(user,chapter,number,"MANGA_SINGLE_GENERATE",body(),null).manifest(); }
    private String queue(Manifest m) { return ImageMessagingConfig.PREFIX+"work."+(m.serial()?"serial":"parallel")+".q"; }
    private boolean consume(String queue) throws Exception {
        var connection=connections.createConnection();var channel=connection.createChannel(false);
        try {
            var delivery=channel.basicGet(queue,false);if(delivery==null)return false;
            var properties=new MessageProperties();properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());properties.setConsumerQueue(queue);
            listener.receive(new Message(delivery.getBody(),properties),channel);return true;
        } finally { if(channel.isOpen())channel.close();connection.close(); }
    }
    private boolean consume(Manifest m) throws Exception { return consume(queue(m)); }
    private void oneFailure(String boundary) {
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation->{ if(first.getAndSet(false))throw new IllegalStateException("injected process interruption at "+boundary);return null; }).when(faults).hit(eq(boundary),any());
    }
    private void redelivery(Manifest m) throws Exception { org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->consume(m)); }

    @Test void missingProviderAndVersionFailBeforeTargetOrPublish() {
        assertThatThrownBy(()->submissions.submit(user,chapter,1,"MANGA_SINGLE_GENERATE",Map.of("model","test-image"),null)).isInstanceOf(ImageRequestException.class).hasMessage("无供应商，请选择生图供应商");
        assertThatThrownBy(()->submissions.submit(user,chapter,1,"MANGA_REGENERATE",body(),null)).isInstanceOf(ImageRequestException.class).hasMessageContaining("image_version");
        assertThat(strings.keys("image:target-active:*")).isEmpty();verifyNoInteractions(model);
    }

    @Test void reviewedCreativeProposalUsesRealQueueAndReadOnlyStatusWithNoProviderBeforeAuthorApproval() throws Exception {
        var context=tx.execute(status->{
            var owner=users.findById(user).orElseThrow();var c=chapters.findById(chapter).orElseThrow();
            apiKeys.saveProviderConfig(owner,null,new com.artverse.application.UserProviderConfig("image","openai","Controlled image fixture","test-only-never-sent","https://api.openai.com/v1","test-image"),true);
            var conversation=new MangaAgentConversation();conversation.setConversationUuid(UUID.randomUUID());conversation.setTitle("Creative queue review");conversation.setConversationType(AiConversationType.STORY_CHAT);conversation.setUser(owner);conversation.setStory(c.getStory());conversation.setChapter(c);entityManager.persist(conversation);
            var run=new MangaAgentRun();run.setUser(owner);run.setStory(c.getStory());run.setChapter(c);run.setConversation(conversation);run.setRunType(AgentRunType.STORY_CHAT);run.setRoute(com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION);run.setRouteKey("READ");run.setInputMessage("Prepare image proposal");run.setRunAttributesJson("{\"story_chat_execution\":{\"profile\":\"local-tools-v1\",\"route\":\"READ\",\"task_type\":\"STORY_CHAT_READ\",\"step_id\":\"story-chat:READ\",\"version\":1}}");entityManager.persist(run);entityManager.flush();agentLeases.claim(run);
            return new com.artverse.agent.MangaAgentRuntimeContext(user,c.getStory().getId(),chapter,conversation.getConversationUuid(),run.getRequestId(),null,com.artverse.agent.AgentTaskType.STORY_CHAT_READ,"story-chat:READ",run.getFencingToken(),null,run.getOwnerInstanceId());
        });
        var config=creativeImages.models(context,null,null,20).items().getFirst();
        var selection=new com.artverse.application.imageproposal.ImageProposalViews.Selection(List.of(1),config.configId(),config.configVersion(),"test-image",com.artverse.application.imageproposal.ImageProposalViews.Mode.FIRST,null,"1024x1024");
        var preview=creativeImages.preview(context,selection);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manga_agent_run_artifacts WHERE run_id=(SELECT id FROM manga_agent_runs WHERE request_id=?)",Integer.class,context.requestId())).isZero();
        var proposal=creativeImages.propose(context,preview.previewRef(),null);verifyNoInteractions(model);
        UUID artifact=UUID.fromString(proposal.get("artifactId").toString());var accepted=creativeImages.decide(user,artifact,"confirm",proposal.get("commandHash").toString());
        assertThat(accepted.get("status")).isEqualTo("COMMITTED");verifyNoInteractions(model);
        var receipt=(Map<?,?>)accepted.get("receipt");UUID root=UUID.fromString(((List<?>)receipt.get("accepted_requests")).getFirst().toString());var m=queries.manifest(user,root);
        assertThat(m.panels()).extracting(Panel::imageNumber).containsExactly(1);assertThat(m.executionLane()).isEqualTo("SERIAL_MODEL");assertThat(queries.snapshot(m).get("submission_status")).isEqualTo("ACCEPTED");
        publisher.advance(m);consume(ImageMessagingConfig.PREFIX+"advance.q");consume(m);publisher.advance(m);consume(ImageMessagingConfig.PREFIX+"advance.q");
        var stateBefore=states.read(states.state(m,m.panels().getFirst()));var result=creativeImages.request(context,root,null);
        assertThat(result.images().getFirst().status()).isEqualTo("SUCCEEDED");assertThat(result.images().getFirst().imageId()).isNotNull();assertThat(states.read(states.state(m,m.panels().getFirst()))).isEqualTo(stateBefore);
        assertThat(creativeImages.decide(user,artifact,"confirm",proposal.get("commandHash").toString())).isEqualTo(accepted);assertThat(images.findByChapterIdOrderByImageNumberAsc(chapter)).hasSize(1);
        assertThat(mapper.writeValueAsString(creativeArtifacts.list(user,chapter,context.conversationId(),null,20))).doesNotContain("test-only-never-sent","commands","api_key");verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void stableIdentityAndDifferentUuidJoinOneCanonicalOperation() throws Exception {
        Manifest first=submit(1);Manifest repeat=submit(1);assertThat(repeat.rootId()).isEqualTo(first.rootId());
        var newBody=body();newBody.put("request_id",UUID.randomUUID().toString());
        var joined=submissions.submit(user,chapter,1,"MANGA_SINGLE_GENERATE",newBody,null);
        assertThat(joined.joined()).isTrue();assertThat(joined.manifest().rootId()).isEqualTo(first.rootId());
        consume(first);verify(model,times(1)).generateScheduled(any(),any());
        assertThat(queries.snapshot(first).get("status")).isEqualTo("SUCCEEDED");
        assertThat(submissions.submit(user,chapter,1,"MANGA_SINGLE_GENERATE",body(),null).manifest().rootId()).isEqualTo(first.rootId());
        var completedAlias=body();completedAlias.put("request_id",UUID.randomUUID().toString());
        assertThat(submissions.submit(user,chapter,1,"MANGA_SINGLE_GENERATE",completedAlias,null).manifest().rootId()).isEqualTo(first.rootId());
    }
    @Test void sameIdentityDifferentInputAndBusyTargetDifferentIntentConflict() {
        Manifest m=submit(1);var changed=body();changed.put("prompt","Different input");
        assertThatThrownBy(()->submissions.submit(user,chapter,1,"MANGA_SINGLE_GENERATE",changed,null)).isInstanceOf(ImageRequestException.class).hasMessageContaining("输入不同");
        changed.put("request_id",UUID.randomUUID().toString());
        assertThatThrownBy(()->submissions.submit(user,chapter,1,"MANGA_SINGLE_GENERATE",changed,null)).isInstanceOf(ImageRequestException.class).hasMessageContaining("其他生图意图");
        assertThat(m.panels()).hasSize(1);verifyNoInteractions(model);
    }
    @Test void interruptedAfterPermitDoesNotCallProviderOnRedeliveryAndCanBeManuallyResolved() throws Exception {
        Manifest m=submit(1);oneFailure("AFTER_PERMIT");assertThatThrownBy(()->consume(m)).isInstanceOf(Exception.class);
        redelivery(m);verifyNoInteractions(model);Panel p=m.panels().getFirst();
        assertThat(queries.panel(m,p).get("status")).isEqualTo("NEEDS_REVIEW");
        var r=evidence.find(m,p);Map<String,Object> decision=new TreeMap<>(Map.of("action_id",UUID.randomUUID().toString(),"action","ADMIN_DECISION","expected_recovery_version",r.getRecoveryVersion(),"provider_attempt",r.getProviderAttempt(),"execution_token",r.getExecutionToken(),"evidence_source","test supplier fixture","evidence_reference","controlled no-send boundary","conclusion","NOT_ACCEPTED","confirm_external_ended",true));
        recovery.action(user,m.rootId(),p.operationId(),decision,true);
        assertThat(evidence.find(m,p).getAttemptDecision()).isEqualTo("FINAL");assertThat(states.value(states.target(m,p))).isNull();
        publisher.execute(m,p,0);consume(m);verifyNoInteractions(model);
    }
    @Test void uploadedCandidateSurvivesInterruptedWorkerAndRedisLoss() throws Exception {
        Manifest m=submit(1);oneFailure("AFTER_UPLOAD");assertThatThrownBy(()->consume(m)).isInstanceOf(Exception.class);
        strings.getConnectionFactory().getConnection().serverCommands().flushDb();
        redelivery(m);verify(model,times(1)).generateScheduled(any(),any());assertThat(evidence.find(m,m.panels().getFirst()).getStatus()).isEqualTo(ImageGenStatus.SUCCEEDED);
    }
    @Test void databaseCommitSurvivesRedisWriteWindowAndAckLoss() throws Exception {
        Manifest m=submit(1);oneFailure("AFTER_COMMIT");assertThatThrownBy(()->consume(m)).isInstanceOf(Exception.class);
        redelivery(m);publisher.execute(m,m.panels().getFirst(),0);consume(m);
        verify(model,times(1)).generateScheduled(any(),any());assertThat(records.findByUserIdAndRootRequestIdOrderByImageNumberAsc(user,m.rootId())).hasSize(1);
        assertThat(queries.panel(m,m.panels().getFirst()).get("image_version")).isEqualTo("0");
    }
    @Test void mandatoryReturnIsNotSuccessfulAcceptance() {
        Manifest m=submit(1);
        assertThatThrownBy(()->publisher.publish(publisher.command(m,m.panels().getFirst(),0,"IMAGE_EXECUTE"),"work.x","no-such-route")).isInstanceOf(ImageRequestException.class).hasMessageContaining("无法路由");
    }
    @Test void ownerLeasesAndSharedSerialQuotaFenceOtherInstances() {
        Manifest m=submit(1);Panel p=m.panels().getFirst();
        assertThat(states.lock("image:test-owner","old",Duration.ofSeconds(10))).isTrue();states.unlock("image:test-owner","new");assertThat(states.value("image:test-owner")).isEqualTo("old");
        var serial=new Manifest(m.schemaVersion(),m.userId(),m.rootId(),m.submissionHash(),m.operation(),m.executionMode(),"SERIAL_MODEL",m.chapterId(),m.assetGroupId(),m.conversationId(),m.provider(),m.size(),m.colorMode(),m.references(),m.panels(),m.createdAt());
        var first=leases.acquire(serial,p,true);assertThat(first).isNotNull();
        ImageExecutionLeases other=new ImageExecutionLeases(states,props);
        try { assertThat(other.acquire(serial,p,true)).isNull(); }finally { first.close();other.stop(); }
    }
    @Test void unresolvedSerialCallBlocksAnotherSerialButNotIndependentFirstGeneration() throws Exception {
        Manifest original=submit(1);Panel p=original.panels().getFirst();
        Manifest serial=new Manifest(1,user,original.rootId(),original.submissionHash(),"MANGA_GENERATE","SINGLE_IMAGE","SERIAL_MODEL",chapter,null,null,original.provider(),original.size(),original.colorMode(),original.references(),original.panels(),original.createdAt());
        evidence.permit(serial,p,objects.start(original,p,Duration.ofHours(1)),()->{});
        Manifest parallel=submit(2);Panel second=parallel.panels().getFirst();
        Manifest blocked=new Manifest(1,user,parallel.rootId(),parallel.submissionHash(),"MANGA_GENERATE","SINGLE_IMAGE","SERIAL_MODEL",chapter,null,null,parallel.provider(),parallel.size(),parallel.colorMode(),parallel.references(),parallel.panels(),parallel.createdAt());
        assertThatThrownBy(()->evidence.permit(blocked,second,objects.start(parallel,second,Duration.ofHours(1)),()->{})).isInstanceOf(ImageGenerationEvidence.NoPermit.class);
        consume(original);consume(parallel);verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void chapterHasIndependentControlResponsibilityAfterImageAck() throws Exception {
        Manifest m=submissions.submit(user,chapter,null,"MANGA_GENERATE",body(),UUID.randomUUID().toString()).manifest();
        assertThat(queries.snapshot(m).get("status")).isEqualTo("QUEUED");assertThat((List<?>)queries.snapshot(m).get("images")).isEmpty();
        assertThat(consume(ImageMessagingConfig.PREFIX+"advance.q")).isTrue();
        assertThat(consume(m)).isTrue();assertThat(evidence.find(m,m.panels().getFirst()).getStatus()).isEqualTo(ImageGenStatus.SUCCEEDED);
        // No browser and no image worker schedules the next panel. The broker's 5s TTL control does.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->consume(ImageMessagingConfig.PREFIX+"advance.q"));
        assertThat(consume(m)).isTrue();verify(model,times(2)).generateScheduled(any(),any());
        assertThat(evidence.find(m,m.panels().get(2))).isNull();
    }
    @Test void deletionAndRecreationRejectsOldImageIdEvenWithVersionZero() throws Exception {
        Manifest first=submit(1);consume(first);MangaImage old=images.findByChapterIdAndImageNumber(chapter,1).orElseThrow();
        var input=body();input.put("expected_image_id",old.getId());input.put("expected_image_version",old.getVersion().toString());
        images.deleteById(old.getId());images.flush();
        tx.executeWithoutResult(status->{ MangaImage replacement=new MangaImage();replacement.setChapter(chapters.getReferenceById(chapter));replacement.setImageNumber(1);replacement.setImagePath("existing.png");images.saveAndFlush(replacement); });
        assertThatThrownBy(()->submissions.submit(user,chapter,1,"MANGA_REGENERATE",input,null)).isInstanceOf(ImageRequestException.class).hasMessageContaining("已变化");
        verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void privateConditionalManifestKeepsOriginalInputAndIsNotServedFromImageBucket() {
        Manifest m=submit(1);Manifest second=objects.freeze(m);assertThat(second).isEqualTo(m);
        var altered=new Manifest(m.schemaVersion(),m.userId(),m.rootId(),"different",m.operation(),m.executionMode(),m.executionLane(),m.chapterId(),m.assetGroupId(),m.conversationId(),m.provider(),m.size(),m.colorMode(),m.references(),m.panels(),m.createdAt());
        assertThatThrownBy(()->objects.freeze(altered)).isInstanceOf(ImageRequestException.class);
        assertThat(objects.load(user,m.rootId()).submissionHash()).isEqualTo(m.submissionHash());
    }
    @Test void actualListenerUsesVirtualThreadAndRenewsDuringBlockingProvider() throws Exception {
        Duration oldLease=props.getExecutionLease(),oldRenew=props.getExecutionRenew();
        props.setExecutionLease(Duration.ofSeconds(1));props.setExecutionRenew(Duration.ofMillis(200));
        var virtual=new java.util.concurrent.atomic.AtomicBoolean();
        when(model.generateScheduled(any(),any())).thenAnswer(call->{
            virtual.set(Thread.currentThread().isVirtual());
            return Mono.fromCallable(()-> { Thread.sleep(1800);return new GeneratedImage(PNG,"image/png"); });
        });
        Manifest m=submit(1);
        try {
            parallelContainer.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->"SUCCEEDED".equals(queries.snapshot(m).get("status")));
            assertThat(virtual).isTrue();verify(model,times(1)).generateScheduled(any(),any());
        } finally { parallelContainer.stop();props.setExecutionLease(oldLease);props.setExecutionRenew(oldRenew); }
    }
    @Test void publisherConfirmTimeoutRetainsIdentityAndDuplicateCommandsDoNotChargeTwice() throws Exception {
        props.setPublishConfirmTimeout(Duration.ZERO);
        try { assertThatThrownBy(()->submit(1)).isInstanceOf(ImageRequestException.class); }
        finally { props.setPublishConfirmTimeout(Duration.ofSeconds(5)); }
        Manifest m=submit(1);consume(m);consume(m);
        assertThat(queries.snapshot(m).get("status")).isEqualTo("SUCCEEDED");verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void brokerRestartKeepsConfirmedDurableCommand() throws Exception {
        Manifest m=submit(1);
        // Restart Rabbit itself without Docker Desktop reallocating the ephemeral host port.
        assertThat(rabbit.execInContainer("rabbitmqctl","stop_app").getExitCode()).isZero();
        assertThat(rabbit.execInContainer("rabbitmqctl","start_app").getExitCode()).isZero();
        org.awaitility.Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(45)).until(()->consume(m));
        assertThat(queries.snapshot(m).get("status")).isEqualTo("SUCCEEDED");verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void queueOverflowRejectsPublisherInsteadOfDroppingOldMessage() throws Exception {
        String name="image-test-overflow-"+UUID.randomUUID();var admin=new RabbitAdmin(connections);
        var queue=QueueBuilder.durable(name).quorum().maxLength(1).overflow(QueueBuilder.Overflow.rejectPublish).build();
        admin.declareQueue(queue);admin.declareBinding(new Binding(name,Binding.DestinationType.QUEUE,ImageMessagingConfig.PREFIX+"work.x","test-limit",null));
        try {
            Manifest m=submit(1);var command=publisher.command(m,m.panels().getFirst(),0,"IMAGE_EXECUTE");publisher.publish(command,"work.x","test-limit");
            // Quorum queue limits are approximate: in-flight accepted entries can overshoot.
            boolean rejected=false;
            for(int i=0;i<30&&!rejected;i++)try { publisher.publish(command,"work.x","test-limit"); }
            catch(ImageRequestException expected) { rejected=true; }
            assertThat(rejected).isTrue();
            assertThat((Integer)admin.getQueueProperties(name).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).isPositive();
            var connection=connections.createConnection();var channel=connection.createChannel(false);
            try { assertThat(mapper.readValue(channel.basicGet(name,true).getBody(),Command.class).eventId()).isEqualTo(command.eventId()); }
            finally { channel.close();connection.close(); }
        }finally { admin.deleteQueue(name); }
    }
    @Test void oldHistoryTimeoutCannotFinalizeMqOrAutomationEvidence() throws Exception {
        Manifest m=submit(1);var p=m.panels().getFirst();evidence.permit(m,p,objects.start(m,p,Duration.ofHours(1)),()->{});
        tx.executeWithoutResult(status->{
            jdbc.update("UPDATE image_gen_records SET created_at=now()-interval '1 day' WHERE request_id=?",p.operationId());
            assertThat(records.markExpiredRunningAsFailed(user,OffsetDateTime.now().minusMinutes(12),OffsetDateTime.now(),"legacy timeout")).isZero();
        });
        assertThat(evidence.find(m,p).getAttemptDecision()).isEqualTo("UNRESOLVED");
    }
    @Test void concurrentDifferentIdsShareOneConfirmedCanonical() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Manifest> submit=()-> { start.await();var input=body();input.put("request_id",UUID.randomUUID().toString());return submissions.submit(user,chapter,1,"MANGA_SINGLE_GENERATE",input,null).manifest(); };
            var a=pool.submit(submit);var b=pool.submit(submit);start.countDown();
            Manifest first=a.get(20,TimeUnit.SECONDS);assertThat(b.get(20,TimeUnit.SECONDS).rootId()).isEqualTo(first.rootId());
            consume(first);consume(first);verify(model,times(1)).generateScheduled(any(),any());
        }
    }
    @Test void lostRedisTargetRejoinsDurableUnresolvedOperation() throws Exception {
        Manifest m=submit(1);Panel p=m.panels().getFirst();
        evidence.permit(m,p,objects.start(m,p,Duration.ofHours(1)),()->{});
        strings.getConnectionFactory().getConnection().serverCommands().flushDb();
        var input=body();input.put("request_id",UUID.randomUUID().toString());
        assertThat(submissions.submit(user,chapter,1,"MANGA_SINGLE_GENERATE",input,null).manifest().rootId()).isEqualTo(m.rootId());
        assertThat(states.value(states.target(m,p))).contains(p.operationId().toString());verifyNoInteractions(model);
    }
    @Test void getRebuildsCommittedResultAndTerminalExpiryWithoutCallingProvider() throws Exception {
        Manifest m=submit(1);consume(m);Panel p=m.panels().getFirst();
        strings.getConnectionFactory().getConnection().serverCommands().flushDb();
        var view=queries.panel(m,p);assertThat(view.get("status")).isEqualTo("SUCCEEDED");assertThat(view.get("status_source")).isEqualTo("rebuild");
        assertThat(strings.getExpire(states.state(m,p))).isBetween(29L*86400,30L*86400);
        verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void entireChapterOnlyFillsMissingPanelsAndDuplicateControlsConverge() throws Exception {
        Manifest existing=submit(2);consume(existing);MangaImage saved=images.findByChapterIdAndImageNumber(chapter,2).orElseThrow();
        Manifest m=submissions.submit(user,chapter,null,"MANGA_GENERATE",body(),UUID.randomUUID().toString()).manifest();
        assertThat(m.panels()).extracting(Panel::imageNumber).containsExactly(1,3,4);
        for(int i=0;i<3;i++) { publisher.advance(m);consume(ImageMessagingConfig.PREFIX+"advance.q");consume(m); }
        publisher.advance(m);consume(ImageMessagingConfig.PREFIX+"advance.q");
        assertThat(queries.snapshot(m).get("status")).isEqualTo("SUCCEEDED");
        assertThat(images.findByChapterIdAndImageNumber(chapter,2).orElseThrow().getId()).isEqualTo(saved.getId());
        assertThat(images.findByChapterIdOrderByImageNumberAsc(chapter)).hasSize(4);verify(model,times(4)).generateScheduled(any(),any());
    }
    @Test void recoveryActionsAreIdempotentAndInvalidAdminInputDoesNotConsumeVersion() throws Exception {
        Manifest m=submit(1);oneFailure("AFTER_PERMIT");assertThatThrownBy(()->consume(m)).isInstanceOf(Exception.class);redelivery(m);Panel p=m.panels().getFirst();
        var action=new TreeMap<String,Object>(Map.of("action_id",UUID.randomUUID().toString(),"action","REQUEST_REVIEW","expected_recovery_version",0));
        recovery.action(user,m.rootId(),p.operationId(),action,false);recovery.action(user,m.rootId(),p.operationId(),action,false);
        assertThat(evidence.find(m,p).getRecoveryVersion()).isEqualTo(1);
        var invalid=new TreeMap<String,Object>(Map.of("action_id",UUID.randomUUID().toString(),"action","ADMIN_DECISION","expected_recovery_version",1));
        assertThatThrownBy(()->recovery.action(user,m.rootId(),p.operationId(),invalid,true)).isInstanceOf(ImageRequestException.class);
        assertThat(evidence.find(m,p).getRecoveryVersion()).isEqualTo(1);
        action.put("action_id",UUID.randomUUID().toString());assertThatThrownBy(()->recovery.action(user,m.rootId(),p.operationId(),action,false)).isInstanceOf(ImageRequestException.class).hasMessageContaining("版本");
        verifyNoInteractions(model);
    }
    @Test void recoveryCandidateRecheckDoesNotCallModelAgainAndRevokedTokenCannotCommit() throws Exception {
        Manifest m=submit(1);oneFailure("AFTER_UPLOAD");assertThatThrownBy(()->consume(m)).isInstanceOf(Exception.class);Panel p=m.panels().getFirst();
        var detail=recovery.reviewDetail(user,m.rootId(),p.operationId());assertThat(detail.get("candidate_registered")).isEqualTo(true);assertThat(detail.toString()).doesNotContain("test-only-never-sent");
        var record=evidence.find(m,p);
        var invalidFinal=Map.<String,Object>of("action_id",UUID.randomUUID().toString(),"action","ADMIN_DECISION","expected_recovery_version",0,"provider_attempt",record.getProviderAttempt(),"execution_token",record.getExecutionToken(),"evidence_source","test fixture","evidence_reference","candidate exists","conclusion","ENDED_WITHOUT_RESULT","confirm_external_ended",true);
        assertThatThrownBy(()->recovery.action(user,m.rootId(),p.operationId(),invalidFinal,true)).isInstanceOf(ImageRequestException.class).hasMessageContaining("候选");assertThat(evidence.find(m,p).getRecoveryVersion()).isZero();
        var action=Map.<String,Object>of("action_id",UUID.randomUUID().toString(),"action","RECHECK","expected_recovery_version",0);
        recovery.action(user,m.rootId(),p.operationId(),action,false);consume(ImageMessagingConfig.PREFIX+"recovery.q");
        assertThat(queries.panel(m,p).get("status")).isEqualTo("SUCCEEDED");
        assertThatThrownBy(()->evidence.commit(m,p,0,()->{})).isInstanceOf(ImageRequestException.class).hasMessageContaining("代次");
        redelivery(m);verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void deliveryDeadlineClosesChannelAndNeverReissuesUnknownCall() throws Exception {
        Duration previous=props.getDeliveryProcessingTimeout();props.setDeliveryProcessingTimeout(Duration.ofSeconds(2));
        when(model.generateScheduled(any(),any())).thenReturn(Mono.delay(Duration.ofSeconds(10)).map(ignored->new GeneratedImage(PNG,"image/png")));
        Manifest m=submit(1);
        try {
            parallelContainer.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->"NEEDS_REVIEW".equals(queries.snapshot(m).get("status")));
            verify(model,times(1)).generateScheduled(any(),any());
        }finally { parallelContainer.stop();props.setDeliveryProcessingTimeout(previous); }
    }
    @Test void beforePermitInterruptionSafelyResumesAndKeepsOriginalDeadline() throws Exception {
        Manifest m=submit(1);Panel p=m.panels().getFirst();oneFailure("BEFORE_PERMIT");
        assertThatThrownBy(()->consume(m)).isInstanceOf(Exception.class);assertThat(evidence.find(m,p)).isNull();verifyNoInteractions(model);
        var original=objects.start(m,p,Duration.ofHours(1));redelivery(m);
        assertThat(evidence.find(m,p).getOperationDeadline()).isEqualTo(original.deadline().truncatedTo(java.time.temporal.ChronoUnit.MICROS));verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void notificationFailureAfterAckCannotUndoSuccessOrTriggerAnotherCall() throws Exception {
        Manifest m=submit(1);oneFailure("AFTER_ACK");consume(m);
        assertThat(consume(m)).isFalse();var result=queries.snapshot(m);assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        assertThat(queries.panel(m,m.panels().getFirst()).get("image_url")).isNotNull();
        publisher.execute(m,m.panels().getFirst(),0);consume(m);verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void reliableDeadLetterWaitRetainsResponsibilityUntilTargetIsBound() throws Exception {
        String suffix=UUID.randomUUID().toString();String source="image-test-dlx-source-"+suffix,target="image-test-dlx-target-"+suffix,exchange="image-test-dlx-"+suffix;
        var admin=new RabbitAdmin(connections);admin.declareExchange(new DirectExchange(exchange,true,false));
        // Test-only shorter internal redelivery timer; production Rabbit default is about 3 minutes.
        assertThat(rabbit.execInContainer("rabbitmqctl","eval","application:set_env(rabbit, dead_letter_worker_publisher_confirm_timeout, 1000).").getExitCode()).isZero();
        admin.declareQueue(QueueBuilder.durable(source).quorum().overflow(QueueBuilder.Overflow.rejectPublish).ttl(500).deadLetterExchange(exchange).deadLetterRoutingKey("resume").withArgument("x-dead-letter-strategy","at-least-once").build());
        admin.declareBinding(new Binding(source,Binding.DestinationType.QUEUE,ImageMessagingConfig.PREFIX+"work.x","dlx-test",null));
        try {
            Manifest m=submit(1);var command=publisher.command(m,m.panels().getFirst(),0,"IMAGE_EXECUTE");publisher.publish(command,"work.x","dlx-test");
            // Expired entries wait in the internal dead-letter consumer and are not ready-count entries.
            Thread.sleep(2000);assertThat(admin.getQueueProperties(target)).isNull();
            admin.declareQueue(QueueBuilder.durable(target).quorum().build());admin.declareBinding(new Binding(target,Binding.DestinationType.QUEUE,exchange,"resume",null));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).until(()->Integer.valueOf(1).equals(admin.getQueueProperties(target).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)));
            verifyNoInteractions(model);
        }finally { admin.deleteQueue(source);admin.deleteQueue(target);admin.deleteExchange(exchange);rabbit.execInContainer("rabbitmqctl","eval","application:set_env(rabbit, dead_letter_worker_publisher_confirm_timeout, 180000)."); }
    }
    @Test void regenerationUsesSerialLaneAndReturnsIncrementedStringVersion() throws Exception {
        Manifest first=submit(1);consume(first);MangaImage baseline=images.findByChapterIdAndImageNumber(chapter,1).orElseThrow();
        var input=body();input.put("expected_image_id",baseline.getId());input.put("expected_image_version",baseline.getVersion().toString());
        Manifest regeneration=submissions.submit(user,chapter,1,"MANGA_REGENERATE",input,null).manifest();assertThat(regeneration.serial()).isTrue();consume(regeneration);
        MangaImage updated=images.findById(baseline.getId()).orElseThrow();assertThat(updated.getVersion()).isEqualTo(baseline.getVersion()+1);
        assertThat(mapper.valueToTree(updated).get("image_version").asText()).isEqualTo("1");
        assertThat(queries.panel(regeneration,regeneration.panels().getFirst()).get("image_version")).isEqualTo("1");verify(model,times(2)).generateScheduled(any(),any());
    }
    @Test void scheduledSharedWriteFencesUploadedImmediateCandidate() throws Exception {
        Manifest first=submit(1);consume(first);MangaImage baseline=images.findByChapterIdAndImageNumber(chapter,1).orElseThrow();
        var input=body();input.put("expected_image_id",baseline.getId());input.put("expected_image_version",baseline.getVersion().toString());
        Manifest regeneration=submissions.submit(user,chapter,1,"MANGA_REGENERATE",input,null).manifest();oneFailure("AFTER_UPLOAD");assertThatThrownBy(()->consume(regeneration)).isInstanceOf(Exception.class);
        Long storyId=tx.execute(status->chapters.findById(chapter).orElseThrow().getStory().getId());
        MangaImage scheduled=sharedImages.saveGeneratedPanel(chapter,storyId,1,new GeneratedImage(PNG,"image/png"),"scheduled fixture",baseline.getId(),baseline.getVersion());
        redelivery(regeneration);assertThat(queries.panel(regeneration,regeneration.panels().getFirst()).get("status")).isEqualTo("STALE");
        assertThat(images.findById(baseline.getId()).orElseThrow().getImagePath()).isEqualTo(scheduled.getImagePath());verify(model,times(2)).generateScheduled(any(),any());
    }
    @Test void corruptCandidateBecomesActionableReviewWithoutRegeneration() throws Exception {
        Manifest m=submit(1);oneFailure("AFTER_UPLOAD");assertThatThrownBy(()->consume(m)).isInstanceOf(Exception.class);Panel p=m.panels().getFirst();var record=evidence.find(m,p);
        objectStorage.putObject(objectStorage.defaultBucket(),record.getCandidateObjectKey(),PNG,"image/png",java.util.Map.of());
        redelivery(m);var result=queries.panel(m,p);assertThat(result.get("status")).isEqualTo("NEEDS_REVIEW");assertThat((List<?>)result.get("recovery_actions")).isNotEmpty();verify(model,times(1)).generateScheduled(any(),any());
    }
    @Test void adminCanResolveEvidenceAfterChapterDeletion() throws Exception {
        Manifest m=submit(1);Panel p=m.panels().getFirst();evidence.permit(m,p,objects.start(m,p,Duration.ofHours(1)),()->{});
        chapters.deleteById(chapter);var record=evidence.find(m,p);
        var decision=Map.<String,Object>of("action_id",UUID.randomUUID().toString(),"action","ADMIN_DECISION","expected_recovery_version",0,"provider_attempt",record.getProviderAttempt(),"execution_token",record.getExecutionToken(),"evidence_source","controlled fixture","evidence_reference","no provider send","conclusion","NOT_ACCEPTED","confirm_external_ended",true);
        recovery.action(user,m.rootId(),p.operationId(),decision,true);assertThat(evidence.find(m,p).getAttemptDecision()).isEqualTo("FINAL");verifyNoInteractions(model);
    }
    @Test void standaloneCompatibilityUrlReturns202EnvelopeAndFullGetResultForLoggedInOwner() throws Exception {
        var current=mock(com.artverse.application.CurrentUserService.class);when(current.requireCurrentUser()).thenReturn(users.findById(user).orElseThrow());
        var requests=new com.artverse.api.ImageGenerationRequestController(current,submissions,queries,events,recovery,records);
        var legacy=new com.artverse.api.ImageGenController(null,null,current,requests);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(requests,legacy).setControllerAdvice(new com.artverse.common.GlobalExceptionHandler(),new com.artverse.common.ApiResponseAdvice()).build();
        UUID root=UUID.randomUUID();var input=body();input.put("user_id",user+999);input.put("request_id",root.toString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/image-gen/generate").contentType("application/json").content(mapper.writeValueAsBytes(input)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.submission_status").value("ACCEPTED"));
        Manifest m=queries.manifest(user,root);assertThat(m.userId()).isEqualTo(user);assertThat(m.chapterId()).isNull();consume(m);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/image-generation/requests/"+root))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.images[0].image_url").isNotEmpty());
        assertThat(submissions.submit(user,null,null,"STANDALONE",input,root.toString()).manifest().rootId()).isEqualTo(root);verify(model,times(1)).generateScheduled(any(),any());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/image-gen/generate").contentType("application/json").content("{\"request_id\":\""+UUID.randomUUID()+"\",\"model\":\"test\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(40062))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.error_code").value("PROVIDER_REQUIRED"));
    }
}
