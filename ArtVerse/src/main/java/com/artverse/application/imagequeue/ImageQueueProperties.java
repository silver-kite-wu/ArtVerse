package com.artverse.application.imagequeue;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component @ConfigurationProperties("artverse.image-queue") @Getter @Setter
public class ImageQueueProperties {
    private boolean producerEnabled = false;
    private boolean workerEnabled = false;
    private boolean coordinatorEnabled = false;
    private int globalModelConcurrency = 4;
    private int userModelConcurrency = 4;
    private int profileModelConcurrency = 4;
    private int parallelConsumers = 4;
    private Duration publishConfirmTimeout = Duration.ofSeconds(5);
    private Duration executionLease = Duration.ofSeconds(120);
    private Duration executionRenew = Duration.ofSeconds(30);
    private Duration providerTimeout = Duration.ofSeconds(600);
    private Duration operationTotalTimeout = Duration.ofMinutes(60);
    private Duration deliveryProcessingTimeout = Duration.ofMinutes(15);
    private Duration postprocessReserve = Duration.ofSeconds(180);
    private Duration drainTimeout = Duration.ofMinutes(16);
    private Duration terminalRetention = Duration.ofDays(30);
}
