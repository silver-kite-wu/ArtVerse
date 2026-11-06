package com.artverse.application.imageproposal;

import com.artverse.agent.*;
import com.artverse.application.*;
import com.artverse.application.knowledge.KnowledgeSchema;
import com.artverse.application.imagequeue.*;
import com.artverse.common.BusinessException;
import com.artverse.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.artverse.application.CreativeProposalArtifacts.*;
import static com.artverse.application.imageproposal.ImageProposalViews.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

/** Preview/proposal are observations. Only a durable authenticated author decision dispatches work. */
@Service @RequiredArgsConstructor
public class ImageProposalService {
    private final CreativeProposalArtifacts proposals;
    private final ImageProposalTokens tokens;
    private final KnowledgeSchema json;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ImageRequestInputs inputs;
    private final MangaImageRepository images;
    private final ImageRequestQueries queries;
    private final ImageRequestSubmissionService submissions;
    private final ImageRecoveryService recovery;
    private final ImageQueueProperties queue;
    private final com.artverse.config.ArtVerseProperties properties;
    private final TransactionTemplate transactions;

    @Transactional
    public ModelPage models(MangaAgentRuntimeContext c,Capability capability,String cursor,Integer requested) {
        proposals.active(c);int limit=requested==null?20:requested;
        if(limit<1||limit>50)throw new BusinessException(400,"分页大小为 1-50");
        var configs=jdbc.queryForList("SELECT id,provider,label,model,active,api_key,base_url FROM user_api_keys WHERE user_id=? AND slot='image' ORDER BY id",c.userId());
        String snapshot=json.hash(configs);long before=0;
        if(cursor!=null&&!cursor.isBlank()) {
            var claims=tokens.verify(cursor);
            if(!"models".equals(claims.path("kind").asText())||claims.path("actor").asLong()!=c.userId()||claims.path("story").asLong()!=c.storyId()||!snapshot.equals(claims.path("snapshot").asText())||claims.path("expires").asLong()<Instant.now().getEpochSecond())throw conflict("模型目录快照已变化，请重新读取");
            before=claims.path("after").asLong(-1);if(before<0)throw conflict("目录游标无效");
        }
        final long position=before;var selected=configs.stream().filter(row->((Number)row.get("id")).longValue()>position).limit(limit+1L).toList();
        var items=selected.stream().limit(limit).map(row->new Model(((Number)row.get("id")).longValue(),configVersion(row),Objects.toString(row.get("label"),""),row.get("provider").toString(),modelNames(row.get("model")),List.of(Capability.IMAGE_GENERATION),!Objects.toString(row.get("api_key"),"").isBlank()&&!modelNames(row.get("model")).isEmpty())).toList();
        boolean more=selected.size()>limit;
        String next=more?tokens.sign(Map.of("version",1,"kind","models","actor",c.userId(),"story",c.storyId(),"snapshot",snapshot,"after",items.getLast().configId(),"expires",Instant.now().plusSeconds(600).getEpochSecond())):"";
        return new ModelPage(items,next,more);
    }

    @Transactional
    public Preview preview(MangaAgentRuntimeContext c,Selection selection) {
        proposals.active(c);
        if(selection!=null&&selection.size()==null)selection=new Selection(selection.imageNumbers(),selection.configId(),selection.configVersion(),selection.model(),selection.mode(),selection.assetGroupId(),properties.getImage().getSize());
        var snapshot=snapshot(c.userId(),c.storyId(),c.chapterId(),selection);
        Instant now=Instant.now(),expires=now.plusSeconds(300);
        var claims=new LinkedHashMap<String,Object>();claims.put("version",1);claims.put("kind","preview");
        claims.put("actor",c.userId());claims.put("story",c.storyId());claims.put("chapter",c.chapterId());claims.put("conversation",c.conversationId().toString());claims.put("request",c.requestId().toString());
        claims.put("selection",selection);claims.put("snapshot",snapshot.hash());claims.put("expires",expires.getEpochSecond());
        return new Preview(tokens.sign(claims),selection,snapshot.hash(),snapshot.targets(),"SERIAL_MODEL",selection.imageNumbers().size(),queue.isProducerEnabled(),"CHECKED_AT_SUBMISSION","UNKNOWN",now,expires,false,false);
    }

