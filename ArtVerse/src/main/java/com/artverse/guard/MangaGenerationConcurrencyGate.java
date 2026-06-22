package com.artverse.guard;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
public class MangaGenerationConcurrencyGate {

    private static final String BUSY_MESSAGE = "当前漫画生成任务较多，请稍后再试";

    private final Semaphore permits;

    public MangaGenerationConcurrencyGate(ArtVerseProperties properties) {
        int maxConcurrentJobs = Math.max(1, properties.getMangaGeneration().getMaxConcurrentJobs());
        this.permits = new Semaphore(maxConcurrentJobs);
    }

    public void acquireOrReject() {
        if (!permits.tryAcquire()) {
            throw new BusinessException(429, BUSY_MESSAGE);
        }
    }

    public void release() {
        permits.release();
    }

    int availablePermits() {
        return permits.availablePermits();
    }
}
