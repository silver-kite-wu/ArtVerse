package com.artverse.application.imagequeue;

import com.artverse.ai.*;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

@Service @RequiredArgsConstructor
public class ImageCommandProcessor {
    private final ImageRedisState state;
    private final ImageExecutionLeases leases;
    private final ImageGenerationEvidence evidence;
    private final ImageGenerationObjects objects;
    private final ImageRequestInputs inputs;
    private final ImageRequestSubmissionService submissions;
    private final ImageCommandPublisher publisher;
    private final Image2Client model;
    private final ImageQueueProperties props;
    private final ImageRequestQueries queries;
    private final ImageExecutionFaults faults;

    /** Resource lifetime includes ACK and post-ACK notification; only the listener invokes ack. */
    public final class Completion implements AutoCloseable {
        private final Manifest manifest;private final Panel panel;private final ImageExecutionLeases.Claim claim;
        private final boolean release;private final String event;
        Completion(Manifest m,Panel p,ImageExecutionLeases.Claim c,boolean release,String event) { manifest=m;panel=p;claim=c;this.release=release;this.event=event; }
        public void afterAck() {
            faults.hit("AFTER_ACK",panel.operationId());
            if(event!=null)state.event(manifest,event,queries.panel(manifest,panel));
            if(release)submissions.release(manifest,panel);
        }
        @Override public void close() { if(claim!=null)claim.close(); }
    }
    public Completion execute(Command command,Manifest m) {
        Panel p=m.panels().get(command.cursor());
        if(!p.operationId().equals(command.operationId()))throw new IllegalArgumentException("Operation mismatch");
        if(command.notBefore()>System.currentTimeMillis()) { publisher.delay(command,m);return new Completion(m,p,null,false,null); }
        state.accepted(m);state.queued(m,p);
        ImageGenRecord old=evidence.find(m,p);
        var claim=leases.acquire(m,p,old==null);
        if(claim==null) { defer(command,m,"LEASE_OR_QUOTA");return new Completion(m,p,null,false,null); }
        try {
            claim.assertValid();
            old=evidence.find(m,p);
            if(old!=null)return restore(command,m,p,old,claim);
            if(m.chapterId()!=null) {
                var target=submissions.reserve(m,p,claim.owner);
                if(!target.operationId().equals(p.operationId())) { defer(command,m,"TARGET_BUSY");claim.close();return new Completion(m,p,null,false,null); }
            }
            if(!state.claim(m,p,claim.owner)) { defer(command,m,"STATE_CHANGED");claim.close();return new Completion(m,p,null,false,null); }
            var start=objects.start(m,p,props.getOperationTotalTimeout());
            state.event(m,"progress",queries.panel(m,p));
            List<Path> refs=new ArrayList<>();
            try {
                // Context IO is outside every DB transaction. Only input references may use temporary files.
                inputs.revalidate(m,p);
                for(String b64:m.references()) {
                    Path ref=Files.createTempFile("artverse-image-input-",".png");refs.add(ref);Files.write(ref,Base64.getDecoder().decode(b64));
                }
                ImageGenRecord permitted;
                faults.hit("BEFORE_PERMIT",p.operationId());
                try { permitted=evidence.permit(m,p,start,claim::assertValid); }
                catch(ImageGenerationEvidence.ExistingEvidence e) { return restore(command,m,p,e.record,claim); }
                catch(ImageGenerationEvidence.NoPermit e) { state.evidence(m,p,claim.owner,"QUEUED");defer(command,m,e.getMessage());claim.close();return new Completion(m,p,null,false,null); }
                long token=permitted.getExecutionToken();
                faults.hit("AFTER_PERMIT",p.operationId());
                state.transition(m,p,claim.owner,Status.GENERATING,token);claim.assertValid();
                state.event(m,"progress",queries.panel(m,p));
                GeneratedImage generated;
                try {
                    generated=model.generateScheduled(new ImageGenerationRequest(p.prompt(),m.provider().model(),m.size(),refs,m.colorMode()),m.provider()).block(props.getProviderTimeout());
                    if(generated==null||generated.sizeBytes()>32L*1024*1024)throw new ImageResultUncertainException(null);
                } catch(Exception e) {
                    claim.assertValid();evidence.finish(m,p,"NEEDS_REVIEW","RESULT_UNCERTAIN",true,claim::assertValid);
                    return isolate(command,m,p,claim,"NEEDS_REVIEW",false);
                }
                claim.assertValid();state.transition(m,p,claim.owner,Status.UPLOADING,token);
                String objectKey="generation-results/"+m.userId()+"/"+p.operationId()+"/"+token+"/result"+generated.extension();
                String sha;try(var stream=generated.openStream()) { sha=bytesHash(stream.readAllBytes()); }
                evidence.candidate(m,p,token,objectKey,sha,generated.contentType(),generated.sizeBytes(),claim::assertValid);
                Map<String,String> meta=Map.of("operation-id",p.operationId().toString(),"input-hash",p.inputHash(),"execution-token",Long.toString(token),"sha256",sha);
                boolean uploaded=false;
                for(int attempt=0;attempt<3&&!uploaded;attempt++) {
                    claim.assertValid();
                    try { objects.upload(objectKey,generated,meta);uploaded=objects.candidate(objectKey,sha,generated.sizeBytes(),generated.contentType(),p.operationId(),token); }
                    catch(RuntimeException e) {
                        // PUT may have succeeded despite a lost response. Do not call the model again.
                        try { uploaded=objects.candidate(objectKey,sha,generated.sizeBytes(),generated.contentType(),p.operationId(),token); }catch(RuntimeException ignored) {}
                        if(!uploaded&&attempt<2)Thread.sleep(250L*(attempt+1));
                    }
                }
                if(!uploaded) {
                    evidence.finish(m,p,"NEEDS_REVIEW","RESULT_UNCERTAIN",true,claim::assertValid);
                    return isolate(command,m,p,claim,"NEEDS_REVIEW",false);
                }
                faults.hit("AFTER_UPLOAD",p.operationId());
                state.transition(m,p,claim.owner,Status.COMMITTING,token);
                evidence.commit(m,p,token,claim::assertValid);
                faults.hit("AFTER_COMMIT",p.operationId());
                state.transition(m,p,claim.owner,Status.SUCCEEDED,token);
                state.retainTerminal(m,p,"SUCCEEDED");
                faults.hit("AFTER_REDIS_SUCCESS",p.operationId());
                return new Completion(m,p,claim,true,"image_result");
            } catch(ImageRequestException e) {
                String code=e.getData().get("error_code").toString();
                if(!Set.of("INPUT_STALE","TARGET_CONFLICT","CONFIG_CHANGED","INVALID_INPUT","PROVIDER_CONFIG_INVALID").contains(code))throw e;
                claim.assertValid();
                ImageGenRecord current=evidence.find(m,p);
                // Only a known candidate proves the paid response ended; otherwise keep UNRESOLVED.
                if(current!=null&&current.getProviderAttempt()>0&&current.getCandidateHash()==null)throw e;
                evidence.finish(m,p,"STALE",code,false,claim::assertValid);return isolate(command,m,p,claim,"STALE",true);
            } finally { for(Path ref:refs)try { Files.deleteIfExists(ref); }catch(Exception ignored) {} }
        } catch(RuntimeException e) { claim.close();throw e; }
        catch(Exception e) { claim.close();throw new IllegalStateException("Image processing interrupted",e); }
    }
    private Completion restore(Command command,Manifest m,Panel p,ImageGenRecord r,ImageExecutionLeases.Claim claim) {
        claim.assertValid();
        if(r.getStatus()==ImageGenStatus.SUCCEEDED) {
            state.evidence(m,p,claim.owner,"SUCCEEDED");return new Completion(m,p,claim,true,"image_result");
        }
        if("FINAL".equals(r.getAttemptDecision()))return isolate(command,m,p,claim,ImageGenerationEvidence.status(r),true);
        if(r.getCandidateHash()!=null&&objects.candidate(r.getCandidateObjectKey(),r.getCandidateHash(),r.getCandidateSize(),r.getCandidateContentType(),p.operationId(),r.getExecutionToken())) {
            state.evidence(m,p,claim.owner,"COMMITTING");
            try { evidence.commit(m,p,r.getExecutionToken(),claim::assertValid); }
            catch(ImageRequestException e) {
                if(!Set.of("TARGET_CONFLICT","INPUT_STALE","CONFIG_CHANGED").contains(e.getData().get("error_code")))throw e;
                evidence.finish(m,p,"STALE",e.getData().get("error_code").toString(),false,claim::assertValid);return isolate(command,m,p,claim,"STALE",true);
            }
            state.evidence(m,p,claim.owner,"SUCCEEDED");return new Completion(m,p,claim,true,"image_result");
        }
        evidence.finish(m,p,"NEEDS_REVIEW","RESULT_UNCERTAIN",true,claim::assertValid);
        return isolate(command,m,p,claim,"NEEDS_REVIEW",false);
    }
    private Completion isolate(Command command,Manifest m,Panel p,ImageExecutionLeases.Claim claim,String status,boolean release) {
        state.evidence(m,p,claim.owner,status);publisher.quarantine(command,status);
        return new Completion(m,p,claim,release,status.equals("NEEDS_REVIEW")?"needs_review":"image_error");
    }
    private void defer(Command command,Manifest m,String reason) {
        Panel p=m.panels().get(command.cursor());state.metadata(state.state(m,p),Map.of("blocked_reason",reason));
        publisher.delay(command.defer(command.deferCount()==0?30000:command.deferCount()==1?120000:600000),m);
    }
}
