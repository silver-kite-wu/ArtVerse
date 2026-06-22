package com.artverse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class TaskExecutorConfig {

    @Bean(name = "mangaGenerationExecutor", destroyMethod = "close")
    public ExecutorService mangaGenerationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
