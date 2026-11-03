package com.artverse.application.imagequeue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.artverse.application.imagequeue.ImageMessagingConfig.PREFIX;
import static com.artverse.application.imagequeue.ImageProtocol.*;

@Component @RequiredArgsConstructor
public class ImageCommandPublisher {
    private final RabbitTemplate template;
    private final ObjectMapper mapper;
    private final ImageQueueProperties props;
    private final ImageRedisState state;
    public void execute(Manifest manifest,Panel panel,int cursor) { publish(command(manifest,panel,cursor,"IMAGE_EXECUTE"),"work.x",manifest.serial()?"execute.serial":"execute.parallel"); }
    public Command command(Manifest m,Panel p,int cursor,String kind) { return new Command(1,kind,UUID.randomUUID(),m.userId(),m.rootId(),p==null?null:p.operationId(),m.submissionHash(),cursor,0,0,0,null); }
    public void advance(Manifest manifest) { publish(nextAdvance(manifest),"advance.x","advance"); }
    public Command nextAdvance(Manifest m) {
        return nextAdvance(m,0);
    }
    public Command nextAdvance(Manifest m,long minimum) {
        long generation=state.eval("local current=tonumber(redis.call('hget',KEYS[1],'control_generation') or '0');local next=math.max(current,tonumber(ARGV[1]))+1;redis.call('hset',KEYS[1],'control_generation',next);return next",List.of(state.root(m.userId(),m.rootId())),Long.toString(minimum));
        return new Command(1,"MANGA_ADVANCE",UUID.randomUUID(),m.userId(),m.rootId(),null,m.submissionHash(),0,generation,0,0,null);
    }
    public void delay(Command command,Manifest manifest) {
        long remaining=Math.max(1,command.notBefore()-System.currentTimeMillis());
        int seconds=remaining<=30000?30:remaining<=120000?120:600;
        publish(command,"retry.x",(manifest.serial()?"serial":"parallel")+"."+seconds);
    }
    public void quarantine(Command command,String error) { publish(command,"quarantine.x","isolate"); }
    public void publish(Command command,String exchange,String routing) {
        try {
            byte[] bytes=mapper.writeValueAsBytes(command);
            if(bytes.length>65536)throw new IllegalArgumentException("Command exceeds 64KiB");
            var properties=new MessageProperties();properties.setContentType("application/json");properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(command.eventId().toString());
            var correlation=new CorrelationData(UUID.randomUUID().toString());
            template.setMandatory(true);
            template.send(PREFIX+exchange,routing,new Message(bytes,properties),correlation);
            var confirm=correlation.getFuture().get(props.getPublishConfirmTimeout().toMillis(),TimeUnit.MILLISECONDS);
            if(correlation.getReturned()!=null)throw new ImageRequestException("UNROUTABLE","生图命令无法路由，请使用原请求重试");
            if(!confirm.isAck())throw new ImageRequestException("QUEUE_UNAVAILABLE","消息队列拒绝受理，请使用原请求重试");
        } catch(ImageRequestException e) { throw e; }
        catch(java.util.concurrent.TimeoutException e) { throw new ImageRequestException("PUBLISH_OUTCOME_UNKNOWN","发布确认未知，请使用原请求重试"); }
        catch(Exception e) { throw new ImageRequestException("PUBLISH_OUTCOME_UNKNOWN","未能确认可靠受理，请使用原请求重试"); }
    }
}
