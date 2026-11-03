package com.artverse.application.imagequeue;

import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;
import static com.artverse.application.imagequeue.ImageRequestInputs.*;

@Service @RequiredArgsConstructor
public class ImageRecoveryService {
    private final ImageRequestQueries queries;
    private final ImageGenerationEvidence evidence;
    private final ImageGenRecordRepository records;
    private final ImageExecutionLeases leases;
    private final ImageGenerationObjects objects;
    private final ImageRedisState state;
    private final ImageCommandPublisher publisher;
    private final ImageRequestSubmissionService submissions;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    /** Called only after the ADMIN controller has resolved the owner from durable evidence. */
    public Map<String,Object> reviewDetail(long user,UUID root,UUID operation) {
        Manifest m=objects.load(user,root);Panel p=panel(m,operation);ImageGenRecord r=evidence.find(m,p);
        if(r==null)throw new com.artverse.common.BusinessException(404,"Operation not found");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("request_id",root);result.put("operation_id",operation);result.put("provider",m.provider().provider());result.put("provider_label",m.provider().label());result.put("model",m.provider().model());
        result.put("provider_started_at",r.getProviderStartedAt());result.put("provider_attempt",r.getProviderAttempt());result.put("execution_token",Long.toString(r.getExecutionToken()));result.put("recovery_version",r.getRecoveryVersion());
        result.put("attempt_decision",r.getAttemptDecision());result.put("outcome",r.getOutcome());result.put("candidate_registered",r.getCandidateHash()!=null);result.put("query_supported",false);result.put("cancel_supported",false);
        result.put("evidence_summary",r.getCandidateHash()!=null?"存在候选凭据，应先重新核实或安全恢复；有效候选不能人工丢弃后重新收费":"当前适配器无查询/取消能力，请结合供应商后台和开始时间核实；无法确定必须保持未决");return result;
    }

