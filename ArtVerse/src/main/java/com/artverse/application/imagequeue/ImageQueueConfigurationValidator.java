package com.artverse.application.imagequeue;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ImageQueueConfigurationValidator {
    private final ImageQueueProperties props;
    private final RabbitProperties rabbit;
    @PostConstruct void validate() {
        if(!props.isProducerEnabled()&&!props.isWorkerEnabled()&&!props.isCoordinatorEnabled())return;
        if(rabbit.getUsername()==null||rabbit.getUsername().isBlank()||"guest".equals(rabbit.getUsername())||rabbit.getPassword()==null||rabbit.getPassword().isBlank())
            throw new IllegalStateException("Image queue requires a dedicated RabbitMQ user/password; guest or blank credentials are forbidden");
        if(rabbit.getPublisherConfirmType()!=org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED||!rabbit.isPublisherReturns())
            throw new IllegalStateException("Image queue requires correlated confirms and publisher returns");
        if(props.getGlobalModelConcurrency()<1||props.getUserModelConcurrency()<1||props.getProfileModelConcurrency()<1||props.getParallelConsumers()<1)
            throw new IllegalStateException("Image model capacities must be positive");
        if(props.getExecutionRenew().multipliedBy(2).compareTo(props.getExecutionLease())>=0)
            throw new IllegalStateException("Image lease needs an independent renewal safety margin");
        if(props.getProviderTimeout().isNegative()||props.getProviderTimeout().isZero()
                ||props.getPublishConfirmTimeout().isNegative()||props.getPublishConfirmTimeout().isZero()
                ||props.getProviderTimeout().plus(props.getPostprocessReserve()).compareTo(props.getDeliveryProcessingTimeout())>=0
                ||props.getDrainTimeout().compareTo(props.getDeliveryProcessingTimeout())<=0)
            throw new IllegalStateException("Image time budgets need positive provider/confirm timeouts, postprocess headroom and a longer drain timeout");
    }
}
