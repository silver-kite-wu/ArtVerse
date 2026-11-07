package com.artverse.application.imagequeue;

import com.artverse.application.AiConversationService;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.artverse.storage.ObjectStorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static com.artverse.application.imagequeue.ImageProtocol.*;

/** Short transactions only. Every paid call is preceded by a durable UNRESOLVED permit. */
@Service @RequiredArgsConstructor
public class ImageGenerationEvidence {
    private final ImageGenRecordRepository records;
    private final MangaImageRepository images;
    private final ChapterRepository chapters;
    private final UserRepository users;
    private final AiConversationService conversations;
    private final TransactionTemplate transactions;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final ImageQueueProperties props;
    private final ObjectStorageService storage;
    private final ImageRequestInputs inputs;

    public ImageGenRecord find(Manifest m,Panel p) { return records.findByUserIdAndRequestId(m.userId(),p.operationId()).orElse(null); }
    public ImageGenRecord permit(Manifest m,Panel p,ImageGenerationObjects.Start start,Runnable assertLease) {
        return transactions.execute(tx -> {
            locks(m,p);assertLease.run();
            ImageGenRecord existing=find(m,p);
            if(existing!=null)throw new ExistingEvidence(existing);
            if(OffsetDateTime.now().plus(props.getProviderTimeout()).plus(props.getPostprocessReserve()).isAfter(start.deadline()))
                throw new ImageRequestException("INPUT_STALE","操作总预算不足，未调用供应商");
            inputs.baseline(m,p);
            long global=count("",new Object[0]);
            if(global>=props.getGlobalModelConcurrency()||count(" AND user_id=?",new Object[]{m.userId()})>=props.getUserModelConcurrency()
                    ||count(" AND profile_model_key=?",new Object[]{m.profileKey()})>=props.getProfileModelConcurrency()
                    ||m.serial()&&count(" AND execution_lane='SERIAL_MODEL'",new Object[0])>0)
                throw new NoPermit("SERIAL_CALL_UNRESOLVED_OR_QUOTA");
            if(m.chapterId()!=null) {
                Long unresolved=jdbc.queryForObject("SELECT count(*) FROM image_gen_records WHERE chapter_id=? AND image_number=? AND request_id IS NOT NULL AND attempt_decision='UNRESOLVED'",Long.class,m.chapterId(),p.imageNumber());
                if(unresolved!=null&&unresolved>0)throw new NoPermit("TARGET_CALL_UNRESOLVED");
                Long consumed=jdbc.queryForObject("SELECT count(*) FROM image_gen_records WHERE chapter_id=? AND image_number=? AND request_id IS NOT NULL AND status='SUCCEEDED' AND target_image_id IS NOT DISTINCT FROM ? AND target_base_version IS NOT DISTINCT FROM ?",Long.class,m.chapterId(),p.imageNumber(),p.expectedImageId(),p.expectedImageVersion());
                if(consumed!=null&&consumed>0)throw new ImageRequestException("TARGET_CONFLICT","此图片基准已被成功操作使用");
            }
            ImageGenRecord r=create(m,p);r.setExecutionToken(1);r.setProviderAttempt(1);
            r.setProviderStartedAt(OffsetDateTime.now());r.setAttemptDecision("UNRESOLVED");
            r.setFirstStartedAt(start.firstStartedAt());r.setOperationDeadline(start.deadline());
            r.setCandidateObjectKey("generation-results/"+m.userId()+"/"+p.operationId()+"/1/");
            records.saveAndFlush(r);assertLease.run();return r;
        });
    }
    private long count(String extra,Object[] args) { Long count=jdbc.queryForObject("SELECT count(*) FROM image_gen_records WHERE request_id IS NOT NULL AND attempt_decision='UNRESOLVED'"+extra,Long.class,args);return count==null?0:count; }
    public void locks(Manifest m,Panel p) {
        jdbc.execute("SET LOCAL lock_timeout='1s'");jdbc.execute("SET LOCAL statement_timeout='3s'");
        advisory("image:permit:global");if(m.serial())advisory("image:permit:serial");
        advisory("image:permit:user:"+m.userId());advisory("image:permit:profile:"+m.profileKey());
        if(m.chapterId()!=null)advisory("image:target:"+m.chapterId()+":"+p.imageNumber());
        advisory("image:history:"+m.userId()+":"+p.operationId());
    }
    private void advisory(String name) { jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},name); }
    private ImageGenRecord create(Manifest m,Panel p) {
        ImageGenRecord r=new ImageGenRecord();r.setUser(users.getReferenceById(m.userId()));
        r.setRequestId(p.operationId());r.setRootRequestId(m.rootId());r.setRequestHash(p.inputHash());r.setOperation(m.operation());r.setExecutionLane(m.executionLane());
        r.setProfileModelKey(m.profileKey());r.setChapterId(m.chapterId());r.setImageNumber(p.imageNumber());r.setTargetImageId(p.expectedImageId());r.setTargetBaseVersion(p.expectedImageVersion());r.setTargetExpectedAbsent(p.expectedImageId()==null);
        r.setPrompt(p.prompt());r.setModel(m.provider().model());r.setSize(m.size());
        if(m.conversationId()!=null)r.setConversation(conversations.require(users.getReferenceById(m.userId()),m.conversationId()));
        return r;
    }
    public ImageGenRecord candidate(Manifest m,Panel p,long token,String objectKey,String sha256,String contentType,long size,Runnable lease) {
        return mutate(m,p,token,lease,r -> { r.setCandidateObjectKey(objectKey);r.setCandidateHash(sha256);r.setCandidateContentType(contentType);r.setCandidateSize(size); });
    }
    public ImageGenRecord commit(Manifest m,Panel p,long token,Runnable lease) {
        return mutate(m,p,token,lease,r -> {
            if(r.getStatus()==ImageGenStatus.SUCCEEDED)return;
            if(!"UNRESOLVED".equals(r.getAttemptDecision()))throw new ImageRequestException("RECOVERY_CONFLICT","提交权已结束");
            inputs.baseline(m,p);
            if(m.chapterId()!=null) {
                MangaImage image=images.findByChapterIdAndImageNumber(m.chapterId(),p.imageNumber()).orElseGet(MangaImage::new);
                if(image.getId()!=null)em.lock(image,LockModeType.PESSIMISTIC_WRITE);
                if(image.getId()==null) { image.setChapter(chapters.getReferenceById(m.chapterId()));image.setImageNumber(p.imageNumber()); }
                image.setImagePath(r.getCandidateObjectKey());image.setObjectKey(r.getCandidateObjectKey());image.setBucket(storage.defaultBucket());image.setStorageProvider(StorageProvider.OSS);
                image.setContentType(r.getCandidateContentType());image.setSizeBytes(r.getCandidateSize());image.setPrompt(p.prompt());
                images.saveAndFlush(image);r.setMangaImageId(image.getId());r.setResultImageVersion(image.getVersion());
                // Old objects are retained for the documented audited cleanup, never deleted before commit.
            }
            r.setImagePath(r.getCandidateObjectKey());r.setStatus(ImageGenStatus.SUCCEEDED);r.setOutcome("GENERATED");r.setAttemptDecision("FINAL");r.setResultCode(null);r.setFailureReason(null);r.setCompletedAt(OffsetDateTime.now());
        });
    }
    public ImageGenRecord finish(Manifest m,Panel p,String outcome,String code,boolean uncertain,Runnable lease) {
        return transactions.execute(tx -> {
            locks(m,p);lease.run();ImageGenRecord r=find(m,p);if(r==null)r=create(m,p);
            else em.lock(r,LockModeType.PESSIMISTIC_WRITE);
            if(r.getStatus()==ImageGenStatus.SUCCEEDED||"FINAL".equals(r.getAttemptDecision()))return r;
            if(!uncertain&&r.getProviderAttempt()>0&&"UNRESOLVED".equals(r.getAttemptDecision())&&!"STALE".equals(outcome))
                throw new ImageRequestException("EXTERNAL_CALL_UNRESOLVED","调用未决，不能判为确定失败");
            r.setStatus(ImageGenStatus.FAILED);r.setOutcome(outcome);r.setResultCode(code);
            r.setFailureReason(uncertain?"调用结果未知；当前供应商不支持查询，请核实":"输入或目标已失效");
            r.setAttemptDecision(uncertain?"UNRESOLVED":"FINAL");if(!uncertain)r.setCompletedAt(OffsetDateTime.now());
            records.saveAndFlush(r);return r;
        });
    }
    public ImageGenRecord reuse(Manifest m,Panel p) {
        return transactions.execute(tx -> {
            locks(m,p);ImageGenRecord r=find(m,p);if(r!=null)return r;
            if(count(" AND chapter_id=? AND image_number=?",new Object[]{m.chapterId(),p.imageNumber()})>0)throw new NoPermit("TARGET_CALL_UNRESOLVED");
            MangaImage image=images.findByChapterIdAndImageNumber(m.chapterId(),p.imageNumber()).orElseThrow(()->new NoPermit("TARGET_CHANGED"));
            r=create(m,p);r.setAttemptDecision("FINAL");r.setOutcome("REUSED");r.setStatus(ImageGenStatus.SUCCEEDED);
            r.setImagePath(image.getImagePath());r.setMangaImageId(image.getId());r.setResultImageVersion(image.getVersion());r.setCandidateContentType(image.getContentType());r.setCandidateSize(image.getSizeBytes());r.setCompletedAt(OffsetDateTime.now());
            return records.saveAndFlush(r);
        });
    }
    public ImageGenRecord mutate(Manifest m,Panel p,long token,Runnable lease,Consumer<ImageGenRecord> mutation) {
        return transactions.execute(tx -> {
            locks(m,p);lease.run();ImageGenRecord r=find(m,p);if(r==null)throw new IllegalStateException("Missing call evidence");
            em.lock(r,LockModeType.PESSIMISTIC_WRITE);
            if(r.getExecutionToken()!=token)throw new ImageRequestException("RECOVERY_CONFLICT","执行代次已变化");
            mutation.accept(r);records.saveAndFlush(r);lease.run();return r;
        });
    }
    public static String status(ImageGenRecord r) {
        if(r.getStatus()==ImageGenStatus.SUCCEEDED)return "SUCCEEDED";
        if("UNRESOLVED".equals(r.getAttemptDecision()))return "NEEDS_REVIEW";
        return "STALE".equals(r.getOutcome())?"STALE":"FAILED";
    }
    public static class NoPermit extends RuntimeException { public NoPermit(String message) { super(message); } }
    public static class ExistingEvidence extends RuntimeException { public final ImageGenRecord record;ExistingEvidence(ImageGenRecord r) { record=r; } }
}
