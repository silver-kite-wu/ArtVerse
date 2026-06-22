package com.artverse.guard;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MangaGenerationConcurrencyGateTest {

    @Test
    void rejectsWhenNoPermitIsAvailableAndRecoversAfterRelease() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getMangaGeneration().setMaxConcurrentJobs(1);
        MangaGenerationConcurrencyGate gate = new MangaGenerationConcurrencyGate(properties);

        gate.acquireOrReject();

        assertThatThrownBy(gate::acquireOrReject)
                .isInstanceOf(BusinessException.class);

        gate.release();
        gate.acquireOrReject();
        gate.release();
        assertThat(gate.availablePermits()).isEqualTo(1);
    }
}
