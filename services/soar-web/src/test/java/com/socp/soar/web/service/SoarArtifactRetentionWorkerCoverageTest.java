package com.socp.soar.web.service;

import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SoarArtifactRetentionWorkerCoverageTest {

    @Mock
    private SoarArtifactRepository artifacts;

    private SoarArtifactRetentionWorker worker;

    @BeforeEach
    void setUp() {
        worker = new SoarArtifactRetentionWorker(artifacts);
    }

    @Test
    void expiredArtifactsAreDeletedWithCurrentTimeCutoff() {
        given(artifacts.deleteExpired(any(Instant.class))).willReturn(3);

        Instant before = Instant.now().minusSeconds(5);
        worker.tick();
        Instant after = Instant.now().plusSeconds(5);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(artifacts).deleteExpired(cutoff.capture());
        assertThat(cutoff.getValue().isAfter(before)).isTrue();
        assertThat(cutoff.getValue().isBefore(after)).isTrue();
    }

    @Test
    void emptyRetentionPassIsANoOp() {
        given(artifacts.deleteExpired(any(Instant.class))).willReturn(0);

        worker.tick();

        verify(artifacts).deleteExpired(any(Instant.class));
    }
}
