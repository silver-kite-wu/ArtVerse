package com.artverse.application.automation;

import com.artverse.ai.*;
import com.artverse.application.*;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ImageGenRecordRepository;
import com.artverse.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Independent image jobs use the existing provider/storage stack, with a durable send marker. */
@Component
@RequiredArgsConstructor
public class ImageAutomationExecutor implements AutomationExecutor {
    private final AutomationStore store;
    private final AutomationInputService inputs;
    private final ImageGenRecordRepository records;
    private final AiConversationService conversations;
    private final Image2Client client;
    private final ObjectStorageService storage;
    private final MediaStorageService media;
    private final ArtVerseProperties properties;
    private final ScheduledMangaWorkerSlotService slots;
    private final PlatformTransactionManager transactionManager;
    public Set<String> keys() { return Set.of("IMAGE_GEN"); }

    public Observation execute(AutomationStore.Run run) {
        var config=inputs.config(run.userId(),run.definition().payload(),true);
        String target="automation:"+run.id()+":image:1";
        var tx=new TransactionTemplate(transactionManager);
        ImageGenRecord record=tx.execute(s->records.findByAutomationTargetKey(target).orElseGet(()-> {
            User user=inputs.user(run.userId());
            var payload=run.definition().payload();
            var conversation=payload.hasNonNull("conversationId")?conversations.require(user,UUID.fromString(payload.path("conversationId").asText())):
                    conversations.automationConversation(user,AiConversationType.IMAGE_GEN,null,run.id(),"定时生图："+run.definition().name());
            ImageGenRecord created=new ImageGenRecord();created.setUser(user);created.setConversation(conversation);
            created.setPrompt(run.definition().instruction());created.setSize(payload.path("size").asText("1024x1024"));created.setModel(config.model());
            created.setAutomationTargetKey(target);created.setAutomationDispatchState("PENDING");
            return records.saveAndFlush(created);
        }));
        if(record==null)throw new IllegalStateException("Image record was not persisted");
        store.attach(run,"image:1","IMAGE_GEN",null,null,record.getId(),null,null);
        String dispatch=record.getAutomationDispatchState();
        if("SUCCEEDED".equals(dispatch))return Observation.done("图片生成完成");
        if("IN_FLIGHT".equals(dispatch)||"UNCERTAIN".equals(dispatch))return Observation.waiting("RESULT_UNCERTAIN","外部图片请求可能已被接受，请核对结果后处理");
        if("FAILED".equals(dispatch)) {
            if(run.response()!=null&&"retry".equals(run.response().path("action").asText())&& !"RESULT_UNCERTAIN".equals(record.getAutomationErrorCode())) {
                store.jdbc().update("UPDATE image_gen_records SET status='RUNNING',automation_dispatch_state='PENDING',automation_attempts=0,automation_error_code=NULL,failure_reason=NULL,completed_at=NULL WHERE id=? AND automation_dispatch_state='FAILED'",record.getId());
                store.jdbc().update("UPDATE automation_run SET response=NULL WHERE id=? AND fencing_token=?",run.id(),run.fencingToken());
            } else return Observation.failed("IMAGE_FAILED","图片生成失败，可查看失败原因后重试");
        }
        String slotOwner=ScheduledMangaWorkerSlotService.newOwner(run.id());
        var slot=slots.acquire(slotOwner);
        if(slot.isEmpty())throw new BusinessException(429,"生图容量已满");
        String userPermit="user:"+run.userId(),providerPermit="provider-model:"+config.configId()+":"+AutomationStore.hash(config.model());
        boolean userHeld=false,providerHeld=false;
        List<Path> references=new ArrayList<>();
        boolean sent=false;
        boolean resultReturned=false;
        try {
            userHeld=slots.acquireScoped(userPermit,slotOwner);providerHeld=userHeld&&slots.acquireScoped(providerPermit,slotOwner);
            if(!userHeld||!providerHeld)throw new BusinessException(429,"当前用户或模型正在执行其他生图任务");
            for(var key:run.definition().payload().path("referenceImageKeys")) {
                try(var stream=storage.get(storage.defaultBucket(),key.asText())) {
                    byte[] data=stream.readNBytes(Math.toIntExact(properties.getUpload().getMaxImageBytes()+1));
                    media.validateImageBytes(data,properties.getUpload().getMaxImageBytes());
                    Path path=Files.createTempFile("automation-ref-",".png");references.add(path);media.savePng(data,path);
                }
            }
            int claimed=store.jdbc().update("""
                    UPDATE image_gen_records SET automation_dispatch_state='IN_FLIGHT',automation_attempts=automation_attempts+1
                    WHERE id=? AND automation_dispatch_state IN ('PENDING','RETRY_WAIT') AND EXISTS
                      (SELECT 1 FROM automation_run WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>now() AND state='RUNNING')
                    """,record.getId(),run.id(),run.leaseOwner(),run.fencingToken());
            if(claimed==0)return Observation.running("本次执行权已变更");
            sent=true;
            GeneratedImage generated=client.generateScheduled(new ImageGenerationRequest(record.getPrompt(),record.getModel(),record.getSize(),references.isEmpty()?null:references,null),config)
                    .block(Duration.ofSeconds(Math.min(720,run.definition().limits().maxDurationSeconds())));
            if(generated==null)throw new BusinessException(502,"供应商未返回图片");
            resultReturned=true;
            String objectKey="image_gen/"+run.userId()+"/"+media.generateUniqueFilename("gen",generated.extension());
            try(var content=generated.openStream()) {
                storage.put(objectKey,content,generated.sizeBytes(),generated.contentType());
            }
            // The durable image record may collect a late result even if the parent lease moved.
            store.jdbc().update("UPDATE image_gen_records SET status='SUCCEEDED',automation_dispatch_state='SUCCEEDED',image_path=?,failure_reason=NULL,automation_error_code=NULL,completed_at=now() WHERE id=? AND automation_dispatch_state IN ('IN_FLIGHT','UNCERTAIN')",objectKey,record.getId());
            return Observation.done("图片生成完成");
        } catch(Exception e) {
            if(!sent) {
                if(e instanceof BusinessException business)throw business;
                throw new BusinessException(400,"参考图片不可用，请重新上传");
            }
            int attempts=Objects.requireNonNull(store.jdbc().queryForObject("SELECT automation_attempts FROM image_gen_records WHERE id=?",Integer.class,record.getId()));
            var decision=ScheduledMangaRetryPolicy.classify(e,attempts,3);
            boolean uncertain=resultReturned||"UNCERTAIN".equals(decision.state().name())
                    ||e instanceof IllegalStateException||e instanceof java.io.IOException;
            String state=uncertain?"UNCERTAIN":"RETRY_WAIT".equals(decision.state().name())?"RETRY_WAIT":"FAILED";
            String code=uncertain?"RESULT_UNCERTAIN":decision.errorCode();
            String message=uncertain?"外部请求结果不确定，请核对供应商记录":e instanceof org.springframework.web.reactive.function.client.WebClientResponseException response?
                    "图片供应商返回 HTTP "+response.getStatusCode().value():"图片生成未成功";
            store.jdbc().update("UPDATE image_gen_records SET status=?,automation_dispatch_state=?,automation_error_code=?,failure_reason=?,completed_at=CASE WHEN ?='RETRY_WAIT' THEN NULL ELSE now() END WHERE id=? AND automation_dispatch_state='IN_FLIGHT'",
                    "RETRY_WAIT".equals(state)?"RUNNING":"FAILED",state,code,message,state,record.getId());
            if(uncertain)return Observation.waiting("RESULT_UNCERTAIN",message);
            if("RETRY_WAIT".equals(state))return new Observation("RETRY_WAIT",code,message,null,retryDelay(e,attempts));
            return Observation.failed(code,message);
        } finally {
            for(Path path:references)try{Files.deleteIfExists(path);}catch(Exception ignored){}
            if(providerHeld)slots.releaseScoped(providerPermit,slotOwner);if(userHeld)slots.releaseScoped(userPermit,slotOwner);slots.release(slot.get(),slotOwner);
        }
    }
    public Observation cancel(AutomationStore.Run run) {
        var record=records.findByAutomationTargetKey("automation:"+run.id()+":image:1").orElse(null);
        if(record==null)return new Observation("CANCELLED",null,"生图已取消",null,0);
        return switch(Objects.toString(record.getAutomationDispatchState(),"")) {
            case "SUCCEEDED" -> Observation.done("图片已生成，结果保留");
            case "IN_FLIGHT","UNCERTAIN" -> Observation.waiting("RESULT_UNCERTAIN","请求已派发，需核对外部结果后关闭");
            default -> { store.jdbc().update("UPDATE image_gen_records SET status='FAILED',automation_dispatch_state='FAILED',failure_reason='用户取消',completed_at=now() WHERE id=? AND automation_dispatch_state IN ('PENDING','RETRY_WAIT')",record.getId());yield new Observation("CANCELLED",null,"已停止尚未派发的生图请求",null,0); }
        };
    }
    private int retryDelay(Exception e,int attempts) {
        if(e instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
            String header=response.getHeaders().getFirst("Retry-After");
            if(header!=null)try{return Math.max(1,Math.min(86400,Integer.parseInt(header)));}catch(NumberFormatException ignored){
                try{return (int)Math.max(1,Math.min(86400,Duration.between(Instant.now(),ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()));}catch(Exception ignoredDate){}
            }
        }
        return Math.min(3600,(1<<Math.min(attempts,6))*30+ThreadLocalRandom.current().nextInt(1,20));
    }
}
