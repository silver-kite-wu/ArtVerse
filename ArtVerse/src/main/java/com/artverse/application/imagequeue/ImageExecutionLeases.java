package com.artverse.application.imagequeue;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.artverse.application.imagequeue.ImageProtocol.*;

/** Each scope is atomic separately. Acquisition order is fixed; partial acquisition rolls back in reverse. */
@Component @RequiredArgsConstructor
public class ImageExecutionLeases {
    private final ImageRedisState state;
    private final ImageQueueProperties props;
    private final ScheduledExecutorService renewal=Executors.newScheduledThreadPool(2,Thread.ofPlatform().daemon(true).name("image-lease-",0).factory());
    private static final String ACQUIRE="""
            local t=redis.call('time');local now=t[1]*1000+math.floor(t[2]/1000)
            redis.call('zremrangebyscore',KEYS[1],'-inf',now)
            if redis.call('zscore',KEYS[1],ARGV[1]) or redis.call('zcard',KEYS[1])<tonumber(ARGV[2]) then
            redis.call('zadd',KEYS[1],now+tonumber(ARGV[3]),ARGV[1]);return 1 end return 0
            """;
    private static final String RENEW="""
            local t=redis.call('time');local now=t[1]*1000+math.floor(t[2]/1000)
            local score=redis.call('zscore',KEYS[1],ARGV[1])
            if score and tonumber(score)>now then redis.call('zadd',KEYS[1],now+tonumber(ARGV[2]),ARGV[1]);return 1 end return 0
            """;
    public Claim acquire(Manifest m,Panel p,boolean model) {
        Claim c=new Claim(m,p);
        try {
            if(model) {
                c.permit("image:permits:{global}",props.getGlobalModelConcurrency());
                if(m.serial())c.permit("image:permits:{serial-model}",1);
                c.permit("image:permits:{user:"+m.userId()+"}",props.getUserModelConcurrency());
                c.permit("image:permits:{profile:"+m.profileKey()+"}",props.getProfileModelConcurrency());
            }
            c.lock(state.opLock(m,p));
            if(m.chapterId()!=null)c.lock(state.targetLock(m,p));
            c.future=renewal.scheduleAtFixedRate(c::renew,props.getExecutionRenew().toMillis(),props.getExecutionRenew().toMillis(),TimeUnit.MILLISECONDS);
            return c;
        } catch(RuntimeException e) { c.close();return null; }
    }
    public final class Claim implements AutoCloseable {
        public final String owner=UUID.randomUUID().toString();
        private final Manifest manifest;private final Panel panel;
        private final List<String> permits=new ArrayList<>(),locks=new ArrayList<>();
        private final AtomicBoolean valid=new AtomicBoolean(true);
        private ScheduledFuture<?> future;
        private Claim(Manifest m,Panel p) { manifest=m;panel=p; }
        private void permit(String key,int limit) {
            if(state.eval(ACQUIRE,List.of(key),owner,""+limit,""+props.getExecutionLease().toMillis())!=1)throw new IllegalStateException("No quota");permits.add(key);
        }
        private void lock(String key) { if(!state.lock(key,owner,props.getExecutionLease()))throw new IllegalStateException("Busy");locks.add(key); }
        public void assertValid() {
            if(!valid.get()||locks.stream().anyMatch(k->!state.owns(k,owner)))throw new ImageRequestException("STATUS_UNAVAILABLE","生图执行租约已失效");
        }
        private void renew() {
            try {
                for(String key:permits)if(state.eval(RENEW,List.of(key),owner,""+props.getExecutionLease().toMillis())!=1)valid.set(false);
                for(String key:locks)if(!state.renew(key,owner,props.getExecutionLease()))valid.set(false);
                if(valid.get()) {
                    state.eval("if redis.call('get',KEYS[2])==ARGV[1] then redis.call('hset',KEYS[1],'last_heartbeat_at',ARGV[2],'lease_expires_at',ARGV[3]);return 1 end return 0",
                            List.of(state.state(manifest,panel),state.opLock(manifest,panel)),owner,ImageRedisState.now(),java.time.OffsetDateTime.now().plus(props.getExecutionLease()).toString());
                    if(manifest.chapterId()!=null)state.eval("""
                        if redis.call('get',KEYS[2])~=ARGV[3] then return 0 end
                        local raw=redis.call('get',KEYS[1]);if not raw then return 0 end
                        if not string.find(raw,'"operationId":"'..ARGV[1]..'"',1,true) then return 0 end
                        local updated=string.gsub(raw,'"lastHeartbeatAt":"[^"]*"','"lastHeartbeatAt":"'..ARGV[2]..'"')
                        redis.call('set',KEYS[1],updated);return 1
                        """,List.of(state.target(manifest,panel),state.targetLock(manifest,panel)),panel.operationId().toString(),ImageRedisState.now(),owner);
                }
            } catch(RuntimeException e) { valid.set(false); }
        }
        @Override public void close() {
            valid.set(false);if(future!=null)future.cancel(false);
            boolean interrupted=Thread.interrupted();
            try {
                for(int i=locks.size()-1;i>=0;i--)try { state.unlock(locks.get(i),owner); }catch(RuntimeException ignored) {}
                for(int i=permits.size()-1;i>=0;i--)try { state.eval("return redis.call('zrem',KEYS[1],ARGV[1])",List.of(permits.get(i)),owner); }catch(RuntimeException ignored) {}
            }finally { if(interrupted)Thread.currentThread().interrupt(); }
        }
    }
    @PreDestroy void stop() { renewal.shutdown(); }
}
