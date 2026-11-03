package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.artverse.config.ArtVerseProperties;
import com.artverse.prompt.MangaPromptPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@RequiredArgsConstructor
public class ScheduledMangaGenerationService {
    private final ScheduledMangaGenerationRepository tasks;
    private final ScheduledMangaPanelRepository panels;
    private final ChapterRepository chapters;
    private final UserRepository users;
    private final MangaImageRepository images;
    private final ApiKeyService apiKeys;
    private final MangaGenerationService generation;
    private final MangaImageStorageService imageStorage;
    private final ScheduledMangaWorkerSlotService workerSlots;
    private final ArtVerseProperties properties;
    private final ObjectMapper mapper;
    @Value("${artverse.scheduled-manga.default-timezone:Asia/Shanghai}")
    private String defaultTimezone;
    @Value("${artverse.scheduled-manga.max-active-tasks-per-user:3}")
    private int maxActiveTasksPerUser;
    @Value("${artverse.scheduled-manga.progress-poll-interval-ms:1000}")
    private long progressPollIntervalMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String instanceId = UUID.randomUUID().toString();
    @Autowired @Lazy
    private ScheduledMangaGenerationService transactionalSelf;
    @Autowired @Lazy
    private com.artverse.application.automation.AutomationLegacyBridge automationLegacyBridge;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate automationJdbc;

    @Transactional(readOnly = true)
    public ScheduledMangaInputSnapshot previewAutomationInputs(Long userId, Long chapterId, UserProviderConfig config) {
        Chapter chapter=chapters.findByIdAndUserId(chapterId,userId)
                .orElseThrow(()->new BusinessException(404,"Chapter not found"));
        List<String> scenes;
        try { scenes=mapper.readValue(chapter.getScenesText(),new TypeReference<>(){}); }
        catch(Exception e) { throw new BusinessException(400,"Generate a valid storyboard before scheduling images"); }
        if(scenes==null||scenes.isEmpty()||scenes.size()!=chapter.getImageCount()||scenes.size()>60)
            throw new BusinessException(400,"Storyboard panel count is invalid");
        List<Integer> missing=new ArrayList<>();
        for(int i=1;i<=scenes.size();i++)if(images.findByChapterIdAndImageNumber(chapterId,i).isEmpty())missing.add(i);
        return makeSnapshot(chapter,scenes,missing,config);
    }

    /** The task center has already decided when to execute; this method never schedules a second future trigger. */
    @Transactional
    public void refreshAutomationChild(Long userId,UUID taskId,ScheduledMangaInputSnapshot snapshot) {
        var task=tasks.findByIdAndUserId(taskId,userId).orElseThrow(()->new BusinessException(404,"任务不存在"));
        if(task.getAutomationRunId()==null||task.getState()==ScheduledMangaGeneration.State.RUNNING)throw new BusinessException(409,"任务当前不能更新输入");
        if(panels.countByTaskIdAndState(taskId,ScheduledMangaPanel.State.UNCERTAIN)>0)throw new BusinessException(409,"请先核对不确定的图片结果");
        if(task.getState()==ScheduledMangaGeneration.State.SUCCEEDED)return;
        var existing=panels.findByTaskIdOrderByImageNumberAsc(taskId);
        for(var panel:existing)if(panel.getState()!=ScheduledMangaPanel.State.SUCCEEDED) {
            if(!snapshot.missingImageNumbers().contains(panel.getImageNumber()))panelState(panel,ScheduledMangaPanel.State.CANCELLED,null,"不再需要生成");
            else {panel.setPromptSnapshot(snapshot.scenes().get(panel.getImageNumber()-1));panel.setAttemptCount(0);panel.setNextAttemptAt(null);panelState(panel,ScheduledMangaPanel.State.PENDING,null,null);}
        }
        for(int number:snapshot.missingImageNumbers())if(existing.stream().noneMatch(panel->panel.getImageNumber()==number)) {
            var panel=new ScheduledMangaPanel();panel.setTask(task);panel.setImageNumber(number);panel.setPromptSnapshot(snapshot.scenes().get(number-1));panel.setState(ScheduledMangaPanel.State.PENDING);panels.save(panel);
        }
        task.setChapterVersion(snapshot.chapterVersion());task.setScenesSnapshot(write(snapshot.scenes()));task.setInputSnapshot(write(snapshot));task.setSnapshotHash(hashSnapshot(snapshot));task.setPromptVersion(snapshot.promptVersion());
        task.setModelConfigId(snapshot.modelConfigId());task.setModelName(snapshot.modelName());task.setState(ScheduledMangaGeneration.State.SCHEDULED);task.setRequestedAt(OffsetDateTime.now());task.setCompletedAt(null);task.setLeaseOwner(null);task.setLeaseUntil(null);task.setErrorCode(null);task.setErrorMessage(null);tasks.save(task);
    }

