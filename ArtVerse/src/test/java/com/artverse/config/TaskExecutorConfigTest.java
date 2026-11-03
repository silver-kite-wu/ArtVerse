package com.artverse.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorConfigTest {
    @Test
    void titleExecutorIsBoundedAndExistingUnqualifiedConsumersKeepTheirExecutor() {
        new ApplicationContextRunner().withUserConfiguration(TaskExecutorConfig.class, ConsumerConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            var titles = context.getBean("conversationTitleExecutor", ThreadPoolExecutor.class);
            assertThat(titles.getMaximumPoolSize()).isEqualTo(2);
            assertThat(titles.getQueue().remainingCapacity()).isEqualTo(32);
            assertThat(context.getBean(ExistingConsumer.class).executor())
                    .isSameAs(context.getBean("mangaGenerationExecutor")).isNotSameAs(titles);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerConfig {
        @Bean ExistingConsumer existingConsumer(ExecutorService executor) { return new ExistingConsumer(executor); }
    }
    record ExistingConsumer(ExecutorService executor) {}
}
