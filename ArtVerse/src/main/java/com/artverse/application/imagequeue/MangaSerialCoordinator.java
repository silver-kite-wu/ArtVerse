package com.artverse.application.imagequeue;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

@Service @RequiredArgsConstructor
public class MangaSerialCoordinator {
    private final ImageRedisState state;
    private final ImageRequestSubmissionService submissions;
    private final ImageCommandPublisher publisher;
    private final ImageRequestQueries queries;
    private final ImageGenerationEvidence evidence;
    private final com.artverse.persistence.MangaImageRepository images;

    public boolean advance(Command command,Manifest m) {
        if(!m.chapter()||!"SERIAL_MODEL".equals(m.executionLane()))throw new IllegalArgumentException("Invalid chapter lane");
        String key="image:advance-lock:"+m.userId()+":{"+m.rootId()+"}",owner=UUID.randomUUID().toString();
        if(!state.lock(key,owner,Duration.ofSeconds(30))) { waitNext(command,m);return false; }
        try {
            state.accepted(m);
            var root=state.read(state.root(m.userId(),m.rootId()));
            long transferred=Long.parseLong(root.getOrDefault("last_transferred_generation","-1"));
            if(command.generation()<=transferred&&"true".equals(root.get("successor_confirmed")))return false;
            for(int cursor=0;cursor<m.panels().size();cursor++) {
                Panel p=m.panels().get(cursor);var r=evidence.find(m,p);
                String status=r==null?state.read(state.state(m,p)).get("status"):ImageGenerationEvidence.status(r);
                if(r!=null&&"UNRESOLVED".equals(r.getAttemptDecision())&&state.value(state.opLock(m,p))!=null)status=state.read(state.state(m,p)).get("status");
                if("SUCCEEDED".equals(status)||"FAILED".equals(status)) {
                    if(state.value(state.opLock(m,p))!=null) { waitNext(command,m);return false; }continue;
                }
                if("STALE".equals(status)||"NEEDS_REVIEW".equals(status)) { publisher.quarantine(command,status);return false; }
                if(status==null) {
                    // Existing images remain intact. This plan fills gaps only.
                    if(images.findByChapterIdAndImageNumber(m.chapterId(),p.imageNumber()).isPresent()) {
                        if(state.value(state.targetLock(m,p))!=null) { waitNext(command,m);return false; }
                        try { evidence.reuse(m,p);continue; }
                        catch(ImageGenerationEvidence.NoPermit busy) { waitNext(command,m);return false; }
                    }
                    try {
                        var target=submissions.reserve(m,p);
                        if(target.operationId().equals(p.operationId()))publisher.execute(m,p,cursor);
                        // Foreign targets are only waited on; no duplicate IMAGE_EXECUTE is published.
                    } catch(ImageRequestException e) {
                        if(!"IMAGE_GENERATION_IN_PROGRESS".equals(e.getData().get("error_code")))throw e;
                    }
                }
                waitNext(command,m);return false;
            }
            state.expireRoot(m);return true;
        } finally { state.unlock(key,owner); }
    }
    private void waitNext(Command old,Manifest m) {
        publisher.publish(publisher.nextAdvance(m,old.generation()),"advance.wait.x","wait");
        // Never write the transfer receipt before successor confirmation. Monotonic under root lock.
        state.eval("""
            local g=tonumber(redis.call('hget',KEYS[1],'last_transferred_generation') or '-1')
            if g<tonumber(ARGV[1]) then redis.call('hset',KEYS[1],'last_transferred_generation',ARGV[1],'successor_confirmed','true') end return 1
            """,List.of(state.root(m.userId(),m.rootId())),Long.toString(old.generation()));
    }
}