    /** The task center has already decided when to execute; this method never schedules a second future trigger. */
    @Transactional
    public UUID enqueueAutomation(Long userId,ScheduledMangaInputSnapshot snapshot,String key,UUID automationId,UUID runId) {
        var previous=tasks.findByUserIdAndIdempotencyKey(userId,key);
        if(previous.isPresent())return previous.get().getId();
        Chapter chapter=chapters.findByIdForIdempotencyAndUserId(snapshot.chapterId(),userId)
                .orElseThrow(()->new BusinessException(404,"Chapter not found"));
        users.findByIdForScheduledTaskCreation(userId).orElseThrow(()->new BusinessException(404,"User not found"));
        if(tasks.existsByChapterIdAndActive(chapter.getId())||generation.isGenerationRunning(chapter.getId()))
            throw new BusinessException(409,"Another image task is already running for this chapter");
        User user=users.findById(userId).orElseThrow();
        UserProviderConfig config=apiKeys.requireProviderConfigForModel(user,ApiKeyService.SLOT_IMAGE,snapshot.modelConfigId(),snapshot.modelName(),"Scheduled provider unavailable");
        ScheduledMangaInputSnapshot current=makeSnapshot(chapter,snapshot.scenes(),snapshot.missingImageNumbers(),config);
        if(!Objects.equals(hashSnapshot(current),hashSnapshot(snapshot)))throw new BusinessException(409,"Scheduled inputs changed");
        UUID id=UUID.randomUUID();
        int inserted=tasks.insertIfAbsent(id,userId,chapter.getId(),OffsetDateTime.now(),"UTC","SCHEDULED",snapshot.chapterVersion(),
                snapshot.modelConfigId(),snapshot.modelName(),write(snapshot.scenes()),write(snapshot),hashSnapshot(snapshot),snapshot.promptVersion(),key);
        ScheduledMangaGeneration task=tasks.findByUserIdAndIdempotencyKey(userId,key)
                .orElseThrow(()->new BusinessException(409,"Another image task is already running for this chapter"));
        if(inserted==1)for(int n:snapshot.missingImageNumbers()) {
            ScheduledMangaPanel panel=new ScheduledMangaPanel();panel.setTask(task);panel.setImageNumber(n);
            panel.setPromptSnapshot(snapshot.scenes().get(n-1));panel.setState(ScheduledMangaPanel.State.PENDING);panels.save(panel);
        }
        task.setAutomationId(automationId);task.setAutomationRunId(runId);tasks.save(task);
        return task.getId();
    }

