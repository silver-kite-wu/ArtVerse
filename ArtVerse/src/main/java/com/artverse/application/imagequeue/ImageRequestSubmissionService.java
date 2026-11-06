package com.artverse.application.imagequeue;

import com.artverse.application.UserProviderConfig;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.ImageGenRecord;
import com.artverse.persistence.ImageGenRecordRepository;
import com.artverse.prompt.MangaPromptPolicy;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;
import static com.artverse.application.imagequeue.ImageRequestInputs.*;

@Service @RequiredArgsConstructor
public class ImageRequestSubmissionService {
    private final ImageRequestInputs inputs;
    private final ImageGenerationObjects objects;
    private final ImageRedisState state;
    private final ImageCommandPublisher publisher;
    private final ImageQueueProperties props;
    private final ArtVerseProperties app;
    private final ImageGenRecordRepository records;
    private final ScheduledExecutorService renewal=Executors.newScheduledThreadPool(1,Thread.ofPlatform().daemon(true).name("image-submit-",0).factory());
    public record Target(long userId,UUID rootId,UUID operationId,String inputHash,String operation,String lane,
                         Long expectedImageId,Long expectedImageVersion,UUID activeVersion,String createdAt,String lastHeartbeatAt) {}
    public record Accepted(Manifest manifest,boolean joined) {}

