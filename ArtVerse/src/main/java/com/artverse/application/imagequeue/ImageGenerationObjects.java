package com.artverse.application.imagequeue;

import com.artverse.application.ApiKeyService;
import com.artverse.ai.GeneratedImage;
import com.artverse.storage.ObjectStat;
import com.artverse.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.artverse.application.imagequeue.ImageProtocol.*;

/** Separate private bucket; static image proxy can only access the existing image bucket. */
@Component @RequiredArgsConstructor
public class ImageGenerationObjects {
    private final ObjectStorageService storage;
    private final ApiKeyService secrets;
    private static final int MAX_MANIFEST=60*1024*1024;

    String privateBucket() { return storage.privateBucket(); }
    String key(long user,UUID root) { return "generation-inputs/"+user+"/"+root+"/manifest.enc"; }
    public Manifest freeze(Manifest m) {
        String encoded=secrets.encryptSecret(ImageJson.write(m));
        byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_MANIFEST)throw new ImageRequestException("INVALID_INPUT","输入快照过大");
        storage.putIfAbsent(privateBucket(),key(m.userId(),m.rootId()),new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream",Map.of());
        Manifest saved=load(m.userId(),m.rootId());
        if(!saved.submissionHash().equals(m.submissionHash()))throw new ImageRequestException("IDEMPOTENCY_CONFLICT","同请求身份的输入不同");
        return saved;
    }
    public Optional<Manifest> find(long user,UUID root) {
        try { return Optional.of(load(user,root)); }
        catch(MissingObject e) { return Optional.empty(); }
    }
    public Manifest load(long user,UUID root) {
        byte[] bytes=read(privateBucket(),key(user,root),MAX_MANIFEST);
        var result=ImageJson.read(secrets.decryptSecret(new String(bytes,StandardCharsets.UTF_8)),Manifest.class);
        if(result.userId()!=user||!result.rootId().equals(root)||result.schemaVersion()!=1)throw new ImageRequestException("INPUT_STALE","输入快照归属错误");
        return result;
    }
    public record Alias(UUID canonicalRoot,String submissionHash) {}
    public Optional<Alias> alias(long user,UUID root) {
        try { return Optional.of(ImageJson.read(new String(read(privateBucket(),"generation-inputs/"+user+"/"+root+"/alias.json",4096),StandardCharsets.UTF_8),Alias.class)); }
        catch(MissingObject e) { return Optional.empty(); }
    }
    public void alias(long user,UUID root,UUID canonical,String submissionHash) {
        String path="generation-inputs/"+user+"/"+root+"/alias.json";
        byte[] bytes=ImageJson.write(new Alias(canonical,submissionHash)).getBytes(StandardCharsets.UTF_8);
        storage.putIfAbsent(privateBucket(),path,new ByteArrayInputStream(bytes),bytes.length,"application/json",Map.of());
        if(!alias(user,root).orElseThrow().equals(new Alias(canonical,submissionHash)))throw new ImageRequestException("IDEMPOTENCY_CONFLICT","请求已关联另一操作");
    }
    public record Start(OffsetDateTime firstStartedAt,OffsetDateTime deadline) {}
    public Start start(Manifest m,Panel p,Duration total) {
        String path="generation-inputs/"+m.userId()+"/"+m.rootId()+"/operations/"+p.operationId()+"/start.json";
        var now=OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var stamp=new Start(now,now.plus(total));
        byte[] bytes=ImageJson.write(stamp).getBytes(StandardCharsets.UTF_8);
        storage.putIfAbsent(privateBucket(),path,new ByteArrayInputStream(bytes),bytes.length,"application/json",Map.of("input-hash",p.inputHash()));
        return ImageJson.read(new String(read(privateBucket(),path,4096),StandardCharsets.UTF_8),Start.class);
    }
    public void upload(String objectKey,GeneratedImage image,Map<String,String> metadata) {
        try(var in=image.openStream()) {
            storage.putIfAbsent(storage.defaultBucket(),objectKey,in,image.sizeBytes(),image.contentType(),metadata);
        } catch(IOException e) { throw storageFailure(e); }
        catch(RuntimeException e) { throw storageFailure(e); }
    }
    public boolean candidate(String key,String hash,long size,String contentType,UUID op,long token) {
        try {
            Optional<ObjectStat> stat=storage.statWithMetadata(storage.defaultBucket(),key);
            if(stat.isEmpty())return false;
            var meta=stat.get().metadata();
            if(stat.get().sizeBytes()!=size||!Objects.equals(contentType,stat.get().contentType())||!hash.equals(meta.get("sha256"))||!op.toString().equals(meta.get("operation-id"))||!Long.toString(token).equals(meta.get("execution-token")))
                return false;
            byte[] content=read(storage.defaultBucket(),key,32*1024*1024);
            if(!bytesHash(content).equals(hash))return false;
            return true;
        } catch(ImageRequestException e) { throw e; }
        catch(RuntimeException e) { throw storageFailure(e); }
    }
    private byte[] read(String bucket,String key,int max) {
        try {
            Optional<InputStream> opened=storage.openIfPresent(bucket,key);
            if(opened.isEmpty())throw new MissingObject();
            try(var in=opened.get()) {
                byte[] bytes=in.readNBytes(max+1);if(bytes.length>max)throw new IOException("Object exceeds bound");return bytes;
            }
        } catch(MissingObject e) { throw e; }
        catch(IOException e) { throw storageFailure(e); }
        catch(RuntimeException e) { throw storageFailure(e); }
    }
    private RuntimeException storageFailure(Exception e) { return new ImageRequestException("STATUS_UNAVAILABLE","无法核实持久图片存储"); }
    static class MissingObject extends RuntimeException {}
}
