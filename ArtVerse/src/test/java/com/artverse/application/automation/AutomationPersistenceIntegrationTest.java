package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the local PostgreSQL schema, never provider APIs. Every fixture is removed in teardown. */
@EnabledIfEnvironmentVariable(named="ARTVERSE_AUTOMATION_DB_TEST", matches="true")
class AutomationPersistenceIntegrationTest {
    JdbcTemplate jdbc;AutomationStore store;AutomationService service;TransactionTemplate tx;ObjectMapper mapper;long user;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:postgresql://localhost:5432/manga_novel?stringtype=unspecified","postgres","postgres");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        store=new AutomationStore(jdbc,mapper);
        var inputs=mock(AutomationInputService.class);
        when(inputs.validateAndNormalize(anyLong(),any())).thenAnswer(invocation->invocation.getArgument(1));
        service=new AutomationService(store,new AutomationScheduleCalculator(),inputs,mock(com.artverse.application.NovelDraftReviewService.class),mock(com.artverse.application.MangaAgentRunService.class));
        ReflectionTestUtils.setField(service,"maxActivePlans",10);
        String name="automation_it_"+UUID.randomUUID().toString().replace("-","");
        user=Objects.requireNonNull(jdbc.queryForObject("INSERT INTO users(username,email,password_hash,role,created_at) VALUES (?,?,?,'USER',now()) RETURNING id",Long.class,name,name+"@example.test","integration-fixture-only"));
    }
    @AfterEach void cleanup(){if(user!=0)jdbc.update("DELETE FROM users WHERE id=?",user);}
    AutomationDefinition definition(){return new AutomationDefinition("integration schedule","IMAGE","IMAGE_GEN","test only",new AutomationDefinition.Schedule("DAILY",null,"09:00","Asia/Shanghai",List.of()),mapper.createObjectNode().put("modelConfigId",1).put("model","test"),new AutomationDefinition.Limits(1,2,60),"RUN_LATEST","SKIP",null,null,"TASK_CENTER",null);}
    AutomationStore.Plan plan(){return tx.execute(s->service.create(user,UUID.randomUUID().toString(),definition()));}
    AutomationStore.Run immediate(AutomationStore.Plan plan){return tx.execute(s->service.runNow(user,plan.id(),UUID.randomUUID().toString()));}
    void due(AutomationStore.Plan plan){jdbc.update("UPDATE automation SET next_run_at=now()-interval '4 days' WHERE id=?",plan.id());}

    @Test void wireFormatAndCanonicalHashRemainStableUnderGlobalSnakeCase() throws Exception {
        String json=store.json(definition());assertThat(json).contains("taskType","localTime","maxImagesPerRun").doesNotContain("task_type");
        assertThat(mapper.readValue(json,AutomationDefinition.class)).isEqualTo(definition());
        assertThat(store.canonical(mapper.readTree("{\"b\":2,\"a\":{\"d\":4,\"c\":3}}"))).isEqualTo(store.canonical(mapper.readTree("{\"a\":{\"c\":3,\"d\":4},\"b\":2}")));
    }
    @Test void concurrentScannersMaterializeExactlyOnceAndCoalesceMissedCycles() throws Exception {
        var plan=plan();due(plan);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{ready.countDown();try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}tx.executeWithoutResult(s->service.materializeDue());}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertThat(store.runs(plan.id(),null,20)).hasSize(1);
        assertThat(store.runs(plan.id(),null,20).getFirst().scheduledFor()).isAfter(Instant.now().minusSeconds(86401));
        assertThat(store.plan(plan.id(),user,false).nextRunAt()).isAfter(Instant.now());
        assertThat(store.events(store.runs(plan.id(),null,20).getFirst().id(),0,100)).anyMatch(event->event.get("type").equals("MISFIRE_COALESCED"));
    }
    @Test void expiredOwnerCannotFinishAfterTakeoverAndQueuedWorkSurvivesDispatchGap() {
        var run=immediate(plan());
        var first=tx.execute(s->store.claim("owner-a",1,90)).getFirst();
        assertThat(first.id()).isEqualTo(run.id());assertThat(tx.<List<AutomationStore.Run>>execute(s->store.claim("owner-b",1,90))).isEmpty();
        jdbc.update("UPDATE automation_run SET lease_until=now()-interval '1 second' WHERE id=?",run.id());
        var second=tx.execute(s->store.claim("owner-b",1,90)).getFirst();
        assertThat(second.fencingToken()).isEqualTo(first.fencingToken()+1);
        assertThat(store.transition(first,"SUCCEEDED",null,"stale",null,0)).isFalse();
        assertThat(store.transition(second,"SUCCEEDED",null,"complete",null,0)).isTrue();
    }
    @Test void creationAndRunNowAreIdempotentAndConflictingInputIsRejected() {
        var first=tx.execute(s->service.create(user,"same",definition()));
        assertThat(tx.execute(s->service.create(user,"same",definition())).id()).isEqualTo(first.id());
        var other=definition();var changed=new AutomationDefinition("changed",other.taskType(),other.executorKey(),other.instruction(),other.schedule(),other.payload(),other.limits(),other.misfirePolicy(),other.overlapPolicy(),null,null,null,null);
        assertThatThrownBy(()->tx.execute(s->service.create(user,"same",changed))).isInstanceOf(BusinessException.class);
        var run=tx.execute(s->service.runNow(user,first.id(),"manual"));
        assertThat(tx.execute(s->service.runNow(user,first.id(),"manual")).id()).isEqualTo(run.id());
        assertThatThrownBy(()->tx.execute(s->service.runNow(user,first.id(),"other"))).isInstanceOf(BusinessException.class);
    }
    @Test void waitingRunSkipsNextCycleWithoutHoldingWorkerAndCanBeClosedIdempotently() {
        var plan=plan();var run=immediate(plan);
        jdbc.update("UPDATE automation_run SET state='WAITING_USER',waiting_reason='RESULT_UNCERTAIN' WHERE id=?",run.id());due(plan);
        tx.executeWithoutResult(s->service.materializeDue());
        assertThat(store.runs(plan.id(),null,20)).hasSize(2).anyMatch(item->item.state().equals("SKIPPED")&&item.errorCode().equals("OVERLAP"));
        assertThat(tx.<List<AutomationStore.Run>>execute(s->store.claim("worker",4,90))).isEmpty();
        assertThatThrownBy(()->tx.execute(s->service.respond(user,run.id(),"unsafe",mapper.createObjectNode().put("action","retry")))).isInstanceOf(BusinessException.class);
        var close=mapper.createObjectNode().put("action","close");
        assertThat(tx.execute(s->service.respond(user,run.id(),"close",close)).state()).isEqualTo("CANCEL_REQUESTED");
        assertThat(tx.execute(s->service.respond(user,run.id(),"close",close)).id()).isEqualTo(run.id());
        var events=store.events(run.id(),0,100);long cursor=((Number)events.getFirst().get("id")).longValue();
        assertThat(store.events(run.id(),cursor,100)).allMatch(event->((Number)event.get("id")).longValue()>cursor);
    }
    @Test void versionOwnershipAndPausePreventUnexpectedDispatch() {
        var plan=plan();var paused=tx.execute(s->service.lifecycle(user,plan.id(),"pause",plan.version()));
        assertThat(paused.nextRunAt()).isNull();tx.executeWithoutResult(s->service.materializeDue());assertThat(store.runs(plan.id(),null,10)).isEmpty();
        assertThatThrownBy(()->tx.execute(s->service.lifecycle(user,plan.id(),"resume",plan.version()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.get(user+100000,plan.id())).isInstanceOf(BusinessException.class);
        var resumed=tx.execute(s->service.lifecycle(user,plan.id(),"resume",paused.version()));assertThat(resumed.nextRunAt()).isAfter(Instant.now());
        jdbc.update("UPDATE automation SET status='COMPLETED' WHERE id=?",plan.id());
        assertThat(tx.execute(s->service.lifecycle(user,plan.id(),"archive",resumed.version())).status()).isEqualTo("ARCHIVED");
    }

    AutomationWorker worker(AutomationExecutor adapter) {
        var inputs=mock(AutomationInputService.class);
        when(inputs.prepare(any())).thenReturn(mapper.createObjectNode().put("fixture",true));
        return new AutomationWorker(store,service,inputs,tx.getTransactionManager(),List.of(adapter));
    }
    AutomationExecutor adapter() {
        var adapter=mock(AutomationExecutor.class);
        when(adapter.keys()).thenReturn(Set.of("IMAGE_GEN"));
        return adapter;
    }
    AutomationStore.Run claim() { return tx.execute(s->store.claim("fixture-worker",1,90)).getFirst(); }

    @Test void workerPersistsOneShotCompletionAndReadNotificationWithoutProviderCalls() {
        var daily=definition();
        var once=new AutomationDefinition(daily.name(),daily.taskType(),daily.executorKey(),daily.instruction(),
                new AutomationDefinition.Schedule("ONCE",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(1).toString(),null,"Asia/Shanghai",List.of()),
                daily.payload(),daily.limits(),daily.misfirePolicy(),daily.overlapPolicy(),null,null,null,null);
        var plan=tx.execute(s->service.create(user,UUID.randomUUID().toString(),once));
        due(plan);tx.executeWithoutResult(s->service.materializeDue());
        var run=claim();var adapter=adapter();when(adapter.execute(any())).thenReturn(AutomationExecutor.Observation.done("fixture complete"));
        var worker=worker(adapter);
        try { worker.process(run); } finally { worker.close(); }
        assertThat(store.run(run.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(store.plan(plan.id(),user,false).status()).isEqualTo("COMPLETED");
        assertThat(service.notifications(user)).hasSize(1);
        long notification=((Number)service.notifications(user).getFirst().get("id")).longValue();
        service.markRead(user,notification);
        assertThat(service.notifications(user).getFirst().get("read")).isEqualTo(true);
        assertThatThrownBy(()->service.markRead(user+100000,notification)).isInstanceOf(BusinessException.class);
    }
    @Test void waitingReleasesLeaseAndExplicitCloseFinishesWithoutReexecuting() {
        var run=immediate(plan());var adapter=adapter();
        when(adapter.execute(any())).thenReturn(AutomationExecutor.Observation.waiting("RESULT_UNCERTAIN","核对结果"));
        when(adapter.cancel(any())).thenReturn(AutomationExecutor.Observation.waiting("RESULT_UNCERTAIN","核对结果"));
        var worker=worker(adapter);
        try {
            worker.process(claim());
            assertThat(store.run(run.id()).state()).isEqualTo("WAITING_USER");
            assertThat(store.run(run.id()).leaseOwner()).isNull();
            assertThat(tx.<List<AutomationStore.Run>>execute(s->store.claim("other-worker",4,90))).isEmpty();
            tx.execute(s->service.respond(user,run.id(),"close",mapper.createObjectNode().put("action","close")));
            worker.process(claim());
            assertThat(store.run(run.id()).state()).isEqualTo("CANCELLED");
            verify(adapter,times(1)).execute(any());verify(adapter,times(1)).cancel(any());
        } finally { worker.close(); }
    }
    @Test void cancelledQueuedRunNeverCallsExecutor() {
        var run=immediate(plan());
        assertThat(tx.execute(s->service.cancel(user,run.id(),"cancel")).state()).isEqualTo("CANCELLED");
        assertThat(tx.<List<AutomationStore.Run>>execute(s->store.claim("worker",4,90))).isEmpty();
        assertThat(service.notifications(user)).hasSize(1);
    }
}