    @Transactional
    public Map<String, Object> create(Long userId, Long chapterId, LocalDateTime localTime,
                                      String timezone, String model, Long configId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) throw new BusinessException(400, "A valid Idempotency-Key is required");
        var previous = tasks.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (previous.isPresent()) {
            if (!Objects.equals(previous.get().getChapter().getId(), chapterId))
                throw new BusinessException(409, "Idempotency-Key was already used for a different chapter");
            return summary(previous.get());
        }
        if (localTime == null) throw new BusinessException(400, "Execution time is required");
        ZoneId zone;
        String selectedTimezone=timezone==null||timezone.isBlank()?defaultTimezone:timezone;
        try { zone = ZoneId.of(selectedTimezone); } catch (Exception e) { throw new BusinessException(400, "Invalid timezone"); }
        List<ZoneOffset> validOffsets = zone.getRules().getValidOffsets(localTime);
        if (validOffsets.size() != 1) throw new BusinessException(400, "Execution time is ambiguous or does not exist in this timezone");
        ZonedDateTime due = ZonedDateTime.ofLocal(localTime, zone, validOffsets.getFirst());
        if (!due.isAfter(ZonedDateTime.now(zone).plusSeconds(15))) throw new BusinessException(400, "Execution time must be at least 15 seconds in the future");
        Chapter chapter = chapters.findByIdForIdempotencyAndUserId(chapterId, userId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        if(generation.isGenerationRunning(chapterId))throw new BusinessException(409,"Immediate image generation is already running for this chapter");
        List<String> scenes;
        try { scenes = mapper.readValue(chapter.getScenesText(), new TypeReference<>() {}); }
        catch (Exception e) { throw new BusinessException(400, "Generate a valid storyboard before scheduling images"); }
        if (scenes == null || scenes.isEmpty() || scenes.size() != chapter.getImageCount() || scenes.size() > 60)
            throw new BusinessException(400, "Storyboard panel count is invalid");
        if (tasks.existsByChapterIdAndActive(chapterId)) throw new BusinessException(409, "This chapter already has an active scheduled generation");
        User user = users.findByIdForScheduledTaskCreation(userId).orElseThrow(() -> new BusinessException(404, "User not found"));
        if(tasks.countActiveForUser(userId)>=Math.max(1,maxActiveTasksPerUser))throw new BusinessException(429,"You already have the maximum number of active scheduled image tasks");
        UserProviderConfig config;
        if(configId!=null&&(model==null||model.isBlank())) config=apiKeys.requireProviderConfig(user,
                new UserProviderConfig(ApiKeyService.SLOT_IMAGE,"","","","",""),configId,
                "Image provider configuration is unavailable");
        else if(model==null||model.isBlank()) config=apiKeys.requireActiveUserProviderConfig(user,ApiKeyService.SLOT_IMAGE,
                "Configure an active image provider before scheduling");
        else config=apiKeys.requireProviderConfigForModel(user,ApiKeyService.SLOT_IMAGE,configId,model,
                "Image provider configuration is unavailable");
        List<Integer> missing = new ArrayList<>();
        for (int i=0; i<scenes.size(); i++) if (images.findByChapterIdAndImageNumber(chapterId, i+1).isEmpty()) missing.add(i+1);
        if (missing.isEmpty()) throw new BusinessException(409, "All storyboard panels already have images");
        ScheduledMangaInputSnapshot snapshot=makeSnapshot(chapter,scenes,missing,config);
        String serializedScenes=write(scenes), serializedSnapshot=write(snapshot), snapshotHash=hashSnapshot(snapshot);
        UUID taskId=UUID.randomUUID();
        int inserted=tasks.insertIfAbsent(taskId,userId,chapterId,due.toOffsetDateTime(),zone.getId(),
                ScheduledMangaGeneration.State.SCHEDULED.name(),chapter.getVersion(),config.configId(),config.model(),
                serializedScenes,serializedSnapshot,snapshotHash,snapshot.promptVersion(),idempotencyKey);
        ScheduledMangaGeneration task=tasks.findByUserIdAndIdempotencyKey(userId,idempotencyKey).orElse(null);
        if(task==null)throw new BusinessException(409,"This chapter already has an active scheduled generation");
        if (!Objects.equals(task.getChapter().getId(), chapterId))
            throw new BusinessException(409, "Idempotency-Key was already used for a different chapter");
        if(inserted==0)return summary(task);
        for (int n : missing) {
            ScheduledMangaPanel panel = new ScheduledMangaPanel(); panel.setTask(task); panel.setImageNumber(n);
            panel.setPromptSnapshot(scenes.get(n-1)); panel.setState(ScheduledMangaPanel.State.PENDING); panels.save(panel);
        }
        if(automationLegacyBridge!=null)automationLegacyBridge.register(task);
        return summary(task);
    }

