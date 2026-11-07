package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.automation.*;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AutomationController {
    private final AutomationService service;
    private final CurrentUserService currentUser;
    @Qualifier("mangaGenerationExecutor") private final ExecutorService executor;
    private long userId() { return currentUser.requireCurrentUser().getId(); }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record VersionRequest(Long expectedVersion) {}
    public record PreviewRequest(AutomationDefinition.Schedule schedule) {}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record CommandDecision(String decision, String commandHash, Long expectedVersion) {}

    @PostMapping("/automations/schedule-preview") public Map<String,Object> preview(@RequestBody PreviewRequest input) { return service.preview(input.schedule()); }
    @GetMapping("/automation-command-proposals") public AutomationService.ModelCommandPage commands(
            @RequestParam(required=false) Long chapterId, @RequestParam(required=false) UUID conversationId,
            @RequestParam(required=false) UUID before, @RequestParam(defaultValue="20") int size) {
        return service.modelCommands(userId(),chapterId,conversationId,before,size);
    }
    @PostMapping("/automation-command-proposals/{id}/decision") public AutomationService.ModelCommandView decideCommand(
            @PathVariable UUID id,@RequestBody CommandDecision decision) {
        return service.decideModelCommand(userId(),id,decision.decision(),decision.commandHash(),decision.expectedVersion());
    }
    @PostMapping("/automations") public AutomationStore.Plan create(@RequestHeader("Idempotency-Key") String key,@RequestBody AutomationDefinition input) { return service.create(userId(),key,input); }
    @GetMapping("/automations") public Map<String,Object> list(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String taskType,
            @RequestParam(defaultValue="false") boolean needsAttention,@RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {
        var values=service.list(userId(),status,taskType,needsAttention,search,page,size);
        return Map.of("items",values,"page",Math.max(0,page),"hasMore",values.size()==Math.min(100,Math.max(1,size)));
    }
    @GetMapping("/automations/{id}") public AutomationStore.Plan get(@PathVariable UUID id) { return service.get(userId(),id); }
    @PatchMapping("/automations/{id}") public AutomationStore.Plan edit(@PathVariable UUID id,@RequestBody AutomationDefinition definition) { return service.update(userId(),id,definition); }
    @PostMapping("/automations/{id}/{action:pause|resume|archive}") public AutomationStore.Plan lifecycle(@PathVariable UUID id,@PathVariable String action,@RequestBody VersionRequest input) { return service.lifecycle(userId(),id,action,input.expectedVersion()); }
    @DeleteMapping("/automations/{id}") public Map<String,Object> delete(@PathVariable UUID id,@RequestParam Long expectedVersion) { service.delete(userId(),id,expectedVersion);return Map.of("deleted",true); }
    @PostMapping("/automations/{id}/run-now") public AutomationStore.Run runNow(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key) { return service.runNow(userId(),id,key); }
    @GetMapping("/automations/{id}/runs") public Map<String,Object> runs(@PathVariable UUID id,@RequestParam(required=false) UUID before,@RequestParam(defaultValue="20") int size) { return Map.of("items",service.runs(userId(),id,before,size)); }
    @GetMapping("/automation-runs/{id}") public Map<String,Object> run(@PathVariable UUID id) { return service.runDetail(userId(),id); }
    @PostMapping("/automation-runs/{id}/cancel") public AutomationStore.Run cancel(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key) { return service.cancel(userId(),id,key); }
    @PostMapping("/automation-runs/{id}/retry") public AutomationStore.Run retry(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key) { return service.retry(userId(),id,key); }
    @PostMapping("/automation-runs/{id}/respond") public AutomationStore.Run respond(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode response) { return service.respond(userId(),id,key,response); }
    @GetMapping("/automation-notifications") public Map<String,Object> notifications() { return Map.of("items",service.notifications(userId())); }
    @PostMapping("/automation-notifications/{id}/read") public Map<String,Boolean> read(@PathVariable long id) { service.markRead(userId(),id);return Map.of("read",true); }
    @PostMapping("/automation-notifications/read-all") public Map<String,Integer> readAll() { return Map.of("updated",service.markAllRead(userId())); }
    @DeleteMapping("/automation-notifications/{id}") public Map<String,Boolean> deleteNotification(@PathVariable long id) { service.deleteNotification(userId(),id);return Map.of("deleted",true); }
    @DeleteMapping("/automation-notifications") public Map<String,Object> clearNotifications() { return Map.of("deleted",service.clearNotifications(userId())); }

    @GetMapping(value="/automation-runs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id,@RequestHeader(value="Last-Event-ID",required=false) String lastEventId,
                             @RequestParam(defaultValue="0") long after) {
        long user=userId();service.runDetail(user,id);
        long cursor=after;
        try { if(lastEventId!=null)cursor=Math.max(cursor,Long.parseLong(lastEventId)); }
        catch(NumberFormatException e) { throw new BusinessException(400,"Invalid Last-Event-ID"); }
        SseEmitter emitter=new SseEmitter(120_000L);
        AtomicBoolean open=new AtomicBoolean(true);
        emitter.onCompletion(()->open.set(false));emitter.onTimeout(()->open.set(false));emitter.onError(e->open.set(false));
        long initialCursor=Math.max(0,cursor);
        executor.submit(()-> {
            long position=initialCursor;
            try {
                emitter.send(SseEmitter.event().name("snapshot").data(service.runDetail(user,id)));
                while(open.get()) {
                    var values=service.events(user,id,position);
                    for(var event:values) { position=((Number)event.get("id")).longValue();emitter.send(SseEmitter.event().id(Long.toString(position)).name("event").data(event)); }
                    var detail=service.runDetail(user,id);
                    var run=(AutomationStore.Run)detail.get("run");
                    if(AutomationService.terminal(run.state())&&values.size()<200) { emitter.send(SseEmitter.event().name("snapshot").data(detail));emitter.complete();return; }
                    if(values.isEmpty()) { emitter.send(SseEmitter.event().comment("keepalive"));Thread.sleep(1000); }
                }
            } catch(InterruptedException e) { Thread.currentThread().interrupt();emitter.complete(); }
            catch(Exception e) { emitter.completeWithError(e); }
        });
        return emitter;
    }
}