    @Transactional
    public Map<String,Object> propose(MangaAgentRuntimeContext c,String preview,AgentToolAttempt attempt) {
        proposals.active(c);var claims=tokens.verify(preview);
        if(!"preview".equals(claims.path("kind").asText())||claims.path("actor").asLong()!=c.userId()||claims.path("story").asLong()!=c.storyId()||claims.path("chapter").asLong()!=c.chapterId()
                ||!claims.path("conversation").asText().equals(c.conversationId().toString())||!claims.path("request").asText().equals(c.requestId().toString())||claims.path("expires").asLong()<Instant.now().getEpochSecond())throw conflict("预览范围不匹配或已过期");
        Selection selection=mapper.convertValue(claims.path("selection"),Selection.class);
        var snapshot=snapshot(c.userId(),c.storyId(),c.chapterId(),selection);
        if(!snapshot.hash().equals(claims.path("snapshot").asText()))throw conflict("配置、分镜、素材或图片已变化，请重新预览");
        var body=new LinkedHashMap<String,Object>();body.put("selection",selection);body.put("targets",snapshot.targets());
        body.put("snapshot_hash",snapshot.hash());body.put("provider_hash",snapshot.providerHash());body.put("reference_hash",snapshot.referenceHash());
        body.put("model_calls",selection.imageNumbers().size());body.put("execution_lane","SERIAL_MODEL");body.put("estimated_cost","UNKNOWN");body.put("quota_availability","CHECKED_AT_SUBMISSION");
        return proposals.create(c,"IMAGE_PROPOSAL",body,attempt);
    }

    @Transactional
    public Request request(MangaAgentRuntimeContext c,UUID id,UUID operation) {
        proposals.active(c);return requestFor(c.userId(),c.chapterId(),id,operation);
    }
    private Request requestFor(long actor,long chapter,UUID id,UUID operation) {
        Manifest m=queries.manifest(actor,id);
        if(m.userId()!=actor||!Objects.equals(m.chapterId(),chapter))throw denied();
        var selected=m.panels().stream().filter(p->operation==null||operation.equals(p.operationId())).toList();
        if(operation!=null&&selected.isEmpty())throw new BusinessException(404,"请求没有该图片操作");
        var results=selected.stream().map(p->{
            var r=queries.readOnlyPanel(m,p);
            var actions=((List<?>)r.getOrDefault("recovery_actions",List.of())).stream().map(Object::toString)
                    .filter(s->Arrays.stream(RecoveryAction.values()).anyMatch(a->a.name().equals(s))).map(RecoveryAction::valueOf).toList();
            return new Result(m.rootId(),p.operationId(),p.imageNumber(),Objects.toString(r.get("status"),"UNKNOWN"),Objects.toString(r.get("status_source"),"unavailable"),nullableText(r.get("manga_image_id")),nullableText(r.get("image_version")),((Number)r.getOrDefault("recovery_version",0)).longValue(),actions,"NEEDS_REVIEW".equals(r.get("status"))||r.get("status")==null,Boolean.TRUE.equals(r.get("result_deleted")));
        }).toList();
        return new Request(m.rootId(),chapter,queries.readOnlySubmission(m),results,m.panels().size(),m.panels().size()==results.size()&&results.stream().allMatch(r->terminal(r.status())));
    }

    @Transactional
    public Map<String,Object> proposeRecovery(MangaAgentRuntimeContext c,UUID id,UUID operation,RecoveryAction action,long expected,AgentToolAttempt attempt) {
        proposals.active(c);var r=requestFor(c.userId(),c.chapterId(),id,operation).images().getFirst();
        if(r.recoveryVersion()!=expected||!r.recoveryActions().contains(action))throw conflict("恢复状态或允许动作已变化，请刷新");
        return proposals.create(c,"IMAGE_RECOVERY",Map.of("request_id",id.toString(),"operation_id",operation.toString(),"recovery_action",action.name(),"recovery_version",Long.toString(expected)),attempt);
    }

