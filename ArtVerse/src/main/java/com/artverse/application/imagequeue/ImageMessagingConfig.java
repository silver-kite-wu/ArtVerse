package com.artverse.application.imagequeue;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.context.annotation.*;
import org.springframework.core.task.support.TaskExecutorAdapter;
import java.util.*;
import org.springframework.amqp.core.Queue;
import java.util.concurrent.Executors;

@Configuration
public class ImageMessagingConfig {
    public static final String PREFIX="artverse.image.";
    private Queue queue(String name, String deadExchange, String deadRouting, Integer ttl) {
        var builder=QueueBuilder.durable(PREFIX+name).quorum().withArgument("x-overflow","reject-publish")
                .withArgument("x-delivery-limit",20).withArgument("x-max-length",10000);
        if(deadExchange!=null) builder.deadLetterExchange(PREFIX+deadExchange).deadLetterRoutingKey(deadRouting)
                .withArgument("x-dead-letter-strategy","at-least-once");
        if(ttl!=null)builder.ttl(ttl);
        return builder.build();
    }
    @Bean public Declarables imageTopology() {
        List<Declarable> declarations=new ArrayList<>();
        for(String exchange:List.of("work.x","retry.x","advance.x","advance.wait.x","recovery.x","recovery.wait.x","quarantine.x"))
            declarations.add(new DirectExchange(PREFIX+exchange,true,false));
        for(String lane:List.of("parallel","serial")) {
            bind(declarations,queue("work."+lane+".q","quarantine.x","isolate",null),"work.x","execute."+lane);
            for(int seconds:List.of(30,120,600))bind(declarations,queue("retry."+lane+"."+seconds+"s.q","work.x","execute."+lane,seconds*1000),"retry.x",lane+"."+seconds);
        }
        bind(declarations,queue("advance.q","quarantine.x","isolate",null),"advance.x","advance");
        bind(declarations,queue("advance.wait.5s.q","advance.x","advance",5000),"advance.wait.x","wait");
        bind(declarations,queue("recovery.q","quarantine.x","isolate",null),"recovery.x","recover");
        for(int seconds:List.of(30,120))bind(declarations,queue("recovery.wait."+seconds+"s.q","recovery.x","recover",seconds*1000),"recovery.wait.x","wait."+seconds);
        bind(declarations,queue("quarantine.q",null,null,null),"quarantine.x","isolate");
        return new Declarables(declarations);
    }
    private void bind(List<Declarable> all,Queue queue,String exchange,String routing) {
        all.add(queue);all.add(new Binding(queue.getName(),Binding.DestinationType.QUEUE,PREFIX+exchange,routing,null));
    }
    private SimpleMessageListenerContainer container(ConnectionFactory cf,ImageQueueProperties props,ImageCommandListener listener,String queue,int consumers,boolean enabled) {
        var c=new SimpleMessageListenerContainer(cf);c.setQueueNames(PREFIX+queue);c.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        c.setPrefetchCount(1);c.setConcurrentConsumers(consumers);c.setAutoStartup(enabled);
        c.setTaskExecutor(new TaskExecutorAdapter(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("image-listener-",0).factory())));
        c.setShutdownTimeout(props.getDrainTimeout().toMillis());c.setForceCloseChannel(false);
        c.setRecoveryInterval(5000);c.setDefaultRequeueRejected(false);
        c.setMessageListener((ChannelAwareMessageListener)listener::receive);return c;
    }
    @Bean public SimpleMessageListenerContainer imageParallelContainer(ConnectionFactory c,ImageQueueProperties p,ImageCommandListener l) { return container(c,p,l,"work.parallel.q",p.getParallelConsumers(),p.isWorkerEnabled()); }
    @Bean public SimpleMessageListenerContainer imageSerialContainer(ConnectionFactory c,ImageQueueProperties p,ImageCommandListener l) { return container(c,p,l,"work.serial.q",1,p.isWorkerEnabled()); }
    @Bean public SimpleMessageListenerContainer imageAdvanceContainer(ConnectionFactory c,ImageQueueProperties p,ImageCommandListener l) { return container(c,p,l,"advance.q",1,p.isCoordinatorEnabled()); }
    @Bean public SimpleMessageListenerContainer imageRecoveryContainer(ConnectionFactory c,ImageQueueProperties p,ImageCommandListener l) { return container(c,p,l,"recovery.q",1,p.isWorkerEnabled()); }
}
