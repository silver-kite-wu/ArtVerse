package com.artverse.application.imagequeue;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.artverse.application.imagequeue.ImageProtocol.*;

@Service @RequiredArgsConstructor
public class ImageRequestEvents {
    private final ImageRequestQueries queries;
    private final ImageRedisState state;
    private final RedisMessageListenerContainer redis;
    private final ConcurrentHashMap<String,Hub> hubs=new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("image-sse-heartbeat").factory());
    private final ExecutorService dispatch=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("image-sse-send-",0).factory());
    private final Semaphore sending=new Semaphore(128);
    private void send(SseEmitter emitter,SseEmitter.SseEventBuilder event) {
        // A slow browser must not block Redis's subscriber or another request's heartbeat.
        // Bounded hints may be dropped; authoritative GET supplies all results.
        if(!sending.tryAcquire())return;
        Future<?> future=dispatch.submit(()-> {
            try { emitter.send(event); }catch(Exception e) { emitter.complete(); }
            finally { sending.release(); }
        });
        heartbeat.schedule(()->future.cancel(true),2,TimeUnit.SECONDS);
    }
    public SseEmitter open(long user,UUID root) {
        Manifest m=queries.manifest(user,root);String channel=state.channel(user,m.rootId());
        SseEmitter emitter=new SseEmitter(120000L);AtomicBoolean closed=new AtomicBoolean();
        Hub hub=hubs.compute(channel,(key,existing)-> {
            if(existing==null) { existing=new Hub(key);redis.addMessageListener(existing,new ChannelTopic(key)); }
            existing.emitters.add(emitter);return existing;
        });
        Runnable cleanup=()-> {
            if(!closed.compareAndSet(false,true))return;
            hubs.computeIfPresent(channel,(key,existing)-> {
                existing.emitters.remove(emitter);
                if(existing.emitters.isEmpty()) { redis.removeMessageListener(existing,new ChannelTopic(key));return null; }return existing;
            });
        };
        emitter.onCompletion(cleanup);emitter.onTimeout(cleanup);emitter.onError(e->cleanup.run());
        try { send(emitter,SseEmitter.event().name("snapshot").data(queries.snapshot(m))); }catch(Exception e) { cleanup.run();emitter.complete(); }
        var future=heartbeat.scheduleAtFixedRate(()-> {
            if(closed.get())return;
            try { send(emitter,SseEmitter.event().comment("heartbeat")); }catch(Exception e) { cleanup.run();emitter.complete(); }
        },15,15,TimeUnit.SECONDS);
        emitter.onCompletion(()->future.cancel(false));emitter.onTimeout(()->future.cancel(false));emitter.onError(e->future.cancel(false));return emitter;
    }
    private final class Hub implements MessageListener {
        private final String channel;
        private final Set<SseEmitter> emitters=ConcurrentHashMap.newKeySet();
        Hub(String channel) { this.channel=channel; }
        @Override public void onMessage(org.springframework.data.redis.connection.Message message,byte[] pattern) {
            try {
                var event=ImageJson.read(new String(message.getBody(),StandardCharsets.UTF_8),Map.class);
                for(var emitter:emitters)try { send(emitter,SseEmitter.event().name(event.get("event").toString()).data(event.get("data"))); }
                catch(Exception e) { emitter.complete(); }
            }catch(RuntimeException ignored) {}
        }
    }
    @PreDestroy void stop() { heartbeat.shutdownNow();dispatch.shutdownNow();hubs.values().forEach(h->h.emitters.forEach(SseEmitter::complete)); }
}