    public Map<String,Object> action(long user,UUID root,UUID operation,Map<String,Object> body,boolean admin) {
        // The ADMIN controller already binds the owner through durable operation evidence.
        // Deleted chapters must not make unresolved global/serial permits impossible to review.
        Manifest m=admin?objects.load(user,root):queries.manifest(user,root);Panel p=panel(m,operation);
        UUID actionId=uuid(text(body,"action_id"));String action=text(body,"action");
        if("ADMIN_DECISION".equals(action)&&!admin)throw new com.artverse.common.BusinessException(403,"Forbidden");
        if(!Set.of("RECHECK","REQUEST_REVIEW","RESUME_SAFE","RELEASE_CONFIRMED","ADMIN_DECISION").contains(action))throw new ImageRequestException("INVALID_INPUT","不支持的恢复动作");
        String inputHash=hash(body);
        ImageGenRecord current=evidence.find(m,p);
        if(current==null)throw new ImageRequestException("RECOVERY_UNAVAILABLE","尚无调用凭据；请使用原身份重试提交");
        var claim=leases.acquire(m,p,false);
        if(claim==null)throw new ImageRequestException("RECOVERY_CONFLICT","原执行者仍在处理，请稍后核实");
        try(claim) {
            current=evidence.find(m,p);
            List<Map<String,Object>> history=audit(current);
            var replay=history.stream().filter(a->actionId.toString().equals(a.get("action_id"))).findFirst();
            if(replay.isPresent()) {
                if(!inputHash.equals(replay.get().get("input_hash")))throw new ImageRequestException("IDEMPOTENCY_CONFLICT","action_id 已绑定其他恢复输入");
                if("DONE".equals(replay.get().get("state")))return response(m,p,actionId);
            } else {
                validateAction(action,current,body);
                if("ADMIN_DECISION".equals(action))validateCandidate(m,p,current,body);
                if(history.size()>=100)throw new ImageRequestException("RATE_LIMITED","恢复动作已达上限，请联系管理员");
                Long expected=number(body.get("expected_recovery_version"));
                if(expected==null||expected!=current.getRecoveryVersion())throw new ImageRequestException("RECOVERY_CONFLICT","核实版本已变化，请刷新");
                String rate="image:recovery-rate:{user:"+user+"}";
                if(!admin&&state.eval("local n=redis.call('incr',KEYS[1]);if n==1 then redis.call('expire',KEYS[1],60) end return n",List.of(rate))>3)
                    throw new ImageRequestException("RATE_LIMITED","每分钟最多提交三次核实动作");
                if("RECHECK".equals(action)&&history.stream().anyMatch(a->"RECHECK".equals(a.get("action"))&&OffsetDateTime.parse(a.get("created_at").toString()).plusSeconds(60).isAfter(OffsetDateTime.now())))
                    throw new ImageRequestException("RATE_LIMITED","请等待 60 秒后再次核实");
                Map<String,Object> entry=new LinkedHashMap<>();entry.put("action_id",actionId.toString());entry.put("action",action);entry.put("input_hash",inputHash);entry.put("state","PENDING");entry.put("created_at",OffsetDateTime.now().toString());history.add(entry);
                if("RECHECK".equals(action)||"RESUME_SAFE".equals(action))entry.put("recovery_state","PENDING");
                if(admin)for(String field:List.of("reviewer_id","evidence_source","evidence_reference","conclusion","provider_attempt","execution_token"))if(body.containsKey(field))entry.put(field,body.get(field));
                long token=current.getExecutionToken();
                evidence.mutate(m,p,token,claim::assertValid,r->{ r.setRecoveryVersion(r.getRecoveryVersion()+1);r.setRecoveryAudit(ImageJson.write(history)); });
            }
            current=evidence.find(m,p);
            switch(action) {
                case "RECHECK" -> publisher.publish(new Command(1,"IMAGE_RECOVER",UUID.randomUUID(),user,m.rootId(),p.operationId(),m.submissionHash(),m.panels().indexOf(p),0,0,0,actionId),"recovery.x","recover");
                case "REQUEST_REVIEW" -> evidence.mutate(m,p,current.getExecutionToken(),claim::assertValid,r->r.setReviewRequestedAt(OffsetDateTime.now()));
                case "ADMIN_DECISION" -> {
                    if(!admin)throw new com.artverse.common.BusinessException(403,"Forbidden");
                    decide(m,p,current,body,claim);
                    if(m.chapter())publisher.advance(m);
                }
                case "RESUME_SAFE" -> {
                    if(current.getStatus()!=ImageGenStatus.SUCCEEDED&&current.getCandidateHash()==null)throw new ImageRequestException("EXTERNAL_CALL_UNRESOLVED","没有安全恢复依据，请申请人工核实");
                    publisher.execute(m,p,m.panels().indexOf(p));if(m.chapter())publisher.advance(m);
                }
                case "RELEASE_CONFIRMED" -> {
                    if(!"FINAL".equals(current.getAttemptDecision()))throw new ImageRequestException("EXTERNAL_CALL_UNRESOLVED","旧调用尚未核实结束");
                    state.evidence(m,p,claim.owner,ImageGenerationEvidence.status(current));submissions.release(m,p);
                }
                default -> throw new IllegalStateException();
            }
            completeAudit(m,p,actionId,claim);return response(m,p,actionId);
        }
    }
    private void validateCandidate(Manifest m,Panel p,ImageGenRecord r,Map<String,Object> body) {
        if(!"UNKNOWN".equals(text(body,"conclusion"))&&r.getCandidateHash()!=null&&objects.candidate(r.getCandidateObjectKey(),r.getCandidateHash(),r.getCandidateSize(),r.getCandidateContentType(),p.operationId(),r.getExecutionToken()))
            throw new ImageRequestException("RECOVERY_CONFLICT","已有有效候选图片，请先核实并安全恢复原结果");
    }
    private void validateAction(String action,ImageGenRecord r,Map<String,Object> body) {
        if("ADMIN_DECISION".equals(action)) {
            if(!"UNRESOLVED".equals(r.getAttemptDecision()))throw new ImageRequestException("RECOVERY_CONFLICT","调用已有确定结论，不能再次人工终结");
            Long attempt=number(body.get("provider_attempt")),token=number(body.get("execution_token"));
            if(attempt==null||token==null||attempt!=r.getProviderAttempt()||token!=r.getExecutionToken())throw new ImageRequestException("RECOVERY_CONFLICT","调用代次已变化");
            String source=text(body,"evidence_source"),reference=text(body,"evidence_reference"),conclusion=text(body,"conclusion");
            if(source.isBlank()||source.length()>200||reference.isBlank()||reference.length()>500)throw new ImageRequestException("INVALID_INPUT","请填写脱敏证据来源和引用");
            if(!"UNKNOWN".equals(conclusion)&&(!Set.of("NOT_ACCEPTED","ENDED_WITHOUT_RESULT").contains(conclusion)||!Boolean.TRUE.equals(body.get("confirm_external_ended"))))throw new ImageRequestException("EXTERNAL_CALL_UNRESOLVED","必须明确核实供应商调用已结束或未受理");
        }
        if("RESUME_SAFE".equals(action)&&r.getStatus()!=ImageGenStatus.SUCCEEDED&&r.getCandidateHash()==null)throw new ImageRequestException("EXTERNAL_CALL_UNRESOLVED","没有安全恢复依据，请申请人工核实");
        if("RELEASE_CONFIRMED".equals(action)&&!"FINAL".equals(r.getAttemptDecision()))throw new ImageRequestException("EXTERNAL_CALL_UNRESOLVED","旧调用尚未核实结束");
    }
    private void decide(Manifest m,Panel p,ImageGenRecord r,Map<String,Object> body,ImageExecutionLeases.Claim claim) {
        Long attempt=number(body.get("provider_attempt")),token=number(body.get("execution_token"));
        if(attempt==null||token==null||attempt!=r.getProviderAttempt()||token!=r.getExecutionToken())throw new ImageRequestException("RECOVERY_CONFLICT","调用代次已变化");
        String source=text(body,"evidence_source"),reference=text(body,"evidence_reference"),conclusion=text(body,"conclusion");
        if(source.isBlank()||reference.isBlank()||reference.length()>500)throw new ImageRequestException("INVALID_INPUT","请填写脱敏证据来源和引用");
        if("UNKNOWN".equals(conclusion))return;
        validateCandidate(m,p,r,body);
        if(!Set.of("NOT_ACCEPTED","ENDED_WITHOUT_RESULT").contains(conclusion)||!Boolean.TRUE.equals(body.get("confirm_external_ended")))
            throw new ImageRequestException("EXTERNAL_CALL_UNRESOLVED","必须明确核实供应商调用已结束或未受理");
        // The op/target lease proves no current worker owns it. Advancing token revokes every old committer.
        evidence.mutate(m,p,r.getExecutionToken(),claim::assertValid,record -> {
            record.setExecutionToken(record.getExecutionToken()+1);record.setAttemptDecision("FINAL");record.setOutcome("CONFIRMED_ENDED");record.setStatus(ImageGenStatus.FAILED);
            record.setRecoveryDecision("EXTERNAL_ENDED");record.setResultCode(null);record.setCompletedAt(OffsetDateTime.now());
        });
        state.evidence(m,p,claim.owner,"FAILED");submissions.release(m,p);
    }
    public void recover(Command command,Manifest m) {
        Panel p=panel(m,command.operationId());var claim=leases.acquire(m,p,false);
        ImageGenRecord original=evidence.find(m,p);
        if(original!=null) {
            var action=audit(original).stream().filter(a->Objects.toString(command.actionId(),"").equals(a.get("action_id"))).findFirst();
            if(action.isPresent()&&OffsetDateTime.parse(action.get().get("created_at").toString()).plusSeconds(180).isBefore(OffsetDateTime.now())) {
                if(claim!=null)claim.close();publisher.quarantine(command,"RECOVERY_DEADLINE");return;
            }
        }
        if(claim==null) { if(command.deferCount()>=3)publisher.quarantine(command,"RECOVERY_BUSY");else publisher.publish(command.defer(30000),"recovery.wait.x","wait.30");return; }
        try(claim) {
            ImageGenRecord r=evidence.find(m,p);if(r==null)throw new ImageRequestException("RECOVERY_UNAVAILABLE","调用凭据缺失");
            if(r.getStatus()==ImageGenStatus.SUCCEEDED)state.evidence(m,p,claim.owner,"SUCCEEDED");
            else if("FINAL".equals(r.getAttemptDecision()))state.evidence(m,p,claim.owner,ImageGenerationEvidence.status(r));
            else if(r.getCandidateHash()!=null&&objects.candidate(r.getCandidateObjectKey(),r.getCandidateHash(),r.getCandidateSize(),r.getCandidateContentType(),p.operationId(),r.getExecutionToken())) {
                state.evidence(m,p,claim.owner,"COMMITTING");
                try { evidence.commit(m,p,r.getExecutionToken(),claim::assertValid);state.evidence(m,p,claim.owner,"SUCCEEDED"); }
                catch(ImageRequestException e) {
                    if(!Set.of("INPUT_STALE","TARGET_CONFLICT","CONFIG_CHANGED").contains(e.getData().get("error_code")))throw e;
                    evidence.finish(m,p,"STALE",e.getData().get("error_code").toString(),false,claim::assertValid);state.evidence(m,p,claim.owner,"STALE");
                }
            } else {
                // No query/cancel capability is claimed for Image2Client. One local check ends the chain.
                evidence.finish(m,p,"NEEDS_REVIEW","RESULT_UNCERTAIN",true,claim::assertValid);state.evidence(m,p,claim.owner,"NEEDS_REVIEW");
            }
            if(m.chapter())publisher.advance(m); // Confirm control responsibility before ACK of this recovery delivery.
            r=evidence.find(m,p);if("FINAL".equals(r.getAttemptDecision()))submissions.release(m,p);
            List<Map<String,Object>> history=audit(r);
            history.stream().filter(a->Objects.toString(command.actionId(),"").equals(a.get("action_id"))).forEach(a->{a.put("recovery_state","COMPLETE");a.put("completed_at",OffsetDateTime.now().toString());});
            evidence.mutate(m,p,r.getExecutionToken(),claim::assertValid,record->record.setRecoveryAudit(ImageJson.write(history)));
        }
    }
    private void completeAudit(Manifest m,Panel p,UUID action,ImageExecutionLeases.Claim claim) {
        ImageGenRecord r=evidence.find(m,p);List<Map<String,Object>> history=audit(r);
        history.stream().filter(e->action.toString().equals(e.get("action_id"))).forEach(e->e.put("state","DONE"));
        evidence.mutate(m,p,r.getExecutionToken(),claim::assertValid,record->record.setRecoveryAudit(ImageJson.write(history)));
    }
    private List<Map<String,Object>> audit(ImageGenRecord r) {
        try { return mapper.readValue(Objects.toString(r.getRecoveryAudit(),"[]"),new TypeReference<>() {}); }
        catch(Exception e) { throw new ImageRequestException("RECOVERY_UNAVAILABLE","恢复审计无法读取"); }
    }
    private Map<String,Object> response(Manifest m,Panel p,UUID id) { return Map.of("request_id",m.rootId(),"action_id",id,"image",queries.panel(m,p)); }
    public Panel panel(Manifest m,UUID op) { return m.panels().stream().filter(p->p.operationId().equals(op)).findFirst().orElseThrow(()->new com.artverse.common.BusinessException(404,"Operation not found")); }
    public Map<String,Object> replace(long user,UUID root,UUID operation,Map<String,Object> body) {
        Manifest m=queries.manifest(user,root);Panel p=panel(m,operation);ImageGenRecord r=evidence.find(m,p);
        if(r==null||!"FINAL".equals(r.getAttemptDecision())||!"EXTERNAL_ENDED".equals(r.getRecoveryDecision()))throw new ImageRequestException("EXTERNAL_CALL_UNRESOLVED","请先申请人工核实旧调用已结束");
        if(!Boolean.TRUE.equals(body.get("accept_possible_duplicate_charge")))throw new ImageRequestException("INVALID_INPUT","请确认新的付费生成意图");
        UUID newId=uuid(text(body,"request_id"));if(newId.equals(root))throw new ImageRequestException("INVALID_INPUT","替代请求必须使用新身份");
        Long expected=number(body.get("expected_recovery_version"));
        if(expected==null||expected!=r.getRecoveryVersion())throw new ImageRequestException("RECOVERY_CONFLICT","核实版本已变化");
        transactions.executeWithoutResult(tx->{ evidence.locks(m,p);var current=evidence.find(m,p);
            if(current.getReplacementRequestId()!=null&&!current.getReplacementRequestId().equals(newId))throw new ImageRequestException("RECOVERY_CONFLICT","已有替代请求");
            current.setReplacementRequestId(newId);records.saveAndFlush(current);
        });
        Map<String,Object> input=new TreeMap<>(body);input.remove("accept_possible_duplicate_charge");input.remove("expected_recovery_version");
        input.remove("use_original_input");
        input.putIfAbsent("prompt",Objects.toString(p.sceneOverride(),m.chapterId()==null?p.prompt():""));
        input.putIfAbsent("size",m.size());
        if(!input.containsKey("config_id")&&!input.containsKey("api_key")) {
            if(m.provider().configId()!=null)input.put("config_id",m.provider().configId());
            else { input.put("provider",m.provider().provider());input.put("api_key",m.provider().apiKey());input.put("base_url",m.provider().baseUrl()); }
            input.put("model",m.provider().model());
        }
        if(m.assetGroupId()!=null)input.putIfAbsent("asset_group_id",m.assetGroupId());
        if(m.conversationId()!=null)input.putIfAbsent("conversation_id",m.conversationId().toString());
        if(m.chapterId()==null)input.putIfAbsent("reference_images",m.references());
        var accepted=submissions.submit(user,m.chapterId(),p.imageNumber(),p.expectedImageId()==null?(m.chapterId()==null?"STANDALONE":"MANGA_SINGLE_GENERATE"):"MANGA_REGENERATE",input,newId.toString());
        return queries.snapshot(accepted.manifest());
    }
}
