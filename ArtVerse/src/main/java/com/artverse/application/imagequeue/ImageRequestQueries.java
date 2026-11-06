package com.artverse.application.imagequeue;

import com.artverse.domain.*;
import com.artverse.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

@Service @RequiredArgsConstructor
public class ImageRequestQueries {
    private final ImageGenerationObjects objects;
    private final ImageRedisState state;
    private final ImageGenRecordRepository records;
    private final MangaImageRepository images;
    private final ChapterRepository chapters;
    public Manifest manifest(long user,UUID root) {
        UUID canonical=objects.alias(user,root).map(ImageGenerationObjects.Alias::canonicalRoot).orElse(root);
        Manifest m=objects.find(user,canonical).orElseThrow(()->new com.artverse.common.BusinessException(404,"Image request not found"));
        if(m.chapterId()!=null)chapters.findByIdForIdempotencyAndUserId(m.chapterId(),user).orElseThrow(()->new com.artverse.common.BusinessException(404,"Chapter not found"));
        return m;
    }
    public Map<String,Object> get(long user,UUID root) { return snapshot(manifest(user,root)); }
    public Map<String,Object> panel(Manifest m,Panel p) {
        return panel(m,p,true);
    }
    /** Model reads observe the same evidence without rebuilding or locking Redis state. */
    public Map<String,Object> readOnlyPanel(Manifest m,Panel p) {
        return panel(m,p,false);
    }
    private Map<String,Object> panel(Manifest m,Panel p,boolean rebuild) {
        ImageGenRecord r=records.findByUserIdAndRequestId(m.userId(),p.operationId()).orElse(null);
        Map<String,String> runtime;boolean redis=true;
        try { runtime=state.read(state.state(m,p)); } catch(RuntimeException e) { runtime=Map.of();redis=false; }
        String status=runtime.get("status"),source=status==null?null:"redis";
        if(r!=null&&("FINAL".equals(r.getAttemptDecision())||status==null)) {
            String durable=ImageGenerationEvidence.status(r);
            if("FINAL".equals(r.getAttemptDecision())) { status=durable;source="db"; }
            if(rebuild&&redis&&!durable.equals(runtime.get("status"))) {
                String owner="rebuild:"+UUID.randomUUID();boolean locked=false;
                try {
                    locked=state.lock(state.opLock(m,p),owner,Duration.ofSeconds(5));
                    if(locked) {
                        var latest=records.findByUserIdAndRequestId(m.userId(),p.operationId()).orElseThrow();
                        state.evidence(m,p,owner,ImageGenerationEvidence.status(latest));
                        runtime=state.read(state.state(m,p));status=runtime.get("status");source="rebuild";
                    }
                }catch(RuntimeException unavailable) { /* Only proven final outcomes can fall back to DB. */ }
                finally { if(locked)try { state.unlock(state.opLock(m,p),owner); }catch(RuntimeException ignored) {} }
            } else if(redis&&durable.equals(runtime.get("status")))source="redis";
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("request_id",m.rootId());result.put("operation_id",p.operationId());result.put("chapter_id",m.chapterId());result.put("image_number",p.imageNumber());
        result.put("execution_lane",m.executionLane());result.put("status",status);result.put("status_source",source);
        result.put("state_epoch",runtime.get("state_epoch"));result.put("version",Long.parseLong(runtime.getOrDefault("version","0")));
        result.put("model",m.provider().model());result.put("size",m.size());result.put("prompt",p.sceneOverride()==null?p.prompt():p.sceneOverride());
        result.put("provider_attempt",r==null?0:r.getProviderAttempt());result.put("record_id",r==null?null:r.getId());
        result.put("outcome",r==null?null:r.getOutcome());result.put("error_code",status==null?"STATUS_UNAVAILABLE":r==null?null:r.getResultCode());
        result.put("blocked_reason",runtime.get("blocked_reason"));
        result.put("manga_image_id",r==null?null:r.getMangaImageId());result.put("image_version",r==null||r.getResultImageVersion()==null?null:r.getResultImageVersion().toString());
        result.put("image_path",r==null?null:r.getImagePath());result.put("image_url",r==null||r.getImagePath()==null?null:"/static/manga/"+r.getImagePath());
        result.put("content_type",r==null?null:r.getCandidateContentType());result.put("size_bytes",r==null?null:r.getCandidateSize());
        result.put("completed_at",r==null?null:r.getCompletedAt());result.put("status_started_at",runtime.get("updated_at"));
        result.put("last_activity_at",runtime.getOrDefault("last_heartbeat_at",runtime.get("updated_at")));
        result.put("recovery_version",r==null?0:r.getRecoveryVersion());result.put("review_requested_at",r==null?null:r.getReviewRequestedAt());
        result.put("expected_image_id",p.expectedImageId());result.put("expected_image_version",p.expectedImageVersion()==null?null:p.expectedImageVersion().toString());
        boolean review="NEEDS_REVIEW".equals(status);
        result.put("review_reason",review?"PROVIDER_RESULT_UNKNOWN":null);
        result.put("blocks_serial_lane",review&&m.serial());
        result.put("evidence_summary",review?(r!=null&&r.getCandidateHash()!=null?"已登记候选图片；可核实存储并恢复提交":"调用可能受理；当前供应商适配器不支持查询或取消，需人工核实"):null);
        List<String> actions=new ArrayList<>();
        if(review&&redis) { actions.add("RECHECK");actions.add("REQUEST_REVIEW");if(r!=null&&r.getCandidateHash()!=null)actions.add("RESUME_SAFE"); }
        if(r!=null&&"EXTERNAL_ENDED".equals(r.getRecoveryDecision())) { actions.add("RELEASE_CONFIRMED");actions.add("ABANDON_AND_REGENERATE"); }
        OffsetDateTime nextCheck=null;
        if(review&&r!=null&&r.getRecoveryAudit()!=null)try {
            var audit=ImageJson.read(r.getRecoveryAudit(),List.class);
            for(Object value:audit) {
                var entry=(Map<?,?>)value;
                if("PENDING".equals(entry.get("recovery_state"))) {
                    OffsetDateTime deadline=OffsetDateTime.parse(entry.get("created_at").toString()).plusSeconds(180);
                    if(deadline.isAfter(OffsetDateTime.now()))nextCheck=deadline;
                }
            }
        }catch(RuntimeException ignored) { /* Corrupt audit is handled by the recovery API. */ }
        result.put("recovery_actions",actions);result.put("recovery_in_progress",nextCheck!=null);result.put("next_check_at",nextCheck);
        if(r!=null&&r.getMangaImageId()!=null)result.put("result_deleted",!images.existsById(r.getMangaImageId()));
        else result.put("result_deleted",r!=null&&Boolean.TRUE.equals(r.getIsDeleted()));
        return result;
    }
    public Map<String,Object> snapshot(Manifest m) {
        List<Map<String,Object>> rows=new ArrayList<>();int complete=0,failed=0,uncertain=0;String current="QUEUED";Integer currentNumber=null;
        for(Panel p:m.panels()) {
            var item=panel(m,p);String s=(String)item.get("status");
            if(s!=null||item.get("record_id")!=null)rows.add(item);
            if("SUCCEEDED".equals(s))complete++;else if("FAILED".equals(s))failed++;else if("NEEDS_REVIEW".equals(s))uncertain++;
            if(!terminal(s)&&currentNumber==null) { current=s;currentNumber=p.imageNumber(); }
            if("STALE".equals(s))current="STALE";
        }
        String rootStatus;
        if(m.panels().isEmpty())rootStatus="SUCCEEDED";
        else if(!m.chapter())rootStatus=(String)panel(m,m.panels().getFirst()).get("status");
        else if(uncertain>0)rootStatus="NEEDS_REVIEW";
        else if(rows.stream().anyMatch(i->"STALE".equals(i.get("status"))))rootStatus="STALE";
        else if(complete+failed==m.panels().size())rootStatus=failed==0?"SUCCEEDED":complete==0?"FAILED":"PARTIAL_FAILED";
        else rootStatus=current==null&&"ACCEPTED".equals(submission(m))?"QUEUED":current;
        Map<String,Object> result=new LinkedHashMap<>();result.put("request_id",m.rootId());result.put("operation",m.operation());result.put("execution_mode",m.executionMode());result.put("execution_lane",m.executionLane());
        result.put("status",rootStatus);result.put("status_source",rows.stream().anyMatch(i->"db".equals(i.get("status_source")))?"db":rows.stream().anyMatch(i->"rebuild".equals(i.get("status_source")))?"rebuild":rows.stream().allMatch(i->i.get("status_source")==null)?null:"redis");
        result.put("state_epoch",rows.isEmpty()?null:rows.getLast().get("state_epoch"));result.put("version",rows.stream().mapToLong(i->((Number)i.get("version")).longValue()).sum());
        result.put("total_images",m.panels().size());result.put("current_image_number",currentNumber);result.put("completed_images",complete);result.put("failed_images",failed);result.put("uncertain_images",uncertain);
        result.put("blocked_images",Math.max(0,m.panels().size()-rows.size()));result.put("images",rows);
        result.put("status_url","/api/image-generation/requests/"+m.rootId());result.put("events_url","/api/image-generation/requests/"+m.rootId()+"/events");
        result.put("conversation_id",m.conversationId());result.put("submission_status",m.panels().isEmpty()?"NO_WORK":submission(m));
        result.put("error_code",rootStatus==null?"STATUS_UNAVAILABLE":null);return result;
    }
    private String submission(Manifest m) {
        try { return state.read(state.root(m.userId(),m.rootId())).getOrDefault("submission_status","UNKNOWN"); }
        catch(RuntimeException e) { return "UNKNOWN"; }
    }
    public String readOnlySubmission(Manifest m) { return submission(m); }
}
