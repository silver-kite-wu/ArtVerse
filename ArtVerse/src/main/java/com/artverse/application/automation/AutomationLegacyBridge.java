package com.artverse.application.automation;

import com.artverse.domain.ScheduledMangaGeneration;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Keeps the original chapter task IDs and APIs while making the plan the trigger authority. */
@Service
@RequiredArgsConstructor
public class AutomationLegacyBridge {
    private final AutomationStore store;
    private final ObjectMapper mapper;

    @Transactional
    public void register(ScheduledMangaGeneration task) {
        if(task.getAutomationId()!=null)return;
        var payload=mapper.createObjectNode();
        payload.put("version",1);payload.put("storyId",task.getChapter().getStory().getId());
        payload.putArray("chapterIds").add(task.getChapter().getId());payload.put("inputPolicy","FIXED_SNAPSHOT");
        payload.set("chapterSnapshots",mapper.createArrayNode().add(store.node(task.getInputSnapshot())));
        if(task.getModelConfigId()!=null)payload.put("modelConfigId",task.getModelConfigId());payload.put("model",task.getModelName());
        var definition=new AutomationDefinition("章节预约生图 #"+task.getChapter().getChapterNumber(),"IMAGE","MANGA_IMAGE","生成章节缺失分格",
                new AutomationDefinition.Schedule("ONCE",task.getRequestedAt().atZoneSameInstant(java.time.ZoneId.of(task.getTimezoneId())).toLocalDateTime().toString(),null,task.getTimezoneId(),List.of()),
                payload,new AutomationDefinition.Limits(60,10,7200),"RUN_LATEST","SKIP",null,null,"CHAPTER",null);
        store.jdbc().update("INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,next_run_at,idempotency_key,request_hash) VALUES (?,?,?,'IMAGE','MANGA_IMAGE',?::jsonb,?,?,?,?) ON CONFLICT DO NOTHING",
                task.getId(),task.getUser().getId(),definition.name(),store.json(definition),task.getTimezoneId(),task.getRequestedAt(),"legacy-scheduled-manga:"+task.getId(),AutomationStore.hash(store.json(definition)));
        task.setAutomationId(task.getId());
        store.jdbc().update("UPDATE scheduled_manga_generation SET automation_id=? WHERE id=?",task.getId(),task.getId());
    }
    @Transactional
    public void cancelFromLegacy(ScheduledMangaGeneration task) {
        if(task.getAutomationId()==null)return;
        store.plan(task.getAutomationId(),task.getUser().getId(),true);
        store.jdbc().update("UPDATE automation SET status='COMPLETED',next_run_at=NULL,version=version+1,updated_at=now() WHERE id=? AND status<>'ARCHIVED'",task.getAutomationId());
        if(task.getAutomationRunId()!=null)store.jdbc().update("UPDATE automation_run SET state='CANCELLED',completed_at=now(),lease_owner=NULL,lease_until=NULL WHERE id=? AND state IN ('QUEUED','RUNNING','RETRY_WAIT','WAITING_USER','CANCEL_REQUESTED')",task.getAutomationRunId());
    }
    @Transactional
    public void retryFromLegacy(ScheduledMangaGeneration task) {
        if(task.getAutomationId()==null)return;
        var plan=store.plan(task.getAutomationId(),task.getUser().getId(),true);
        if(task.getAutomationRunId()==null)return;
        store.jdbc().update("UPDATE automation_run SET state='QUEUED',next_attempt_at=now(),completed_at=NULL,error_code=NULL,waiting_reason=NULL,lease_owner=NULL,lease_until=NULL WHERE id=? AND state IN ('FAILED','PARTIAL_FAILED')",task.getAutomationRunId());
        store.jdbc().update("UPDATE automation SET status='ACTIVE',next_run_at=NULL,updated_at=now() WHERE id=? AND status='COMPLETED'",plan.id());
    }
}