    /** Approval is committed before broker/storage work. Retries use immutable bodies and stable identities. */
    public Map<String,Object> decide(long actor,UUID id,String decision,String hash) {
        var observed=proposals.owned(actor,id,false);proposals.verifyDecision(observed,decision,hash);
        if(!Set.of("IMAGE_PROPOSAL","IMAGE_RECOVERY").contains(observed.get("artifact_type")))throw denied();
        if(!"DRAFT".equals(observed.get("status")))return proposals.view(observed);
        Map<String,Object> approval=null;
        if("confirm".equals(decision)&&!Boolean.TRUE.equals(proposals.receipt(observed).get("approved")))approval=prepareDispatch(actor,id,observed);
        final Map<String,Object> prepared=approval;
        var row=transactions.execute(tx->{
            var locked=proposals.owned(actor,id,true);proposals.verifyDecision(locked,decision,hash);
            if(!"DRAFT".equals(locked.get("status")))return locked;
            if("discard".equals(decision)){proposals.finish(locked,"REJECTED",Map.of());return proposals.owned(actor,id,false);}
            if(!Boolean.TRUE.equals(proposals.receipt(locked).get("approved"))) {
                if(prepared==null)throw conflict("缺少审核输入");
                jdbc.update("UPDATE manga_agent_run_artifacts SET evaluation=?::jsonb,updated_at=clock_timestamp() WHERE artifact_uuid=?",json.write(prepared),id);
            }
            return proposals.owned(actor,id,false);
        });
        if(!"DRAFT".equals(row.get("status")))return proposals.view(row);
        var approved=proposals.receipt(row);
        @SuppressWarnings("unchecked") var commands=(List<Map<String,Object>>)approved.get("commands");
        for(var command:commands) {
            String actionId=command.get("action_id").toString();
            @SuppressWarnings("unchecked") var body=(Map<String,Object>)command.get("body");
            String accepted;
            if("IMAGE_RECOVERY".equals(row.get("artifact_type"))) {
                recovery.action(actor,UUID.fromString(command.get("request_id").toString()),UUID.fromString(command.get("operation_id").toString()),body,false);
                accepted=command.get("request_id").toString();
            } else {
                Integer number=command.get("image_number")==null?null:((Number)command.get("image_number")).intValue();
                accepted=submissions.submit(actor,((Number)row.get("chapter_id")).longValue(),number,command.get("operation").toString(),body,actionId).manifest().rootId().toString();
            }
            final String acceptedRequest=accepted;
            transactions.executeWithoutResult(tx->{
                var locked=proposals.owned(actor,id,true);if(!"DRAFT".equals(locked.get("status")))return;
                var receipt=new LinkedHashMap<>(proposals.receipt(locked));
                @SuppressWarnings("unchecked") var existing=(Map<String,String>)receipt.getOrDefault("accepted_by_action",Map.of());
                var completed=new LinkedHashMap<>(existing);completed.put(actionId,acceptedRequest);receipt.put("accepted_by_action",completed);
                receipt.put("accepted_requests",new ArrayList<>(new LinkedHashSet<>(completed.values())));
                if(completed.size()==commands.size()) {
                    // Do not expose internal dispatch bodies in the public terminal receipt.
                    receipt.remove("commands");receipt.put("accepted",true);receipt.put("generation_completed",false);proposals.finish(locked,"COMMITTED",receipt);
                } else jdbc.update("UPDATE manga_agent_run_artifacts SET evaluation=?::jsonb,updated_at=clock_timestamp() WHERE artifact_uuid=?",json.write(receipt),id);
            });
        }
        return proposals.view(proposals.owned(actor,id,false));
    }

    private Map<String,Object> prepareDispatch(long actor,UUID id,Map<String,Object> row) {
        var p=proposals.payload(row);long chapter=((Number)row.get("chapter_id")).longValue();var commands=new ArrayList<Map<String,Object>>();
        if("IMAGE_RECOVERY".equals(row.get("artifact_type"))) {
            var request=UUID.fromString(p.get("request_id").toString());var op=UUID.fromString(p.get("operation_id").toString());
            var r=requestFor(actor,chapter,request,op).images().getFirst();var action=RecoveryAction.valueOf(p.get("recovery_action").toString());
            if(r.recoveryVersion()!=Long.parseLong(p.get("recovery_version").toString())||!r.recoveryActions().contains(action))throw conflict("恢复状态已变化，请重新提案");
            String actionId=stable("creative-recovery",id).toString();
            commands.add(Map.of("action_id",actionId,"request_id",request.toString(),"operation_id",op.toString(),"body",Map.of("action_id",actionId,"action",action.name(),"expected_recovery_version",r.recoveryVersion())));
        } else {
            var selection=mapper.convertValue(p.get("selection"),Selection.class);
            var current=snapshot(actor,((Number)row.get("story_id")).longValue(),chapter,selection);
            if(!current.hash().equals(p.get("snapshot_hash")))throw conflict("已审阅的配置、分镜、素材或图片基准已变化");
            if(!queue.isProducerEnabled())throw conflict("生图受理已暂停");
            List<Target> targets=current.targets();
            for(int i=0;i<(selection.mode()==Mode.FIRST?1:targets.size());i++) {
                String requestId=stable("creative-image",id,i).toString();var body=new LinkedHashMap<String,Object>();
                body.put("request_id",requestId);body.put("config_id",selection.configId());body.put("model",selection.model());body.put("size",selection.size()==null?"auto":selection.size());
                if(selection.assetGroupId()!=null)body.put("asset_group_id",selection.assetGroupId());
                body.put("expected_provider_hash",current.providerHash());body.put("expected_reference_hash",current.referenceHash());
                var contextHashes=new TreeMap<String,String>();targets.forEach(t->contextHashes.put(Integer.toString(t.imageNumber()),t.contextHash()));body.put("expected_context_hashes",contextHashes);
                var command=new LinkedHashMap<String,Object>();command.put("action_id",requestId);command.put("body",body);
                if(selection.mode()==Mode.FIRST) {body.put("image_numbers",selection.imageNumbers());command.put("operation","MANGA_GENERATE");}
                else {var t=targets.get(i);body.put("expected_image_id",Long.parseLong(t.expectedImageId()));body.put("expected_image_version",Long.parseLong(t.expectedImageVersion()));command.put("image_number",t.imageNumber());command.put("operation","MANGA_REGENERATE");}
                commands.add(command);
            }
        }
        return new LinkedHashMap<>(Map.of("approved",true,"approved_at",Instant.now().toString(),"approved_by",Long.toString(actor),"commands",commands,"accepted_by_action",Map.of(),"accepted_requests",List.of()));
    }