    public Accepted submit(long user,Long chapter,Integer number,String operation,Map<String,Object> original,String headerKey) {
        if(!props.isProducerEnabled())throw new ImageRequestException("QUEUE_UNAVAILABLE","即时生图受理已暂停，请稍后使用原请求重试");
        Map<String,Object> body=new TreeMap<>(original);
        if(body.containsKey("assetGroupId"))body.putIfAbsent("asset_group_id",body.remove("assetGroupId"));
        body.remove("user_id");body.remove("userId");
        String requested=text(body,"request_id");
        if(headerKey!=null&&!headerKey.isBlank()) {
            uuid(headerKey);if(!requested.isBlank()&&!requested.equalsIgnoreCase(headerKey))throw new ImageRequestException("INVALID_INPUT","request_id 与 Idempotency-Key 不一致");requested=headerKey;
        }
        if(chapter==null||"MANGA_GENERATE".equals(operation)) {
            if(requested.isBlank())throw new ImageRequestException("INVALID_INPUT","请提供 Idempotency-Key");
        }
        Long expectedId=number(body.get("expected_image_id")),expectedVersion=number(body.get("expected_image_version"));
        boolean regenerate="MANGA_REGENERATE".equals(operation);
        if(regenerate&&(expectedId==null||expectedId<1||expectedVersion==null||expectedVersion<0))throw new ImageRequestException("IMAGE_VERSION_REQUIRED","重生成须携带图片 ID 和 image_version，请先刷新");
        if(!regenerate&&(expectedId!=null||expectedVersion!=null))throw new ImageRequestException("INVALID_INPUT","首次生成须使用 expected_absent");
        if(number!=null&&number<1)throw new ImageRequestException("INVALID_INPUT","图序无效");
        UUID root=requested.isBlank()?stable("artverse-image-v1",user,chapter,number,operation,regenerate?List.of(expectedId,expectedVersion):"expected_absent"):uuid(requested);
        body.remove("request_id");
        // Hash the caller's input before resolving mutable configuration or chapter state.
        String submissionHash=hash(Arrays.asList(user,chapter,number,operation,body));
        var existing=existing(user,root,submissionHash);
        if(existing!=null) { ensureAccepted(existing);return new Accepted(existing,!existing.rootId().equals(root)); }
        // Missing provider must fail before any lock/target reservation.
        UserProviderConfig provider=inputs.provider(user,body);
        String lock="image:submission-lock:"+user+":{"+root+"}",owner=UUID.randomUUID().toString();
        if(!state.lock(lock,owner,Duration.ofSeconds(60))) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<deadline) {
                var known=existing(user,root,submissionHash);
                if(known!=null&&accepted(known))return new Accepted(known,!known.rootId().equals(root));
                try { Thread.sleep(100); }catch(InterruptedException e) { Thread.currentThread().interrupt();break; }
            }
            throw retry("SUBMISSION_IN_PROGRESS","同请求正在提交，请使用原身份重试",root);
        }
        var renew=renewal.scheduleAtFixedRate(()-> { try { state.renew(lock,owner,Duration.ofSeconds(60)); }catch(RuntimeException ignored) {} },20,20,TimeUnit.SECONDS);
        try {
            existing=existing(user,root,submissionHash);
            if(existing!=null) { if(!accepted(existing))publish(existing);return new Accepted(existing,!existing.rootId().equals(root)); }
            Manifest proposed=build(user,root,chapter,number,operation,body,submissionHash,provider,expectedId,expectedVersion);
            if(!proposed.chapter()&&chapter!=null) {
                var canonical=reserve(proposed,proposed.panels().getFirst());
                if(!canonical.rootId().equals(root)) {
                    objects.alias(user,root,canonical.rootId(),submissionHash);
                    var joined=awaitCanonical(user,canonical.rootId());ensureAccepted(joined);return new Accepted(joined,true);
                }
            }
            if(!state.owns(lock,owner))throw retry("SUBMISSION_IN_PROGRESS","提交租约已失效，请使用原身份重试",root);
            Manifest fixed=objects.freeze(proposed);
            state.metadata(state.root(user,root),Map.of("submission_hash",submissionHash,"submission_status","PUBLISHING","publish_attempt_id",UUID.randomUUID().toString()));
            publish(fixed);return new Accepted(fixed,false);
        } finally { renew.cancel(false);state.unlock(lock,owner); }
    }
    private Manifest existing(long user,UUID root,String submissionHash) {
        var alias=objects.alias(user,root);
        if(alias.isPresent()) {
            if(!alias.get().submissionHash().equals(submissionHash))throw new ImageRequestException("IDEMPOTENCY_CONFLICT","同请求身份的输入不同");
            return awaitCanonical(user,alias.get().canonicalRoot());
        }
        var found=objects.find(user,root);
        if(found.isEmpty())return null;
        if(!found.get().submissionHash().equals(submissionHash))throw new ImageRequestException("IDEMPOTENCY_CONFLICT","同请求身份的输入不同");return found.get();
    }
    private Manifest build(long user,UUID root,Long chapter,Integer number,String operation,Map<String,Object> body,String submissionHash,UserProviderConfig config,Long expectedId,Long expectedVersion) {
        if(body.containsKey("expected_provider_hash")&&!hash(config).equals(body.get("expected_provider_hash")))
            throw new ImageRequestException("CONFIG_CHANGED","已审阅的供应商配置已变化，请重新准备提案");
        boolean chapterMode="MANGA_GENERATE".equals(operation);
        String lane=chapterMode||"MANGA_REGENERATE".equals(operation)?"SERIAL_MODEL":"PARALLEL_MODEL";
        String prompt=text(body,"prompt"),size=text(body,"size"),color=null;
        if(size.isBlank())size=app.getImage().getSize();
        if(!size.matches("(?:[1-9][0-9]{1,3}x[1-9][0-9]{1,3}|auto)"))throw new ImageRequestException("INVALID_INPUT","图片尺寸无效");
        if(prompt.length()>100000)throw new ImageRequestException("INVALID_INPUT","提示词过长");
        Long assets=number(body.get("asset_group_id"));
        List<Panel> panels=new ArrayList<>();List<String> references;
        UUID conversation=null;
        if(chapter==null) {
            if(prompt.isBlank())throw new ImageRequestException("INVALID_INPUT","提示词不能为空");
            references=inputs.validateReferences(body.get("reference_images"));
            String cid=text(body,"conversation_id");conversation=inputs.conversation(user,cid.isBlank()?null:uuid(cid),prompt);
            panels.add(new Panel(stable(root,"image",0),null,prompt,null,null,null,null,List.of(),hash(List.of(prompt,size,hash(config),references,lane,operation))));
        } else {
            List<Integer> numbers=chapterMode?inputs.missingPanels(user,chapter):List.of(Objects.requireNonNull(number));
            if(chapterMode&&body.containsKey("image_numbers")) {
                if(!(body.get("image_numbers") instanceof List<?> selected)||selected.isEmpty()||selected.size()>60)
                    throw new ImageRequestException("INVALID_INPUT","图序集合无效");
                List<Integer> requestedNumbers=new ArrayList<>();
                for(Object value:selected) {
                    Long parsed=number(value);
                    if(parsed==null||parsed<1||parsed>60||!numbers.contains(parsed.intValue())||requestedNumbers.contains(parsed.intValue()))
                        throw new ImageRequestException("TARGET_CONFLICT","已审阅目标不再为空，请重新准备提案");
                    requestedNumbers.add(parsed.intValue());
                }
                numbers=List.copyOf(requestedNumbers);
            }
            references=List.of();
            for(int index=0;index<numbers.size();index++) {
                int imageNumber=numbers.get(index);String scene=chapterMode||prompt.isBlank()?null:prompt;
                var preview=inputs.preview(user,chapter,imageNumber,assets,scene);
                if(body.get("expected_context_hashes") instanceof Map<?,?> expected
                        &&!hash(preview).equals(expected.get(Integer.toString(imageNumber))))
                    throw new ImageRequestException("INPUT_STALE","已审阅的分镜或素材上下文已变化");
                if(imageNumber>((Number)preview.get("image_count")).intValue())throw new ImageRequestException("INVALID_INPUT","图序超出章节范围");
                if(chapterMode&&!MangaPromptPolicy.isStoryboardPage(preview.get("scene").toString()))throw new ImageRequestException("INVALID_INPUT","请先生成有效的多格分镜");
                @SuppressWarnings("unchecked") var keys=(List<String>)preview.get("reference_images");
                if(index==0)references=inputs.freezeReferences(keys);
                if(body.containsKey("expected_reference_hash")&&!hash(references).equals(body.get("expected_reference_hash")))
                    throw new ImageRequestException("INPUT_STALE","已审阅的参考图片已变化");
                color=preview.get("color_mode").toString();
                String generatedPrompt=preview.get("prompt").toString();
                panels.add(new Panel(stable(root,"image",index),imageNumber,generatedPrompt,scene,expectedId,expectedVersion,hash(preview),List.of(),hash(Arrays.asList(generatedPrompt,size,hash(config),references,lane,operation,expectedId,expectedVersion))));
            }
        }
        Manifest m=new Manifest(1,user,root,submissionHash,operation,chapterMode?"CHAPTER_SERIAL":"SINGLE_IMAGE",lane,chapter,assets,conversation,config,size,color,references,List.copyOf(panels),OffsetDateTime.now());
        return m; // Target baseline is checked under the short admission lock after existing evidence.
    }
    /** Target locks are short and are never held over snapshot writes or broker waits. */
    public Target reserve(Manifest m,Panel p) {
        return reserve(m,p,null);
    }
    public Target reserve(Manifest m,Panel p,String executionOwner) {
        String key=state.target(m,p),lock=state.targetLock(m,p),owner="admission:"+UUID.randomUUID();
        boolean alreadyOwned=executionOwner!=null&&state.owns(lock,executionOwner);
        String current=state.value(key);
        if(!alreadyOwned) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!state.lock(lock,owner,Duration.ofSeconds(60))) {
                current=state.value(key);
                if(current!=null)return join(m,p,ImageJson.read(current,Target.class));
                if(System.nanoTime()>=deadline)throw new ImageRequestException("STATUS_UNAVAILABLE","目标占用暂无法核实");
                try { Thread.sleep(25); }catch(InterruptedException e) { Thread.currentThread().interrupt();throw new ImageRequestException("STATUS_UNAVAILABLE","目标核实已中断"); }
            }
        }
        try {
            current=state.value(key);
            if(current!=null) {
                Target active=ImageJson.read(current,Target.class);
                ImageGenRecord evidence=records.findByUserIdAndRequestId(active.userId(),active.operationId()).orElse(null);
                if(evidence!=null&&"FINAL".equals(evidence.getAttemptDecision())&&state.value("image:op-lock:"+active.userId()+":{"+active.rootId()+"}:"+active.operationId())==null) {
                    if(active.inputHash().equals(p.inputHash())&&active.operation().equals(m.operation())&&active.lane().equals(m.executionLane()))return join(m,p,active);
                    // The target lock proves no execution currently owns this target; final evidence prohibits revival.
                    state.targetRelease(key,current);current=null;
                } else return join(m,p,active);
            }
            // Redis may have been lost while a durable external call remains unresolved.
            // Reconstruct ownership before admitting another canonical operation.
            var unresolved=records.findFirstByChapterIdAndImageNumberAndAttemptDecisionOrderByIdAsc(m.chapterId(),p.imageNumber(),"UNRESOLVED");
            if(unresolved.isPresent()) {
                var r=unresolved.get();
                Target active=new Target(m.userId(),r.getRootRequestId(),r.getRequestId(),r.getRequestHash(),r.getOperation(),r.getExecutionLane(),r.getTargetImageId(),r.getTargetBaseVersion(),UUID.randomUUID(),r.getCreatedAt().toString(),ImageRedisState.now());
                state.targetClaim(key,ImageJson.write(active));return join(m,p,active);
            }
            var previous=records.findFirstByUserIdAndChapterIdAndImageNumberAndRequestHashAndStatusOrderByIdAsc(m.userId(),m.chapterId(),p.imageNumber(),p.inputHash(),com.artverse.domain.ImageGenStatus.SUCCEEDED);
            if(previous.isPresent()) {
                var r=previous.get();
                return new Target(m.userId(),r.getRootRequestId(),r.getRequestId(),r.getRequestHash(),r.getOperation(),r.getExecutionLane(),r.getTargetImageId(),r.getTargetBaseVersion(),UUID.randomUUID(),r.getCreatedAt().toString(),ImageRedisState.now());
            }
            inputs.baseline(m,p);
            Target target=new Target(m.userId(),m.rootId(),p.operationId(),p.inputHash(),m.operation(),m.executionLane(),p.expectedImageId(),p.expectedImageVersion(),UUID.randomUUID(),ImageRedisState.now(),ImageRedisState.now());
            String encoded=ImageJson.write(target);
            if(!state.targetClaim(key,encoded))return join(m,p,ImageJson.read(state.value(key),Target.class));return target;
        } finally { if(!alreadyOwned)state.unlock(lock,owner); }
    }
    private Target join(Manifest m,Panel p,Target active) {
        if(active.userId()==m.userId()&&active.inputHash().equals(p.inputHash())&&active.operation().equals(m.operation())&&active.lane().equals(m.executionLane()))return active;
        throw new ImageRequestException("IMAGE_GENERATION_IN_PROGRESS","该图片已有其他生图意图，需等待或核实",Map.of("request_id",active.rootId().toString(),"operation_id",active.operationId().toString()));
    }
    public void release(Manifest m,Panel p) {
        if(m.chapterId()==null)return;
        String value=state.value(state.target(m,p));
        if(value!=null) { var t=ImageJson.read(value,Target.class);if(t.rootId().equals(m.rootId())&&t.operationId().equals(p.operationId()))state.targetRelease(state.target(m,p),value); }
    }
    public boolean accepted(Manifest m) { return "ACCEPTED".equals(state.read(state.root(m.userId(),m.rootId())).get("submission_status"))||!records.findByUserIdAndRootRequestIdOrderByImageNumberAsc(m.userId(),m.rootId()).isEmpty(); }
    private Manifest awaitCanonical(long user,UUID root) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            var found=objects.find(user,root);if(found.isPresent())return found.get();
            try { Thread.sleep(100); }catch(InterruptedException e) { Thread.currentThread().interrupt();break; }
        }
        throw retry("SUBMISSION_IN_PROGRESS","原图片请求正在提交，请使用 canonical 身份重试",root);
    }
    public void ensureAccepted(Manifest m) {
        if(accepted(m))return;
        String lock="image:submission-lock:"+m.userId()+":{"+m.rootId()+"}",owner=UUID.randomUUID().toString();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            if(accepted(m))return;
            if(state.lock(lock,owner,Duration.ofSeconds(60))) {
                try { if(!accepted(m))publish(m);return; }finally { state.unlock(lock,owner); }
            }
            try { Thread.sleep(100); }catch(InterruptedException e) { Thread.currentThread().interrupt();break; }
        }
        throw retry("SUBMISSION_IN_PROGRESS","原请求仍在确认受理，请使用原身份重试",m.rootId());
    }
    public void publish(Manifest m) {
        try {
            if(m.panels().isEmpty()) { state.metadata(state.root(m.userId(),m.rootId()),Map.of("status","SUCCEEDED","outcome","NO_WORK"));return; }
            if(m.chapter())publisher.advance(m);else publisher.execute(m,m.panels().getFirst(),0);
            try { state.accepted(m);if(!m.chapter())state.queued(m,m.panels().getFirst()); }catch(RuntimeException ignored) { /* confirmed delivery reconstructs state */ }
        } catch(ImageRequestException e) { throw retry(e.getData().get("error_code").toString(),e.getMessage(),m.rootId()); }
    }
    private ImageRequestException retry(String code,String message,UUID root) { return new ImageRequestException(code,message,Map.of("request_id",root.toString(),"retry_with_same_key",true)); }
    @PreDestroy void stop() { renewal.shutdown(); }
}
