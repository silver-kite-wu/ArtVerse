package com.artverse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class TaskExecutorConfig {

    @Bean(name = "conversationTitleExecutor", destroyMethod = "shutdown")
    public ExecutorService conversationTitleExecutor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                Thread.ofVirtual().name("conversation-title-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "mangaGenerationExecutor", destroyMethod = "close")
    @Primary
    public ExecutorService mangaGenerationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
