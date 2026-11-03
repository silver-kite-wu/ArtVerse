package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class AutomationWorker {
    private final AutomationStore store;
    private final AutomationService service;
    private final AutomationInputService inputs;
    private final TransactionTemplate tx;
    private final Map<String,AutomationExecutor> executors=new HashMap<>();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService renewer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("automation-renewer").factory());
    private final String owner="automation-"+UUID.randomUUID();
    private final AtomicInteger active=new AtomicInteger();
    @Value("${artverse.automations.enabled:true}") private boolean enabled;

    public AutomationWorker(AutomationStore store,AutomationService service,AutomationInputService inputs,
                            PlatformTransactionManager manager,List<AutomationExecutor> adapters) {
        this.store=store;this.service=service;this.inputs=inputs;this.tx=new TransactionTemplate(manager);
        for(var adapter:adapters)for(String key:adapter.keys())if(executors.put(key,adapter)!=null)throw new IllegalStateException("Duplicate automation executor "+key);
    }
    @Scheduled(initialDelay=10000,fixedDelayString="${artverse.automations.poll-interval-ms:5000}")
    public void tick() {
        if(!enabled)return;
        try {
            service.materializeDue();
            int capacity=4-active.get();
            if(capacity<=0)return;
            List<AutomationStore.Run> claimed=tx.execute(s->store.claim(owner,Math.min(4,capacity),90));
            if(claimed==null)return;
            for(var run:claimed) {
                active.incrementAndGet();
                try { workers.submit(()->{try{process(run);}finally{active.decrementAndGet();}}); }
                catch(RejectedExecutionException e) { active.decrementAndGet();tx.executeWithoutResult(s->store.transition(run,"RETRY_WAIT","CAPACITY","等待执行资源",null,5)); }
            }
        } catch(Exception e) { log.error("Automation dispatch failed: {}",e.getClass().getSimpleName()); }
    }
    void process(AutomationStore.Run claimed) {
        AutomationExecutor adapter=executors.get(claimed.definition().executorKey());
        ScheduledFuture<?> renewal=renewer.scheduleAtFixedRate(()-> {
            try { store.renew(claimed,90); }catch(Exception e){log.warn("Automation lease renewal failed for {}",claimed.id());}
        },30,30,TimeUnit.SECONDS);
        ScheduledFuture<?> control=renewer.scheduleAtFixedRate(()-> {
            try {
                var latest=store.run(claimed.id());
                if(!store.owned(claimed))return;
                boolean expired=latest.startedAt()!=null&&Instant.now().isAfter(latest.startedAt().plusSeconds(latest.definition().limits().maxDurationSeconds()));
                if(expired&&"RUNNING".equals(latest.state()))store.jdbc().update("UPDATE automation_run SET state='CANCEL_REQUESTED',cancel_requested_at=now(),error_code='DURATION_LIMIT' WHERE id=? AND fencing_token=? AND state='RUNNING'",claimed.id(),claimed.fencingToken());
                if((expired||"CANCEL_REQUESTED".equals(latest.state()))&&"AGENT".equals(latest.definition().taskType())&&adapter!=null)adapter.cancel(latest);
            } catch(Exception e) { log.debug("Automation stop signal pending for {}",claimed.id()); }
        },2,2,TimeUnit.SECONDS);
        try {
            var run=store.run(claimed.id());
            if(adapter==null)throw new IllegalStateException("Missing automation executor");
            AutomationExecutor.Observation result;
            boolean timeout=run.startedAt()!=null&&Duration.between(run.startedAt(),Instant.now()).getSeconds()>run.definition().limits().maxDurationSeconds();
            if("CANCEL_REQUESTED".equals(run.state())||timeout)result=adapter.cancel(run);
            else {
                if(run.inputSnapshot()==null) { store.freeze(run,inputs.prepare(run));run=store.run(run.id()); }
                if(!store.owned(run))return;
                result=adapter.execute(run);
            }
            if(run.response()!=null&&"close".equals(run.response().path("action").asText())&&"WAITING_USER".equals(result.state()))
                result=new AutomationExecutor.Observation("CANCELLED",result.code(),"用户已核对并关闭本次待处理事项；历史结果保留",null,0);
            finish(claimed,result);
        } catch(BusinessException e) {
            String message=e.getMessage();
            AutomationExecutor.Observation result;
            if("INPUT_CHANGED".equals(message))result=AutomationExecutor.Observation.waiting("INPUT_CHANGED","输入已发生变化，请检查后重新建立本次输入快照");
            else if(e.getStatus()==429||e.getStatus()==503||e.getStatus()==409)result=new AutomationExecutor.Observation("RETRY_WAIT","CAPACITY","等待执行资源或冲突任务结束",null,30);
            else if(e.getStatus()==400&& !"IMAGE_LIMIT_EXCEEDED".equals(message))result=AutomationExecutor.Observation.waiting("CONFIGURATION","执行配置不可用，请检查任务与模型设置："+AutomationStore.clip(message,200));
            else result=AutomationExecutor.Observation.failed("VALIDATION_FAILED",AutomationStore.clip(message,500));
            finish(claimed,result);
        } catch(Exception e) {
            log.error("Automation run {} failed ({})",claimed.id(),e.getClass().getSimpleName());
            finish(claimed,AutomationExecutor.Observation.failed("EXECUTOR_ERROR","任务执行失败，请查看执行记录后处理"));
        } finally { renewal.cancel(false);control.cancel(false); }
    }
    private void finish(AutomationStore.Run run,AutomationExecutor.Observation result) {
        tx.executeWithoutResult(s->{
            store.plan(run.automationId(),run.userId(),true);
            var latest=store.run(run.id());
            String state=result.state();
            if("CANCEL_REQUESTED".equals(latest.state())&&Set.of("RUNNING","RETRY_WAIT").contains(state))state="CANCEL_REQUESTED";
            if(store.transition(run,state,result.code(),AutomationStore.clip(result.summary(),20000),result.waitingReason(),result.retrySeconds())) {
                if(!Objects.equals(latest.state(),state)||!Objects.equals(latest.summary(),result.summary()))
                    store.event(run.id(),state,Map.of("summary",Objects.toString(result.summary(),""),"code",Objects.toString(result.code(),"")));
                if((!Objects.equals(latest.state(),state)||!Objects.equals(latest.summary(),result.summary()))&&((AutomationService.terminal(state)&&!"SKIPPED".equals(state))||"WAITING_USER".equals(state)))store.notify(run,state,result.summary());
                service.completeOnce(store.run(run.id()));
            }
        });
    }
    @PreDestroy void close() { workers.shutdown();renewer.shutdownNow(); }
}