    @Transactional
    public Map<String,Object> createBatch(Long userId,Long storyId,Long contextChapterId,List<Integer> chapterNumbers,
                                          LocalDateTime localTime,String timezone,String model,Long configId,String idempotencyKey){
        if(idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>128)throw new BusinessException(400,"A valid Idempotency-Key is required");
        if(chapterNumbers==null||chapterNumbers.isEmpty()){
            if(contextChapterId==null)throw new BusinessException(400,"Select at least one chapter");
            return Map.of("tasks",List.of(create(userId,contextChapterId,localTime,timezone,model,configId,childKey(idempotencyKey,contextChapterId))),"chapter_count",1);
        }
        if(chapterNumbers.size()>3||chapterNumbers.stream().anyMatch(n->n==null||n<1)||new HashSet<>(chapterNumbers).size()!=chapterNumbers.size())
            throw new BusinessException(400,"Select one to three distinct chapter numbers per request");
        if(chapterNumbers.stream().anyMatch(n->n>10000))throw new BusinessException(400,"Chapter number is outside the supported range");
        List<Chapter> storyChapters=chapters.findByStoryIdAndUserIdOrderByChapterNumberAsc(storyId,userId);
        if(contextChapterId!=null&&storyChapters.stream().noneMatch(c->c.getId().equals(contextChapterId)))
            throw new BusinessException(404,"Current chapter is not in the selected story");
        Map<Integer,Chapter> byNumber=new HashMap<>();storyChapters.forEach(c->byNumber.put(c.getChapterNumber(),c));
        List<Chapter> selected=chapterNumbers.stream().sorted().map(byNumber::get).toList();
        if(selected.stream().anyMatch(Objects::isNull))throw new BusinessException(404,"One or more chapters were not found in this story");
        List<Map<String,Object>> results=new ArrayList<>();
        for(Chapter chapter:selected)results.add(create(userId,chapter.getId(),localTime,timezone,model,configId,childKey(idempotencyKey,chapter.getId())));
        return Map.of("tasks",List.copyOf(results),"task_ids",results.stream().map(item->item.get("task_id")).toList(),"chapter_count",results.size());
    }

    @Transactional(readOnly = true)
    public Map<String,Object> get(Long userId, Long chapterId, UUID id) {
        ScheduledMangaGeneration task = tasks.findByIdAndUserId(id,userId).filter(t->t.getChapter().getId().equals(chapterId)).orElseThrow(()->new BusinessException(404,"Scheduled task not found"));
        Map<String,Object> result = new LinkedHashMap<>(summary(task));
        result.put("panels", panels.findByTaskIdOrderByImageNumberAsc(id).stream().map(p -> Map.of(
                "image_number",p.getImageNumber(),"state",p.getState().name(),"attempts",p.getAttemptCount(),
                "image_id",p.getResultImage()==null?0L:p.getResultImage().getId(),"error",p.getErrorMessage()==null?"":p.getErrorMessage())).toList());
        return result;
    }