    private record Snapshot(List<Target> targets,String providerHash,String referenceHash,String hash) {}
    private Snapshot snapshot(long actor,long story,long chapter,Selection s) {
        if(s==null||s.configId()==null||s.configVersion()==null||s.model()==null||s.mode()==null||s.imageNumbers()==null||s.imageNumbers().isEmpty()||s.imageNumbers().size()>60
                ||s.imageNumbers().stream().anyMatch(n->n==null||n<1||n>60)||new HashSet<>(s.imageNumbers()).size()!=s.imageNumbers().size())throw new BusinessException(400,"需要已保存配置、模式和不重复的 1-60 图序");
        if(s.size()!=null&&!s.size().matches("(?:[1-9][0-9]{1,3}x[1-9][0-9]{1,3}|auto)"))throw new BusinessException(400,"图片尺寸无效");
        if(jdbc.queryForList("SELECT c.id FROM chapters c JOIN stories s ON s.id=c.story_id WHERE c.id=? AND s.id=? AND s.user_id=?",chapter,story,actor).size()!=1)throw denied();
        var configs=jdbc.queryForList("SELECT id,provider,label,model,active,api_key,base_url FROM user_api_keys WHERE id=? AND user_id=? AND slot='image'",s.configId(),actor);
        if(configs.size()!=1||!configVersion(configs.getFirst()).equals(s.configVersion())||!modelNames(configs.getFirst().get("model")).contains(s.model()))throw conflict("配置版本或模型已变化，请重新读取模型目录");
        var provider=inputs.provider(actor,Map.of("config_id",s.configId(),"model",s.model()));String providerHash=hash(provider);
        var targets=new ArrayList<Target>();String referenceHash=null;
        for(int number:s.imageNumbers()) {
            var current=images.findByChapterIdAndImageNumber(chapter,number).orElse(null);
            if(s.mode()==Mode.FIRST?current!=null:current==null)throw conflict("图片基准与首次/重生成模式不匹配");
            var input=inputs.preview(actor,chapter,number,s.assetGroupId(),null);
            if(!com.artverse.prompt.MangaPromptPolicy.isStoryboardPage(Objects.toString(input.get("scene"),"")))throw new BusinessException(400,"请先生成有效的多格分镜");
            @SuppressWarnings("unchecked") var refs=(List<String>)input.getOrDefault("reference_images",List.of());
            String refHash=hash(inputs.freezeReferences(refs));
            if(referenceHash!=null&&!referenceHash.equals(refHash))throw conflict("各图参考素材不同，请分别准备提案");referenceHash=refHash;
            targets.add(new Target(number,current==null?null:current.getId().toString(),current==null?null:current.getVersion().toString(),hash(input),refs.size()));
        }
        String snapshotHash=json.hash(Arrays.asList(s,targets,providerHash,referenceHash));return new Snapshot(List.copyOf(targets),providerHash,referenceHash,snapshotHash);
    }
    private String configVersion(Map<String,Object> row) {return json.hash(row);}
    private List<String> modelNames(Object value) {return Arrays.stream(Objects.toString(value,"").split("[\\n,]")).map(String::trim).filter(v->!v.isEmpty()).distinct().toList();}
    private String nullableText(Object value) {return value==null?null:value.toString();}
}
