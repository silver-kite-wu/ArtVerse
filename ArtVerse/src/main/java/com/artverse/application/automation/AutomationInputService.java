package com.artverse.application.automation;

import com.artverse.application.*;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AutomationInputService {
    private final UserRepository users;
    private final ChapterRepository chapters;
    private final ApiKeyService apiKeys;
    private final ScheduledMangaGenerationService manga;
    private final ObjectMapper mapper;
    private final AiConversationService conversations;
    private final com.artverse.application.AiPermissionService aiPermissions;

    @Transactional(readOnly=true)
    public AutomationDefinition validateAndNormalize(long userId,AutomationDefinition input) {
        JsonNode raw=input.payload();
        if(raw==null||!raw.isObject())throw new BusinessException(400,"缺少任务执行对象");
        Set<String> allowed=Set.of("version","storyId","chapterId","chapterIds","modelConfigId","model","inputPolicy","size","referenceImageKeys","conversationId");
        raw.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new BusinessException(400,"任务配置包含不支持的字段："+k);});
        ObjectNode payload=raw.deepCopy();payload.put("version",1);
        User user=user(userId);
        boolean image="IMAGE".equals(input.taskType());
        UserProviderConfig config=config(userId,payload,image);
        if(config.configId()==null)throw new BusinessException(400,"定时任务必须选择已保存的模型配置");
        payload.put("modelConfigId",config.configId());payload.put("model",config.model());
        if(!"IMAGE_GEN".equals(input.executorKey())) {
            List<Long> ids=chapterIds(payload,input.executorKey());
            for(Long id:ids) {
                Chapter chapter=chapters.findByIdAndUserId(id,userId).orElseThrow(()->new BusinessException(404,"章节不存在或无权访问"));
                long storyId=chapter.getStory().getId();
                if(payload.hasNonNull("storyId")&&payload.path("storyId").asLong()!=storyId)throw new BusinessException(400,"章节不属于所选作品");
                payload.put("storyId",storyId);
            }
            if("MANGA_IMAGE".equals(input.executorKey())) {
                String policy=payload.path("inputPolicy").asText("ONCE".equals(input.schedule().kind())?"FIXED_SNAPSHOT":"RESOLVE_AT_RUN");
                if(!Set.of("FIXED_SNAPSHOT","RESOLVE_AT_RUN").contains(policy))throw new BusinessException(400,"无效的生图输入策略");
                payload.put("inputPolicy",policy);
                ArrayNode snapshots=mapper.createArrayNode();
                int count=0;
                for(Long id:ids) { var snapshot=manga.previewAutomationInputs(userId,id,config);snapshots.add(mapper.valueToTree(snapshot));count+=snapshot.missingImageNumbers().size(); }
                if(count>input.limits().maxImagesPerRun())throw new BusinessException(400,"待生成图片数量超过本次执行上限，请调整范围或上限");
                if("FIXED_SNAPSHOT".equals(policy))payload.set("chapterSnapshots",snapshots);
                payload.set("chapterIds",mapper.valueToTree(ids));
            } else { payload.put("chapterId",ids.getFirst()); }
        } else {
            String size=payload.path("size").asText("1024x1024");
            if(!size.matches("[1-9][0-9]{2,3}x[1-9][0-9]{2,3}|auto"))throw new BusinessException(400,"无效图片尺寸");
            payload.put("size",size);
            if(payload.hasNonNull("conversationId")) {
                MangaAgentConversation c=conversations.require(user,UUID.fromString(payload.path("conversationId").asText()));
                if(c.getConversationType()!=AiConversationType.IMAGE_GEN)throw new BusinessException(400,"请选择图片会话");
            }
            validateReferences(userId,payload.path("referenceImageKeys"));
        }
        return new AutomationDefinition(input.name(),input.taskType(),input.executorKey(),input.instruction(),input.schedule(),payload,input.limits(),input.misfirePolicy(),input.overlapPolicy(),input.maxLatenessSeconds(),null,input.origin(),input.sourceConversationId(),permission(userId,input.permissionMode()));
    }

    /** 未显式选择时继承 AI 助手设置；显式值必须是受支持的模式。 */
    private String permission(long userId,String requested) {
        if(requested==null||requested.isBlank())return aiPermissions.get(userId);
        com.artverse.agent.AiPermissionMode mode=com.artverse.agent.AiPermissionMode.parse(requested);
        if(mode==null)throw new BusinessException(400,"未知的 AI 运行权限");
        return mode.name();
    }

    @Transactional(readOnly=true)
    public JsonNode prepare(AutomationStore.Run run) {
        AutomationDefinition definition=run.definition();
        JsonNode payload=definition.payload();
        UserProviderConfig config=config(run.userId(),payload,"IMAGE".equals(definition.taskType()));
        ObjectNode snapshot=mapper.createObjectNode();
        snapshot.put("modelConfigId",config.configId());snapshot.put("model",config.model());
        if("MANGA_IMAGE".equals(definition.executorKey())) {
            ArrayNode items=mapper.createArrayNode();int count=0;
            if("FIXED_SNAPSHOT".equals(payload.path("inputPolicy").asText())) {
                for(JsonNode frozen:payload.path("chapterSnapshots")) {
                    ScheduledMangaInputSnapshot original=mapper.convertValue(frozen,ScheduledMangaInputSnapshot.class);
                    ScheduledMangaInputSnapshot current=manga.previewAutomationInputs(run.userId(),original.chapterId(),config);
                    // Missing images may have been filled by another task; all other creative inputs stay fixed.
                    ObjectNode oldNode=mapper.valueToTree(original),newNode=mapper.valueToTree(current);
                    oldNode.remove(List.of("missingImageNumbers","missing_image_numbers"));newNode.remove(List.of("missingImageNumbers","missing_image_numbers"));
                    if(!oldNode.equals(newNode))throw new BusinessException(409,"INPUT_CHANGED");
                    items.add(frozen);count+=original.missingImageNumbers().size();
                }
            } else for(Long id:chapterIds(payload,"MANGA_IMAGE")) {
                var current=manga.previewAutomationInputs(run.userId(),id,config);items.add(mapper.valueToTree(current));count+=current.missingImageNumbers().size();
            }
            if(count>definition.limits().maxImagesPerRun())throw new BusinessException(400,"IMAGE_LIMIT_EXCEEDED");
            snapshot.set("chapters",items);snapshot.put("imageCount",count);
        } else if(!"IMAGE_GEN".equals(definition.executorKey())) {
            Chapter chapter=chapters.findByIdAndUserId(payload.path("chapterId").asLong(),run.userId()).orElseThrow(()->new BusinessException(404,"章节不存在"));
            snapshot.put("chapterId",chapter.getId());snapshot.put("chapterVersion",chapter.getVersion());
            snapshot.put("storyId",chapter.getStory().getId());
        } else {
            validateReferences(run.userId(),payload.path("referenceImageKeys"));snapshot.set("request",payload);
        }
        return snapshot;
    }
    public User user(long id) { return users.findById(id).orElseThrow(()->new BusinessException(404,"用户不存在")); }
    public AutomationDefinition refreshedDefinition(long userId,AutomationDefinition original) {
        ObjectNode payload=original.payload().deepCopy();payload.remove("chapterSnapshots");
        return validateAndNormalize(userId,new AutomationDefinition(original.name(),original.taskType(),original.executorKey(),original.instruction(),original.schedule(),payload,original.limits(),original.misfirePolicy(),original.overlapPolicy(),original.maxLatenessSeconds(),null,original.origin(),original.sourceConversationId(),original.permissionMode()));
    }
    public void refreshChildren(AutomationStore.Run run,List<AutomationStore.Execution> executions) {
        if(!"MANGA_IMAGE".equals(run.definition().executorKey()))return;
        for(var execution:executions)if(execution.scheduledMangaId()!=null) {
            for(var item:run.inputSnapshot().path("chapters")) {
                var snapshot=mapper.convertValue(item,ScheduledMangaInputSnapshot.class);
                if(execution.targetKey().equals("chapter:"+snapshot.chapterId()))manga.refreshAutomationChild(run.userId(),execution.scheduledMangaId(),snapshot);
            }
        }
    }
    public UserProviderConfig config(long userId,JsonNode payload,boolean image) {
        User user=user(userId);String slot=image?ApiKeyService.SLOT_IMAGE:ApiKeyService.SLOT_LLM;
        Long id=payload.hasNonNull("modelConfigId")?payload.path("modelConfigId").asLong():null;
        String model=payload.path("model").asText("");
        if(id==null)return apiKeys.requireActiveUserProviderConfig(user,slot,"请先保存可用的模型配置");
        if(model.isBlank())return apiKeys.requireProviderConfig(user,new UserProviderConfig(slot,"","","","",""),id,"模型配置不可用");
        return apiKeys.requireProviderConfigForModel(user,slot,id,model,"模型配置不可用");
    }
    public List<Long> chapterIds(JsonNode payload,String executor) {
        List<Long> ids=new ArrayList<>();
        if("MANGA_IMAGE".equals(executor)&&payload.has("chapterIds")) {
            if(!payload.get("chapterIds").isArray())throw new BusinessException(400,"请选择章节");
            payload.get("chapterIds").forEach(v->{if(!v.canConvertToLong()||v.asLong()<1)throw new BusinessException(400,"章节 ID 无效");ids.add(v.asLong());});
        } else if(payload.path("chapterId").asLong()>0)ids.add(payload.path("chapterId").asLong());
        if(ids.isEmpty()||ids.size()>3||new HashSet<>(ids).size()!=ids.size())throw new BusinessException(400,"每个任务请选择 1 至 3 个不同章节");
        return ids;
    }
    public void validateReferences(long userId,JsonNode refs) {
        if(refs.isMissingNode()||refs.isNull())return;
        if(!refs.isArray()||refs.size()>3)throw new BusinessException(400,"最多支持 3 张参考图");
        for(JsonNode key:refs)if(!key.isTextual()||!key.asText().startsWith("automation_reference/"+userId+"/")||key.asText().contains(".."))throw new BusinessException(403,"参考图不属于当前用户");
    }
}
