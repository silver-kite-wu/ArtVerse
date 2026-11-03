package com.artverse.application.imagequeue;

import com.artverse.application.ApiKeyService;
import com.artverse.config.ArtVerseProperties;
import com.artverse.ai.GeneratedImage;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

/** Separate private bucket; static image proxy can only access the existing image bucket. */
@Component @RequiredArgsConstructor
public class ImageGenerationObjects {
    private final ArtVerseProperties properties;
    private final ApiKeyService secrets;
    private MinioClient client;
    private static final int MAX_MANIFEST=60*1024*1024;
    @PostConstruct void init() {
        var p=properties.getMinio();client=MinioClient.builder().endpoint(p.getEndpoint()).credentials(p.getAccessKey(),p.getSecretKey()).region(p.getRegion())
                .httpClient(new okhttp3.OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(30)).writeTimeout(Duration.ofSeconds(30)).callTimeout(Duration.ofSeconds(45)).build()).build();
    }
    String privateBucket() { return properties.getMinio().getBucket()+"-generation-private"; }
    String key(long user,UUID root) { return "generation-inputs/"+user+"/"+root+"/manifest.enc"; }
    public Manifest freeze(Manifest m) {
        String encoded=secrets.encryptSecret(ImageJson.write(m));
        byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_MANIFEST)throw new ImageRequestException("INVALID_INPUT","输入快照过大");
        ensureBucket(privateBucket());
        conditional(privateBucket(),key(m.userId(),m.rootId()),bytes,"application/octet-stream",Map.of());
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
        ensureBucket(privateBucket());
        String path="generation-inputs/"+user+"/"+root+"/alias.json";
        conditional(privateBucket(),path,ImageJson.write(new Alias(canonical,submissionHash)).getBytes(StandardCharsets.UTF_8),"application/json",Map.of());
        if(!alias(user,root).orElseThrow().equals(new Alias(canonical,submissionHash)))throw new ImageRequestException("IDEMPOTENCY_CONFLICT","请求已关联另一操作");
    }
    public record Start(OffsetDateTime firstStartedAt,OffsetDateTime deadline) {}
    public Start start(Manifest m,Panel p,Duration total) {
        String path="generation-inputs/"+m.userId()+"/"+m.rootId()+"/operations/"+p.operationId()+"/start.json";
        var now=OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var stamp=new Start(now,now.plus(total));
        conditional(privateBucket(),path,ImageJson.write(stamp).getBytes(StandardCharsets.UTF_8),"application/json",Map.of("input-hash",p.inputHash()));
        return ImageJson.read(new String(read(privateBucket(),path,4096),StandardCharsets.UTF_8),Start.class);
    }
    public void upload(String objectKey,GeneratedImage image,Map<String,String> metadata) {
        try(var in=image.openStream()) {
            client.putObject(PutObjectArgs.builder().bucket(properties.getMinio().getBucket()).object(objectKey)
                    .stream(in,image.sizeBytes(),64L*1024*1024).contentType(image.contentType()).userMetadata(metadata)
                    .extraHeaders(Map.of("If-None-Match","*")).build());
        } catch(ErrorResponseException e) { if(!"PreconditionFailed".equals(e.errorResponse().code()))throw storageFailure(e); }
        catch(Exception e) { throw storageFailure(e); }
    }
    public boolean candidate(String key,String hash,long size,String contentType,UUID op,long token) {
        try {
            var stat=client.statObject(StatObjectArgs.builder().bucket(properties.getMinio().getBucket()).object(key).build());
            var meta=stat.userMetadata();
            if(stat.size()!=size||!Objects.equals(contentType,stat.contentType())||!hash.equals(meta.get("sha256"))||!op.toString().equals(meta.get("operation-id"))||!Long.toString(token).equals(meta.get("execution-token")))
                return false;
            byte[] content=read(properties.getMinio().getBucket(),key,32*1024*1024);
            if(!bytesHash(content).equals(hash))return false;
            return true;
        } catch(ErrorResponseException e) { if(missing(e))return false;throw storageFailure(e); }
        catch(ImageRequestException e) { throw e; }
        catch(Exception e) { throw storageFailure(e); }
    }
    private void ensureBucket(String bucket) {
        try { if(!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            try { client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
            catch(ErrorResponseException e) { if(!Set.of("BucketAlreadyOwnedByYou","BucketAlreadyExists").contains(e.errorResponse().code()))throw e; }
        } } catch(Exception e) { throw storageFailure(e); }
    }
    private void conditional(String bucket,String key,byte[] content,String type,Map<String,String> metadata) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(content),content.length,64L*1024*1024)
                    .contentType(type).userMetadata(metadata).extraHeaders(Map.of("If-None-Match","*")).build());
        } catch(ErrorResponseException e) { if(!"PreconditionFailed".equals(e.errorResponse().code()))throw storageFailure(e); }
        catch(Exception e) { throw storageFailure(e); }
    }
    private byte[] read(String bucket,String key,int max) {
        try(var in=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes=in.readNBytes(max+1);if(bytes.length>max)throw new IOException("Object exceeds bound");return bytes;
        } catch(ErrorResponseException e) { if(missing(e))throw new MissingObject();throw storageFailure(e); }
        catch(Exception e) { throw storageFailure(e); }
    }
    private boolean missing(ErrorResponseException e) { return Set.of("NoSuchKey","NoSuchBucket","NoSuchObject").contains(e.errorResponse().code()); }
    private RuntimeException storageFailure(Exception e) { return new ImageRequestException("STATUS_UNAVAILABLE","无法核实持久图片存储"); }
    static class MissingObject extends RuntimeException {}
}
