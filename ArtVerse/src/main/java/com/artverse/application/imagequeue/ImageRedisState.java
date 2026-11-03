package com.artverse.application.imagequeue;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Duration;
import java.util.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

@Component @RequiredArgsConstructor
public class ImageRedisState {
    private final StringRedisTemplate redis;
    private final ImageQueueProperties props;
    public String root(long user,UUID id) { return "image:request:"+user+":{"+id+"}"; }
    public String state(Manifest m,Panel p) { return m.chapter()?"image:op:"+m.userId()+":{"+m.rootId()+"}:"+p.operationId():root(m.userId(),m.rootId()); }
    public String opLock(Manifest m,Panel p) { return "image:op-lock:"+m.userId()+":{"+m.rootId()+"}:"+p.operationId(); }
    public String targetLock(Manifest m,Panel p) { return "image:target-lock:{"+m.chapterId()+":"+p.imageNumber()+"}"; }
    public String target(Manifest m,Panel p) { return "image:target-active:{"+m.chapterId()+":"+p.imageNumber()+"}"; }
    public String channel(long user,UUID root) { return "image:events:"+user+":{"+root+"}"; }
    public Map<String,String> read(String key) {
        Map<String,String> result=new LinkedHashMap<>();redis.opsForHash().entries(key).forEach((k,v)->result.put(k.toString(),v.toString()));return result;
    }
    public String value(String key) { return redis.opsForValue().get(key); }
    public void metadata(String key,Map<String,String> values) { redis.opsForHash().putAll(key,values); }
    public boolean lock(String key,String owner,Duration ttl) { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,owner,ttl)); }
    public boolean renew(String key,String owner,Duration ttl) {
        return eval("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('pexpire',KEYS[1],ARGV[2]) end return 0",List.of(key),owner,""+ttl.toMillis())==1;
    }
    public void unlock(String key,String owner) { eval("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) end return 0",List.of(key),owner); }
    public boolean owns(String key,String owner) { return owner.equals(value(key)); }
    public boolean targetClaim(String key,String canonical) { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,canonical)); }
    public void targetRelease(String key,String canonical) { unlock(key,canonical); }
    public void queued(Manifest m,Panel p) {
        String key=state(m,p);
        eval("if redis.call('hexists',KEYS[1],'status')==0 then redis.call('hset',KEYS[1],'status','QUEUED','state_epoch',ARGV[1],'version','1','updated_at',ARGV[2]);return 1 end return 0",List.of(key),UUID.randomUUID().toString(),now());
    }
    /** Only transmission metadata is changed; delayed producer callbacks never change execution status. */
    public void accepted(Manifest m) {
        eval("redis.call('hset',KEYS[1],'submission_status','ACCEPTED');return 1",List.of(root(m.userId(),m.rootId())));
    }
    public boolean claim(Manifest m,Panel p,String owner) {
        return eval("""
                if redis.call('get',KEYS[2])~=ARGV[1] then return 0 end
                local s=redis.call('hget',KEYS[1],'status')
                if s~='QUEUED' and s~='LOAD_CONTEXT' and s~='RETRY_WAIT' then return 0 end
                redis.call('hset',KEYS[1],'status','LOAD_CONTEXT','owner',ARGV[1],'claim_id',ARGV[1],'claimed_at',ARGV[2],'updated_at',ARGV[2],'status_started_at',ARGV[2])
                redis.call('hsetnx',KEYS[1],'started_at',ARGV[2])
                redis.call('hincrby',KEYS[1],'version',1);return 1
                """,List.of(state(m,p),opLock(m,p)),owner,now())==1;
    }
    public void transition(Manifest m,Panel p,String owner,Status next,long token) {
        String expectedVersion=read(state(m,p)).getOrDefault("version","0");
        if(eval("""
            if redis.call('get',KEYS[2])~=ARGV[1] or redis.call('hget',KEYS[1],'owner')~=ARGV[1] then return 0 end
            if (redis.call('hget',KEYS[1],'version') or '0')~=ARGV[5] then return 0 end
            local s=redis.call('hget',KEYS[1],'status')
            local allowed={LOAD_CONTEXT={GENERATING=true,COMMITTING=true},GENERATING={UPLOADING=true},UPLOADING={COMMITTING=true},COMMITTING={SUCCEEDED=true}}
            if not allowed[s] or not allowed[s][ARGV[2]] then return 0 end
            local t=redis.call('hget',KEYS[1],'execution_token')
            if t and t~=ARGV[3] then return 0 end
            redis.call('hset',KEYS[1],'status',ARGV[2],'execution_token',ARGV[3],'updated_at',ARGV[4],'status_started_at',ARGV[4]);redis.call('hincrby',KEYS[1],'version',1);return 1
            """,List.of(state(m,p),opLock(m,p)),owner,next.name(),""+token,now(),expectedVersion)!=1)throw new ImageRequestException("STATUS_UNAVAILABLE","执行租约或状态已变化");
    }
    /** Used only after durable evidence has been read under the execution lease. */
    public void evidence(Manifest m,Panel p,String owner,String status) {
        if(eval("""
            if redis.call('get',KEYS[2])~=ARGV[1] then return 0 end
            local s=redis.call('hget',KEYS[1],'status')
            if s=='SUCCEEDED' and ARGV[2]~='SUCCEEDED' then return 0 end
            redis.call('hsetnx',KEYS[1],'state_epoch',ARGV[4]);redis.call('hset',KEYS[1],'status',ARGV[2],'owner',ARGV[1],'updated_at',ARGV[3]);redis.call('hincrby',KEYS[1],'version',1);return 1
            """,List.of(state(m,p),opLock(m,p)),owner,status,now(),UUID.randomUUID().toString())!=1)throw new ImageRequestException("STATUS_UNAVAILABLE","无法同步执行凭据");
        retainTerminal(m,p,status);
    }
    public void retainTerminal(Manifest m,Panel p,String status) {
        if(m.chapter())return; // Keep prior chapter results while a later panel remains unresolved.
        if(terminal(status))eval("if redis.call('hget',KEYS[1],'status')==ARGV[1] then return redis.call('pexpire',KEYS[1],ARGV[2]) end return 0",List.of(state(m,p)),status,""+props.getTerminalRetention().toMillis());
    }
    public void expireRoot(Manifest m) {
        for(Panel p:m.panels())redis.expire(state(m,p),props.getTerminalRetention());
        redis.expire(root(m.userId(),m.rootId()),props.getTerminalRetention());
    }
    public long eval(String lua,List<String> keys,String... args) {
        Long value=redis.execute(new DefaultRedisScript<>(lua,Long.class),keys,(Object[])args);return value==null?0:value;
    }
    public void event(Manifest m,String event,Object payload) {
        try { redis.convertAndSend(channel(m.userId(),m.rootId()),ImageJson.write(Map.of("event",event,"data",payload))); }
        catch(RuntimeException ignored) { /* Notifications never decide business success. GET supplies the same result. */ }
    }
    public static String now() { return java.time.OffsetDateTime.now().toString(); }
}
