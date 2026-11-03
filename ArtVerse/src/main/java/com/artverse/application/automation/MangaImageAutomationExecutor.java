package com.artverse.application.automation;

import com.artverse.application.ScheduledMangaGenerationService;
import com.artverse.domain.ScheduledMangaInputSnapshot;
import com.artverse.persistence.ScheduledMangaGenerationRepository;
import com.artverse.persistence.ScheduledMangaPanelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class MangaImageAutomationExecutor implements AutomationExecutor {
    private final AutomationStore store;
    private final ScheduledMangaGenerationService manga;
    private final ScheduledMangaGenerationRepository tasks;
    private final ScheduledMangaPanelRepository panels;
    private final ObjectMapper mapper;
    public Set<String> keys() { return Set.of("MANGA_IMAGE"); }

    public Observation execute(AutomationStore.Run run) {
        if(run.inputSnapshot().path("imageCount").asInt()==0)return new Observation("SKIPPED","NO_MISSING_IMAGES","所选章节图片已齐全",null,0);
        for(var chapter:run.inputSnapshot().path("chapters")) {
            ScheduledMangaInputSnapshot snapshot=mapper.convertValue(chapter,ScheduledMangaInputSnapshot.class);
            if(snapshot.missingImageNumbers().isEmpty())continue;
            String target="chapter:"+snapshot.chapterId();
            var execution=store.executions(run.id()).stream().filter(e->target.equals(e.targetKey())).findFirst();
            UUID child=execution.map(AutomationStore.Execution::scheduledMangaId).orElse(null);
            if(child==null) {
                if(!store.owned(run))return Observation.running("执行权已转移");
                var legacy=store.jdbc().queryForList("SELECT id FROM scheduled_manga_generation WHERE automation_id=? AND chapter_id=? AND automation_run_id IS NULL",run.automationId(),snapshot.chapterId());
                if(!legacy.isEmpty()) {
                    child=(UUID)legacy.getFirst().get("id");
                    store.jdbc().update("UPDATE scheduled_manga_generation SET automation_run_id=? WHERE id=? AND automation_run_id IS NULL AND EXISTS(SELECT 1 FROM automation_run WHERE id=? AND lease_owner=? AND fencing_token=? AND lease_until>now() AND state='RUNNING')",run.id(),child,run.id(),run.leaseOwner(),run.fencingToken());
                } else child=manga.enqueueAutomation(run.userId(),snapshot,"automation:"+run.id()+":"+snapshot.chapterId(),run.automationId(),run.id());
                store.attach(run,target,"MANGA_IMAGE",null,child,null,null,null);
            }
            var task=tasks.findByIdAndUserId(child,run.userId()).orElseThrow();
            if(run.response()!=null&&"retry".equals(run.response().path("action").asText())
                    &&Set.of("FAILED","PARTIAL_FAILED").contains(task.getState().name())) {
                manga.retryAutomationChild(run.userId(),snapshot.chapterId(),child);
            }
        }
        if(run.response()!=null&&"retry".equals(run.response().path("action").asText()))store.jdbc().update("UPDATE automation_run SET response=NULL WHERE id=? AND fencing_token=?",run.id(),run.fencingToken());
        return observe(run,false);
    }
    public Observation cancel(AutomationStore.Run run) {
        var executions=store.executions(run.id());
        for(var execution:executions)if(execution.scheduledMangaId()!=null) {
            var task=tasks.findByIdAndUserId(execution.scheduledMangaId(),run.userId()).orElseThrow();
            if("SCHEDULED".equals(task.getState().name()))manga.cancelAutomationChild(run.userId(),task.getChapter().getId(),task.getId());
            // A provider request already in flight must be observed and its result preserved.
        }
        if(executions.isEmpty())return new Observation("CANCELLED",null,"本次运行已停止",null,0);
        return observe(run,true);
    }
    private Observation observe(AutomationStore.Run run,boolean cancelling) {
        int success=0,failed=0,cancelled=0;boolean pending=false;
        for(var execution:store.executions(run.id())) {
            if(execution.scheduledMangaId()==null)continue;
            var task=tasks.findByIdAndUserId(execution.scheduledMangaId(),run.userId()).orElseThrow();
            if(panels.countByTaskIdAndState(task.getId(),com.artverse.domain.ScheduledMangaPanel.State.UNCERTAIN)>0)
                return Observation.waiting("RESULT_UNCERTAIN","部分图片请求结果不确定，请核对供应商记录；成功图片已保留");
            switch(task.getState().name()) {
                case "SCHEDULED","RUNNING" -> pending=true;
                case "SUCCEEDED" -> success++;
                case "CANCELLED" -> cancelled++;
                case "STALE_REQUIRES_CONFIRMATION" -> { return Observation.waiting("INPUT_CHANGED","章节或参考素材已变化，请检查本次任务输入"); }
                default -> failed++;
            }
        }
        if(pending)return Observation.running(cancelling?"等待已派发的图片请求收尾":"正在逐格生成图片");
        if(failed>0)return new Observation(success>0?"PARTIAL_FAILED":"FAILED","IMAGE_FAILED","生图存在失败项；成功图片已保留",null,0);
        if(cancelled>0)return new Observation(success>0?"PARTIAL_FAILED":"CANCELLED",null,"已停止后续生图，已有结果保留",null,0);
        return Observation.done("章节图片生成完成");
    }
}
