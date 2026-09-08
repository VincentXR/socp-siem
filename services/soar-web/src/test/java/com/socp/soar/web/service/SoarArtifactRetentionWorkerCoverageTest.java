package com.socp.soar.web.service;

import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.artifact.SoarArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;

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
        given(artifacts.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class)))
                .willReturn(List.of(inlineArtifact("a"), inlineArtifact("b"), inlineArtifact("c")));
        given(artifacts.deleteByIds(any(), any(Instant.class))).willReturn(3);

        Instant before = Instant.now().minusSeconds(5);
        worker.tick();
        Instant after = Instant.now().plusSeconds(5);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(artifacts).findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff.capture());
        verify(artifacts).deleteByIds(eq(List.of("a", "b", "c")), any(Instant.class));
        assertThat(cutoff.getValue().isAfter(before)).isTrue();
        assertThat(cutoff.getValue().isBefore(after)).isTrue();
    }

    @Test
    void emptyRetentionPassIsANoOp() {
        given(artifacts.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class)))
                .willReturn(List.of());

        worker.tick();

        verify(artifacts).findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class));
        verify(artifacts, never()).deleteByIds(any(), any(Instant.class));
    }

    @Test
    void remoteDeletionFailureKeepsUnprocessedMetadataForRetry() {
        SoarArtifactStore store = org.mockito.Mockito.mock(SoarArtifactStore.class);
        given(artifacts.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class)))
                .willReturn(List.of(artifact("a"), artifact("b"), artifact("c")));
        org.mockito.Mockito.doAnswer(invocation -> {
            if ("s3://bucket/b".equals(invocation.getArgument(0, String.class))) {
                throw new IllegalStateException("object store down");
            }
            return null;
        }).when(store).delete(anyString());
        given(artifacts.deleteByIds(any(), any(Instant.class))).willReturn(1);

        new SoarArtifactRetentionWorker(artifacts, store).tick();

        verify(store).delete("s3://bucket/a");
        verify(store).delete("s3://bucket/b");
        verify(store, never()).delete("s3://bucket/c");
        verify(artifacts).deleteByIds(eq(List.of("a")), any(Instant.class));
    }

    @Test
    void remoteArtifactsAreRetainedWhenStoreBeanIsUnavailable() {
        given(artifacts.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class)))
                .willReturn(List.of(artifact("a")));

        worker.tick();

        verify(artifacts, never()).deleteByIds(any(), any(Instant.class));
    }

    private static SoarArtifactEntity artifact(String id) {
        SoarArtifactEntity row = new SoarArtifactEntity();
        row.setId(id);
        row.setStorageRef("s3://bucket/" + id);
        row.setExpiresAt(Instant.EPOCH);
        return row;
    }

    private static SoarArtifactEntity inlineArtifact(String id) {
        SoarArtifactEntity row = artifact(id);
        row.setStorageRef("db://soar-artifacts/" + id);
        row.setInlineJson("{}");
        return row;
    }
}
