package com.artverse.application.imagequeue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.artverse.application.imagequeue.ImageProtocol.*;

@Component @RequiredArgsConstructor
public class ImageCommandListener {
    private final ObjectMapper mapper;
    private final ImageGenerationObjects objects;
    private final ImageCommandProcessor processor;
    private final MangaSerialCoordinator coordinator;
    private final ImageRecoveryService recovery;
    private final ImageRequestQueries queries;
    private final ImageRedisState state;
    private final ImageQueueProperties props;
    private final java.util.concurrent.ScheduledExecutorService deadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("image-delivery-deadline").factory());
    public void receive(Message message,Channel channel) throws Exception {
        long tag=message.getMessageProperties().getDeliveryTag();
        Command command;
        try {
            if(message.getBody().length>65536)throw new IllegalArgumentException("Command too large");
            command=mapper.readValue(message.getBody(),Command.class);
            if(command.schemaVersion()!=1||command.rootId()==null||command.eventId()==null||command.userId()<=0)throw new IllegalArgumentException("Invalid identity");
        } catch(Exception invalid) { channel.basicReject(tag,false);return; } // Broker's reliable DLX retains malformed payload.
        Thread execution=Thread.currentThread();var expired=new java.util.concurrent.atomic.AtomicBoolean();
        Object deadlineGate=new Object();boolean[] completed={false};
        long timeout="IMAGE_EXECUTE".equals(command.kind())?props.getDeliveryProcessingTimeout().toMillis():30000;
        var deadline=deadlines.schedule(()-> {
            synchronized(deadlineGate) {
                if(completed[0])return;
                expired.set(true);execution.interrupt();
            }
            try { if(channel.isOpen())channel.abort(); }catch(Exception ignored) {}
        },timeout,java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            Manifest m=objects.load(command.userId(),command.rootId());
            if(!m.submissionHash().equals(command.manifestHash()))throw new IllegalArgumentException("Manifest mismatch");
            switch(command.kind()) {
                case "IMAGE_EXECUTE" -> {
                    if(command.cursor()<0||command.cursor()>=m.panels().size())throw new IllegalArgumentException("Invalid cursor");
                    String queue=message.getMessageProperties().getConsumerQueue();
                    if(queue!=null&&!queue.endsWith(m.serial()?"work.serial.q":"work.parallel.q"))throw new IllegalArgumentException("Invalid lane");
                    try(var completion=processor.execute(command,m)) {
                        if(expired.get())throw new IllegalStateException("Delivery budget exhausted; external outcome requires evidence");
                        channel.basicAck(tag,false);
                        // Deliberately outside all failure/nack paths: SSE failure never undoes an ACK.
                        try { completion.afterAck(); }catch(RuntimeException ignored) {}
                    }
                }
                case "MANGA_ADVANCE" -> {
                    boolean done=coordinator.advance(command,m);channel.basicAck(tag,false);
                    if(done)try { state.event(m,"done",queries.snapshot(m)); }catch(RuntimeException ignored) {}
                }
                case "IMAGE_RECOVER" -> {
                    recovery.recover(command,m);channel.basicAck(tag,false);
                    try { state.event(m,"snapshot",queries.snapshot(m)); }catch(RuntimeException ignored) {}
                }
                default -> channel.basicReject(tag,false);
            }
        } catch(IllegalArgumentException e) { channel.basicReject(tag,false); }
        // Transient infrastructure exceptions escape the MANUAL listener. The channel is closed so
        // the broker retains the unacknowledged delivery; no in-memory job or preemptive ACK exists.
        catch(Exception e) { if(channel.isOpen())channel.abort();throw e; }
        finally { synchronized(deadlineGate) { completed[0]=true; }deadline.cancel(false); }
    }
    @jakarta.annotation.PreDestroy void stop() { deadlines.shutdownNow(); }
}