    public SseEmitter streamProgress(Long userId, Long chapterId, UUID id) {
        SseEmitter emitter=new SseEmitter(0L);
        java.util.concurrent.atomic.AtomicBoolean connected=new java.util.concurrent.atomic.AtomicBoolean(true);
        emitter.onCompletion(()->connected.set(false));
        emitter.onTimeout(()->connected.set(false));
        emitter.onError(error->connected.set(false));
        executor.submit(()->{
            try {
                while(connected.get()) {
                    Map<String,Object> progress=transactionalSelf.get(userId,chapterId,id);
                    emitter.send(SseEmitter.event().name("progress").data(progress));
                    String state=Objects.toString(progress.get("state"),"");
                    if(!state.equals(ScheduledMangaGeneration.State.SCHEDULED.name())
                            &&!state.equals(ScheduledMangaGeneration.State.RUNNING.name())) {
                        emitter.complete();
                        return;
                    }
                    long delay=Math.max(250,progressPollIntervalMs);
                    if(state.equals(ScheduledMangaGeneration.State.SCHEDULED.name())) {
                        try {
                            long until=java.time.Duration.between(java.time.Instant.now(),java.time.Instant.parse(
                                    Objects.toString(progress.get("scheduled_at")))).toMillis();
                            if(until>0)delay=Math.min(Math.max(delay,1_000),Math.min(60_000,until));
                        } catch(Exception ignored) { }
                    }
                    Thread.sleep(delay);
                }
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch(Exception error) {
                connected.set(false);
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    @Transactional
    public Map<String,Object> cancel(Long userId, Long chapterId, UUID id) {
        return cancelInternal(userId,chapterId,id,true);
    }

    @Transactional
    public Map<String,Object> cancelAutomationChild(Long userId,Long chapterId,UUID id) { return cancelInternal(userId,chapterId,id,false); }

    private Map<String,Object> cancelInternal(Long userId,Long chapterId,UUID id,boolean syncPlan) {
        ScheduledMangaGeneration task=tasks.findByIdAndUserId(id,userId).filter(t->t.getChapter().getId().equals(chapterId)).orElseThrow(()->new BusinessException(404,"Scheduled task not found"));
        if (task.getState()==ScheduledMangaGeneration.State.CANCELLED) return summary(task);
        if (tasks.cancelIfScheduled(id,OffsetDateTime.now())!=1) throw new BusinessException(409,"Only a task that has not started can be cancelled");
        task.setState(ScheduledMangaGeneration.State.CANCELLED);
        task.setCompletedAt(OffsetDateTime.now());
        task.setLeaseOwner(null);
        task.setLeaseUntil(null);
        panels.findByTaskIdOrderByImageNumberAsc(id).stream().filter(p->p.getState()!=ScheduledMangaPanel.State.SUCCEEDED&&p.getState()!=ScheduledMangaPanel.State.UNCERTAIN)
                .forEach(p->{p.setState(ScheduledMangaPanel.State.CANCELLED);panels.save(p);});
        if(syncPlan&&automationLegacyBridge!=null)automationLegacyBridge.cancelFromLegacy(task);
        return summary(task);
    }

    @Transactional
    public Map<String,Object> retryFailed(Long userId, Long chapterId, UUID id) {
        return retryInternal(userId,chapterId,id,true);
    }
    @Transactional
    public Map<String,Object> retryAutomationChild(Long userId,Long chapterId,UUID id) { return retryInternal(userId,chapterId,id,false); }

    private Map<String,Object> retryInternal(Long userId,Long chapterId,UUID id,boolean syncPlan) {
        ScheduledMangaGeneration task=tasks.findByIdAndUserId(id,userId)
                .filter(t->t.getChapter().getId().equals(chapterId))
                .orElseThrow(()->new BusinessException(404,"Scheduled task not found"));
        if(task.getState()!=ScheduledMangaGeneration.State.FAILED
                &&task.getState()!=ScheduledMangaGeneration.State.PARTIAL_FAILED)
            throw new BusinessException(409,"Only a failed task can be retried");
        if(panels.countByTaskIdAndState(id,ScheduledMangaPanel.State.FAILED)==0)
            throw new BusinessException(409,"This task has no safely retryable failed panels");
        User user=users.findByIdForScheduledTaskCreation(userId)
                .orElseThrow(()->new BusinessException(404,"User not found"));
        if(tasks.countActiveForUser(userId)>=Math.max(1,maxActiveTasksPerUser))
            throw new BusinessException(429,"You already have the maximum number of active scheduled image tasks");
        if(tasks.existsByChapterIdAndActive(chapterId))
            throw new BusinessException(409,"This chapter already has an active scheduled generation");
        OffsetDateTime now=OffsetDateTime.now();
        if(tasks.retryFailedIfFinal(id,userId,now)!=1)
            throw new BusinessException(409,"This task can no longer be retried");
        if(panels.resetFailedForRetry(id)==0)
            throw new BusinessException(409,"This task has no safely retryable failed panels");
        task.setState(ScheduledMangaGeneration.State.SCHEDULED);
        task.setRequestedAt(now);
        task.setCompletedAt(null);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setLeaseOwner(null);
        task.setLeaseUntil(null);
        if(syncPlan&&automationLegacyBridge!=null)automationLegacyBridge.retryFromLegacy(task);
        return summary(task);
    }

    @Scheduled(fixedDelayString="${artverse.scheduled-manga.poll-interval-ms:5000}", initialDelay=5000)
    public void poll() {
        List<UUID> due=tasks.findDueIds(OffsetDateTime.now(),8);
        for (UUID id:due) if (claim(id)) executor.submit(()->run(id));
    }

    @Transactional
    protected boolean claim(UUID id) {
        int changed=tasks.claim(id,instanceId,OffsetDateTime.now().plusMinutes(15),OffsetDateTime.now());
        if (changed==1) {
            // A process may have died after the provider accepted the request. Never auto-replay that uncertain panel.
            panels.findByTaskIdOrderByImageNumberAsc(id).stream().filter(p->p.getState()==ScheduledMangaPanel.State.RUNNING).forEach(p->{p.setState(ScheduledMangaPanel.State.UNCERTAIN);p.setErrorCode("RESULT_UNCERTAIN");p.setErrorMessage("Worker stopped while the provider request was in flight; manual review is required.");panels.save(p);});
        }
        return changed==1;
    }

    public void run(UUID id) {
        ScheduledMangaGeneration task=null;
        try {
            task=tasks.findById(id).orElse(null); if(task==null)return;
            final long taskFence=task.getFencingToken();
            Chapter chapter=chapters.findByIdForIdempotencyAndUserId(task.getChapter().getId(),task.getUser().getId()).orElse(null);
            if(chapter==null){finish(id,ScheduledMangaGeneration.State.FAILED,"CHAPTER_UNAVAILABLE","Chapter unavailable",task.getFencingToken());return;}
            if(!Objects.equals(chapter.getVersion(),task.getChapterVersion())){finish(id,ScheduledMangaGeneration.State.STALE_REQUIRES_CONFIRMATION,"CHAPTER_CHANGED","Chapter changed after this task was scheduled",task.getFencingToken());return;}
            if(generation.isGenerationRunning(chapter.getId())){defer(id,task.getFencingToken());return;}
            User user=users.findById(task.getUser().getId()).orElseThrow();
            UserProviderConfig config=apiKeys.requireProviderConfigForModel(user,ApiKeyService.SLOT_IMAGE,task.getModelConfigId(),task.getModelName(),"Scheduled image provider configuration is unavailable");
            List<String> scenes=mapper.readValue(task.getScenesSnapshot(),new TypeReference<>(){});
            ScheduledMangaInputSnapshot snapshot=mapper.readValue(task.getInputSnapshot(),ScheduledMangaInputSnapshot.class);
            ScheduledMangaInputSnapshot current=makeSnapshot(chapter,scenes,snapshot.missingImageNumbers(),config);
            if(!Objects.equals(hashSnapshot(current),task.getSnapshotHash())){finish(id,ScheduledMangaGeneration.State.STALE_REQUIRES_CONFIRMATION,"INPUTS_CHANGED","Story, character, style, reference-image, or generation inputs changed after scheduling",task.getFencingToken());return;}
            for(ScheduledMangaPanel panel:panels.findByTaskIdOrderByImageNumberAsc(id)) {
                if(task.getAutomationRunId()!=null&&automationJdbc!=null&&Boolean.TRUE.equals(automationJdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM automation_run WHERE id=? AND state<>'RUNNING')",Boolean.class,task.getAutomationRunId()))) {
                    for(var remaining:panels.findByTaskIdOrderByImageNumberAsc(id))if(Set.of(ScheduledMangaPanel.State.PENDING,ScheduledMangaPanel.State.RETRY_WAIT).contains(remaining.getState()))panelState(remaining,ScheduledMangaPanel.State.CANCELLED,"CANCELLED","已停止后续生图");
                    finish(id,ScheduledMangaGeneration.State.CANCELLED,"CANCELLED","已有图片保留，后续请求已停止",taskFence);return;
                }
                if(panel.getState()==ScheduledMangaPanel.State.SUCCEEDED)continue;
                if(images.findByChapterIdAndImageNumber(chapter.getId(),panel.getImageNumber()).isPresent()){panelState(panel,ScheduledMangaPanel.State.SUCCEEDED,null,null);continue;}
                if(panel.getState()==ScheduledMangaPanel.State.FAILED||panel.getState()==ScheduledMangaPanel.State.UNCERTAIN)continue;
                if(panel.getNextAttemptAt()!=null&&panel.getNextAttemptAt().isAfter(OffsetDateTime.now()))continue;
                String slotOwner=ScheduledMangaWorkerSlotService.newOwner(id);
                Optional<Integer> globalSlot=workerSlots.acquire(slotOwner);
                if(globalSlot.isEmpty()){panelState(panel,ScheduledMangaPanel.State.RETRY_WAIT,"CAPACITY","Worker capacity temporarily unavailable");continue;}
                String userPermit="user:"+user.getId();
                String providerPermit="provider-model:"+Objects.toString(config.configId(),"default")+":"+hashText(config.model());
                boolean userPermitAcquired=false,providerPermitAcquired=false;
                try {
                    userPermitAcquired=workerSlots.acquireScoped(userPermit,slotOwner);
                    if(!userPermitAcquired){panelState(panel,ScheduledMangaPanel.State.RETRY_WAIT,"USER_CAPACITY","Another scheduled image is already running for this user");continue;}
                    providerPermitAcquired=workerSlots.acquireScoped(providerPermit,slotOwner);
                    if(!providerPermitAcquired){panelState(panel,ScheduledMangaPanel.State.RETRY_WAIT,"MODEL_CAPACITY","The selected provider/model is at its scheduled generation limit");continue;}
                    if(tasks.renew(id,instanceId,taskFence,OffsetDateTime.now().plusMinutes(15),OffsetDateTime.now())!=1)throw new LeaseLostException();
                    if(panels.startIfCurrent(panel.getId(),id,instanceId,instanceId,taskFence,OffsetDateTime.now(),OffsetDateTime.now().plusMinutes(15))!=1)continue;
                    panel.setState(ScheduledMangaPanel.State.RUNNING);
                    panel.setAttemptCount(panel.getAttemptCount()+1);
                    panel.setFencingToken(taskFence);
                    MangaImage image=generation.generateScheduledPanel(chapter.getId(),user.getId(),panel.getImageNumber(),panel.getPromptSnapshot(),snapshot.scenes(),snapshot.characterProfiles(),snapshot.mangaStyle(),snapshot.colorMode(),snapshot.referenceImageKeys(),snapshot.imageSize(),config,()->tasks.isCurrentLease(id,instanceId,taskFence,OffsetDateTime.now()));
                    panels.completeIfOwned(panel.getId(),instanceId,taskFence,image.getId());
                } catch(Exception e) {
                    if(!tasks.isCurrentLease(id,instanceId,taskFence,OffsetDateTime.now())) break;
                    int attempts=panel.getAttemptCount();
                    ScheduledMangaRetryPolicy.Decision decision=ScheduledMangaRetryPolicy.classify(e,attempts,3);
                    OffsetDateTime nextAttempt=decision.state()==ScheduledMangaPanel.State.RETRY_WAIT?retryAt(e,attempts):null;
                    panels.updateOwnedState(panel.getId(),instanceId,taskFence,decision.state().name(),decision.errorCode(),safeMessage(e),nextAttempt);
                } finally {
                    if(providerPermitAcquired)workerSlots.releaseScoped(providerPermit,slotOwner);
                    if(userPermitAcquired)workerSlots.releaseScoped(userPermit,slotOwner);
                    globalSlot.ifPresent(slot->workerSlots.release(slot,slotOwner));
                }
            }
            aggregate(id,taskFence);
        } catch(Exception e){if(task!=null)finish(id,ScheduledMangaGeneration.State.FAILED,"WORKER_ERROR",safeMessage(e),task.getFencingToken());}
    }

    public void panelState(ScheduledMangaPanel panel,ScheduledMangaPanel.State state,String code,String message){panel.setState(state);panel.setErrorCode(code);panel.setErrorMessage(message);panel.setLeaseOwner(null);panel.setLeaseUntil(null);panels.save(panel);}
    public void aggregate(UUID id,long fence){List<ScheduledMangaPanel> ps=panels.findByTaskIdOrderByImageNumberAsc(id);boolean outstanding=ps.stream().anyMatch(p->p.getState()==ScheduledMangaPanel.State.PENDING||p.getState()==ScheduledMangaPanel.State.RUNNING||p.getState()==ScheduledMangaPanel.State.RETRY_WAIT);OffsetDateTime now=OffsetDateTime.now();if(outstanding){tasks.updateOwnedState(id,instanceId,fence,ScheduledMangaGeneration.State.SCHEDULED.name(),null,null,null,null);return;}long success=ps.stream().filter(p->p.getState()==ScheduledMangaPanel.State.SUCCEEDED).count();long uncertain=ps.stream().filter(p->p.getState()==ScheduledMangaPanel.State.UNCERTAIN).count();ScheduledMangaGeneration.State finalState=success==ps.size()?ScheduledMangaGeneration.State.SUCCEEDED:success>0||uncertain>0?ScheduledMangaGeneration.State.PARTIAL_FAILED:ScheduledMangaGeneration.State.FAILED;tasks.updateOwnedState(id,instanceId,fence,finalState.name(),null,null,now,null);}
    public void finish(UUID id,ScheduledMangaGeneration.State state,String code,String message,long fence){tasks.updateOwnedState(id,instanceId,fence,state.name(),code,message,OffsetDateTime.now(),null);}
    public void defer(UUID id,long fence){tasks.updateOwnedState(id,instanceId,fence,ScheduledMangaGeneration.State.SCHEDULED.name(),null,null,null,OffsetDateTime.now().plusSeconds(30));}
    private static final class LeaseLostException extends RuntimeException {}

    private ScheduledMangaInputSnapshot makeSnapshot(Chapter chapter,List<String> scenes,List<Integer> missing,UserProviderConfig config){
        Long storyId=chapter.getStory().getId(); Long groupId=chapter.getAssetGroup()==null?null:chapter.getAssetGroup().getId();
        String style=chapter.getStory().getMangaStyle()==null||chapter.getStory().getMangaStyle().isBlank()?"japanese_manga":chapter.getStory().getMangaStyle();
        String color=chapter.getColorMode().name().toLowerCase();
        String profiles=generation.scheduledGenerationProfiles(chapter.getId(),chapter.getStory().getUser().getId());
        List<String> refs=imageStorage.previewReferenceImageKeys(storyId,chapter.getId(),chapter.getRefImage(),groupId,chapter.getStory().getRefImage());
        return new ScheduledMangaInputSnapshot(1,storyId,chapter.getId(),chapter.getVersion(),style,color,profiles,groupId,chapter.getRefImage(),chapter.getStory().getRefImage(),refs,scenes,missing,properties.getImage().getSize(),config.model(),config.configId(),"manga-image-prompt-v1");
    }
    private String hashSnapshot(ScheduledMangaInputSnapshot snapshot){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(snapshot).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Unable to hash generation snapshot",e);}}
    private String hashText(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.toString(value,"").getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Unable to hash provider model",e);}}
    private String childKey(String requestKey,Long chapterId){return hashText(Objects.toString(requestKey,"")+":chapter:"+chapterId);}
    @PreDestroy public void shutdown(){executor.close();}
    private OffsetDateTime retryAt(Exception error,int attempts){
        String header=error instanceof org.springframework.web.reactive.function.client.WebClientResponseException response?response.getHeaders().getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER):null;
        if(header!=null){try{return OffsetDateTime.now().plusSeconds(Math.max(0,Long.parseLong(header)));}catch(NumberFormatException ignored){try{return java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();}catch(Exception ignoredDate){}}}
        return OffsetDateTime.now().plusSeconds((long)Math.pow(2,attempts)*30+ThreadLocalRandom.current().nextInt(1,20));
    }
    private boolean hasCause(Throwable error,Class<? extends Throwable> type){for(Throwable current=error;current!=null;current=current.getCause())if(type.isInstance(current))return true;return false;}

    private Map<String,Object> summary(ScheduledMangaGeneration task){return Map.of("task_id",task.getId().toString(),"state",task.getState().name(),"scheduled_at",task.getRequestedAt().toInstant().toString(),"local_time",task.getRequestedAt().atZoneSameInstant(ZoneId.of(task.getTimezoneId())).toString(),"timezone",task.getTimezoneId(),"chapter_id",task.getChapter().getId(),"panel_count",panels.findByTaskIdOrderByImageNumberAsc(task.getId()).size(),"error",task.getErrorMessage()==null?"":task.getErrorMessage());}
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String safeMessage(Exception e){
        if(e instanceof org.springframework.web.reactive.function.client.WebClientResponseException response)return "Image provider returned HTTP "+response.getStatusCode().value();
        if(hasCause(e,java.util.concurrent.TimeoutException.class))return "Image provider timed out; the external request outcome may be uncertain";
        if(hasCause(e,org.springframework.web.reactive.function.client.WebClientRequestException.class))return "Image provider connection failed; the external request outcome may be uncertain";
        if(e instanceof BusinessException business)return business.getMessage();
        return "Scheduled image generation failed ("+e.getClass().getSimpleName()+")";
    }
}
