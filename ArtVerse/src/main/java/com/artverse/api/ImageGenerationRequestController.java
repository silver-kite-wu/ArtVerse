package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.imagequeue.*;
import com.artverse.domain.Role;
import com.artverse.persistence.ImageGenRecordRepository;
import com.artverse.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.data.domain.PageRequest;
import java.util.*;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ImageGenerationRequestController {
    private final CurrentUserService currentUser;
    private final ImageRequestSubmissionService submissions;
    private final ImageRequestQueries queries;
    private final ImageRequestEvents events;
    private final ImageRecoveryService recovery;
    private final ImageGenRecordRepository records;
    private long user() { return currentUser.requireCurrentUser().getId(); }
    public ResponseEntity<Map<String,Object>> submit(long user,Long chapter,Integer number,String operation,Map<String,Object> body,String key) {
        var accepted=submissions.submit(user,chapter,number,operation,body,key);var snapshot=queries.snapshot(accepted.manifest());
        snapshot.put("joined_existing",accepted.joined());
        return ResponseEntity.status(ImageProtocol.terminal((String)snapshot.get("status"))?200:202).body(snapshot);
    }
    @PostMapping("/chapters/{chapterId}/manga-images/{number}/generation-requests") public ResponseEntity<Map<String,Object>> single(@PathVariable long chapterId,@PathVariable int number,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false) String key) { return submit(user(),chapterId,number,"MANGA_SINGLE_GENERATE",body,key); }
    @GetMapping("/image-generation/requests/{id}") public Map<String,Object> get(@PathVariable UUID id) { return queries.get(user(),id); }
    @GetMapping(value="/image-generation/requests/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(@PathVariable UUID id) { return events.open(user(),id); }
    @PostMapping("/image-generation/requests/{id}/operations/{operation}/recovery-actions") public ResponseEntity<Map<String,Object>> action(@PathVariable UUID id,@PathVariable UUID operation,@RequestBody Map<String,Object> body) {
        var result=recovery.action(user(),id,operation,body,false);String action=Objects.toString(body.get("action"),"");
        return ResponseEntity.status(Set.of("RECHECK","RESUME_SAFE").contains(action)?202:200).body(result);
    }
    @PostMapping("/image-generation/requests/{id}/operations/{operation}/abandon-and-regenerate") public ResponseEntity<Map<String,Object>> replace(@PathVariable UUID id,@PathVariable UUID operation,@RequestBody Map<String,Object> body) { return ResponseEntity.accepted().body(recovery.replace(user(),id,operation,body)); }
    private void admin() { if(currentUser.requireCurrentUser().getRole()!=Role.ADMIN)throw new BusinessException(403,"Forbidden"); }
    @GetMapping("/admin/image-generation/reviews") public Map<String,Object> reviews(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        admin();var found=records.findByAttemptDecision("UNRESOLVED",PageRequest.of(Math.max(0,page),Math.min(100,Math.max(1,size))));
        List<Map<String,Object>> items=found.getContent().stream().map(r->{ Map<String,Object> item=new LinkedHashMap<>();item.put("user_id",r.getUser().getId());item.put("request_id",r.getRootRequestId());item.put("operation_id",r.getRequestId());item.put("provider_attempt",r.getProviderAttempt());item.put("execution_token",Long.toString(r.getExecutionToken()));item.put("recovery_version",r.getRecoveryVersion());item.put("image_number",r.getImageNumber());item.put("execution_lane",r.getExecutionLane());item.put("review_requested_at",r.getReviewRequestedAt());item.put("created_at",r.getProviderStartedAt());return item; }).toList();
        return Map.of("items",items,"total_elements",found.getTotalElements(),"total_pages",found.getTotalPages());
    }
    @PostMapping("/admin/image-generation/requests/{id}/operations/{operation}/review-decisions") public Map<String,Object> decide(@PathVariable UUID id,@PathVariable UUID operation,@RequestBody Map<String,Object> body) {
        admin();long owner=records.findByRootRequestIdAndRequestId(id,operation).orElseThrow(()->new BusinessException(404,"Operation not found")).getUser().getId();
        Map<String,Object> input=new TreeMap<>(body);input.put("action","ADMIN_DECISION");input.put("reviewer_id",currentUser.requireCurrentUser().getId());return recovery.action(owner,id,operation,input,true);
    }
    @GetMapping("/admin/image-generation/requests/{id}/operations/{operation}") public Map<String,Object> detail(@PathVariable UUID id,@PathVariable UUID operation) {
        admin();long owner=records.findByRootRequestIdAndRequestId(id,operation).orElseThrow(()->new BusinessException(404,"Operation not found")).getUser().getId();
        return recovery.reviewDetail(owner,id,operation);
    }
}
