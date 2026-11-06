package com.artverse.application.imagequeue;

import com.artverse.application.*;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.artverse.media.MediaStorageService;
import com.artverse.storage.ObjectStorageService;
import com.artverse.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

@Service @RequiredArgsConstructor
public class ImageRequestInputs {
    private final ApiKeyService apiKeys;
    private final UserRepository users;
    private final ChapterRepository chapters;
    private final MangaImageRepository images;
    private final MangaGenerationService manga;
    private final AiConversationService conversations;
    private final ArtVerseProperties props;
    private final ObjectStorageService storage;
    private final MediaStorageService media;

    public UserProviderConfig provider(long user,Map<String,Object> body) {
        Long configId=number(body.get("config_id"));
        String provider=text(body,"provider"),key=text(body,"api_key"),base=text(body,"base_url"),model=text(body,"model");
        if(configId==null&&(provider.isBlank()||key.isBlank()||base.isBlank()||model.isBlank())) {
            boolean none=configId==null&&provider.isBlank()&&key.isBlank()&&base.isBlank();
            throw new ImageRequestException(none?"PROVIDER_REQUIRED":"PROVIDER_CONFIG_INVALID",none?"无供应商，请选择生图供应商":"临时供应商配置不完整");
        }
        User owner=users.findById(user).orElseThrow(()->new BusinessException(401,"Login expired"));
        var override=new UserProviderConfig("image",provider,text(body,"label"),key,base,model);
        try {
            var selected=apiKeys.requireProviderConfig(owner,override,configId,"无供应商，请选择生图供应商");
            if(configId!=null&&!model.isBlank())selected=apiKeys.requireProviderConfigForModel(owner,"image",configId,model,"供应商凭据不可用");
            String first=selected.model().split("[\\n,]")[0].trim();
            if(first.isBlank()||selected.provider().isBlank())throw new ImageRequestException("PROVIDER_CONFIG_INVALID","供应商或模型无效");
            return new UserProviderConfig("image",selected.provider(),selected.label(),selected.apiKey(),selected.baseUrl(),first,selected.configId());
        } catch(BusinessException e) {
            if(e.getStatus()==403||e.getStatus()==404)throw e;
            throw new ImageRequestException("PROVIDER_CONFIG_INVALID","所选供应商配置不可用");
        }
    }
    public void revalidateProvider(Manifest m) {
        if(m.provider().configId()!=null) {
            var current=provider(m.userId(),Map.of("config_id",m.provider().configId(),"model",m.provider().model()));
            if(!hash(current).equals(hash(m.provider())))throw new ImageRequestException("CONFIG_CHANGED","供应商配置已变化，请重新确认");
        }
    }
    @Transactional(readOnly=true)
    public Map<String,Object> preview(long user,long chapter,int number,Long assets,String scene) {
        return manga.queueImageInput(chapter,assets,user,number,scene);
    }
    @Transactional(readOnly=true)
    public List<Integer> missingPanels(long user,long chapter) {
        Chapter c=chapters.findByIdForIdempotencyAndUserId(chapter,user).orElseThrow(()->new BusinessException(404,"Chapter not found"));
        if(c.getImageCount()<1||c.getImageCount()>60)throw new ImageRequestException("INVALID_INPUT","章节图数须为 1 到 60");
        Set<Integer> existing=new HashSet<>();images.findByChapterIdOrderByImageNumberAsc(chapter).forEach(i->existing.add(i.getImageNumber()));
        List<Integer> result=new ArrayList<>();for(int i=1;i<=c.getImageCount();i++)if(!existing.contains(i))result.add(i);return result;
    }
    @Transactional
    public UUID conversation(long user,UUID id,String prompt) {
        User owner=users.getReferenceById(user);
        var c=id==null?conversations.activeImageConversation(owner):conversations.require(owner,id);
        if(c.getConversationType()!=AiConversationType.IMAGE_GEN)throw new ImageRequestException("INVALID_INPUT","不是生图会话");
        conversations.autoTitle(c,prompt);conversations.touch(c);return c.getConversationUuid();
    }
    @Transactional(readOnly=true)
    public void baseline(Manifest m,Panel p) {
        if(m.chapterId()==null)return;
        chapters.findByIdForIdempotencyAndUserId(m.chapterId(),m.userId()).orElseThrow(()->new ImageRequestException("INPUT_STALE","章节权限已变化"));
        MangaImage current=images.findByChapterIdAndImageNumber(m.chapterId(),p.imageNumber()).orElse(null);
        if(p.expectedImageId()==null) {
            if(current!=null)throw new ImageRequestException("TARGET_CONFLICT","目标已有图片，请刷新后确认重生成");
        } else if(current==null||!current.getId().equals(p.expectedImageId())||!current.getVersion().equals(p.expectedImageVersion()))
            throw new ImageRequestException("TARGET_CONFLICT","图片已变化，请刷新后确认");
    }
    public void revalidate(Manifest m,Panel p) {
        revalidateProvider(m);baseline(m,p);
        if(m.chapterId()!=null&&!hash(preview(m.userId(),m.chapterId(),p.imageNumber(),m.assetGroupId(),p.sceneOverride())).equals(p.contextHash()))
            throw new ImageRequestException("INPUT_STALE","分镜或章节上下文已变化");
    }
    public List<String> freezeReferences(List<String> keys) {
        List<String> values=new ArrayList<>();
        for(String key:keys) {
            try {
                byte[] bytes;
                Path local=media.resolveRelativePath(key);
                if(local!=null&&Files.exists(local)) { if(Files.size(local)>props.getUpload().getMaxImageBytes())throw new IOException();bytes=Files.readAllBytes(local); }
                else try(var in=storage.get(storage.defaultBucket(),key)) { bytes=in.readNBytes((int)props.getUpload().getMaxImageBytes()+1); }
                media.validateImageBytes(bytes,props.getUpload().getMaxImageBytes());values.add(Base64.getEncoder().encodeToString(bytes));
            } catch(Exception e) { throw new ImageRequestException("INVALID_INPUT","参考图片无法固定，请检查参考图"); }
        }
        return values;
    }
    public List<String> validateReferences(Object raw) {
        if(raw==null)return List.of();
        if(!(raw instanceof List<?> list)||list.size()>3)throw new ImageRequestException("INVALID_INPUT","最多支持三张参考图片");
        List<String> refs=new ArrayList<>();
        for(Object item:list) {
            if(!(item instanceof String value))throw new ImageRequestException("INVALID_INPUT","参考图格式无效");
            byte[] data=media.decodeBase64Image(value);media.validateImageBytes(data,props.getUpload().getMaxImageBytes());refs.add(Base64.getEncoder().encodeToString(data));
        }
        return refs;
    }
    public static String text(Map<String,Object> body,String field) { return Objects.toString(body.get(field),"").trim(); }
    public static Long number(Object value) { try { return value==null||value.toString().isBlank()?null:Long.valueOf(value.toString()); } catch(Exception e) { throw new ImageRequestException("INVALID_INPUT","数值字段无效"); } }
    public static UUID uuid(String value) { try { UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException();return id; }catch(Exception e) { throw new ImageRequestException("INVALID_INPUT","request_id 须为规范 UUID"); } }
}
