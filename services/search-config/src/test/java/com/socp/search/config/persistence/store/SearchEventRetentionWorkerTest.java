package com.socp.search.config.persistence.store;

import com.socp.search.config.persistence.repository.SearchEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchEventRetentionWorkerTest {

    @Test
    void cleanupIsBatchBoundedAndStopsOnShortBatch() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.deleteRetainedBatchBefore(any(Instant.class), eq(100)))
                .thenReturn(100, 100, 12);
        SearchEventRetentionWorker worker =
                new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 100, 10,
                        (io.micrometer.core.instrument.MeterRegistry) null);

        worker.cleanupExpiredEvents();

        verify(repository, times(3)).deleteRetainedBatchBefore(any(Instant.class), eq(100));
    }

    @Test
    void cleanupRespectsMaximumBatchCount() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.deleteRetainedBatchBefore(any(Instant.class), eq(50)))
                .thenReturn(50);
        SearchEventRetentionWorker worker =
                new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 50, 2,
                        (io.micrometer.core.instrument.MeterRegistry) null);

        worker.cleanupExpiredEvents();

        verify(repository, times(2)).deleteRetainedBatchBefore(any(Instant.class), eq(50));
    }

    @Test
    void cleanupContinuesAcrossBoundedRoundsWhileBacklogRemains() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.deleteRetainedBatchBefore(any(Instant.class), eq(100)))
                .thenReturn(100);
        when(repository.findOldestDeletableCreatedAtBefore(any(Instant.class)))
                .thenReturn(Optional.of(Instant.now().minusSeconds(90L * 86_400L)),
                        Optional.of(Instant.now().minusSeconds(60L * 86_400L)),
                        Optional.empty());

        SearchEventRetentionWorker worker =
                new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 100, 2,
                        5_000L, 0L, (io.micrometer.core.instrument.MeterRegistry) null);

        worker.cleanupExpiredEvents();

        verify(repository, times(6)).deleteRetainedBatchBefore(any(Instant.class), eq(100));
        verify(repository, times(3)).findOldestDeletableCreatedAtBefore(any(Instant.class));
    }


    @Test
    void cleanupPublishesDeletionAndZeroLagMetrics() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.deleteRetainedBatchBefore(any(Instant.class), eq(100)))
                .thenReturn(12);
        when(repository.findOldestDeletableCreatedAtBefore(any(Instant.class)))
                .thenReturn(Optional.empty());
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        SearchEventRetentionWorker worker =
                new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 100, 2,
                        5_000L, 0L, metrics);

        worker.cleanupExpiredEvents();

        assertEquals(12.0d, metrics.get("socp.search.event.retention")
                .tag("outcome", "deleted").counter().count());
        assertEquals(0.0d, metrics.get("socp.search.event.retention.oldest.eligible.age.seconds")
                .gauge().value());
        assertEquals(0.0d, metrics.get("socp.search.event.retention.lag.seconds")
                .gauge().value());
        assertTrue(metrics.get("socp.search.event.retention.cleanup.duration.ms")
                .gauge().value() >= 0.0d);
        assertTrue(metrics.get("socp.search.event.retention.delete.rate.rows_per_second")
                .gauge().value() >= 0.0d);
    }

    @Test
    void cleanupTracksOldestEligibleLagAcrossCatchupRounds() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.deleteRetainedBatchBefore(any(Instant.class), eq(100)))
                .thenReturn(0);
        Instant old = Instant.now().minusSeconds(40L * 86_400L);
        when(repository.findOldestDeletableCreatedAtBefore(any(Instant.class)))
                .thenReturn(Optional.of(old), Optional.empty());
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        SearchEventRetentionWorker worker =
                new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 100, 1,
                        5_000L, 0L, metrics);

        worker.cleanupExpiredEvents();

        verify(repository, times(2)).deleteRetainedBatchBefore(any(Instant.class), eq(100));
        verify(repository, times(2)).findOldestDeletableCreatedAtBefore(any(Instant.class));
        assertEquals(0.0d, metrics.get("socp.search.event.retention.oldest.eligible.age.seconds")
                .gauge().value());
        assertEquals(0.0d, metrics.get("socp.search.event.retention.lag.seconds")
                .gauge().value());
    }

    @Test
    void cleanupRecordsFailureWithoutEscapingScheduledInvocation() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.deleteRetainedBatchBefore(any(Instant.class), eq(100)))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        SearchEventRetentionWorker worker =
                new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 100, 2,
                        5_000L, 0L, metrics);

        worker.cleanupExpiredEvents();

        assertEquals(1.0d, metrics.get("socp.search.event.retention")
                .tag("outcome", "failure").counter().count());
        assertTrue(metrics.get("socp.search.event.retention.cleanup.duration.ms")
                .gauge().value() >= 0.0d);
    }

    @Test
    void interruptedCatchupStopsCleanlyAndRestoresInterruptFlag() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.deleteRetainedBatchBefore(any(Instant.class), eq(100)))
                .thenReturn(0);
        when(repository.findOldestDeletableCreatedAtBefore(any(Instant.class)))
                .thenReturn(Optional.of(Instant.now().minusSeconds(40L * 86_400L)));

        SearchEventRetentionWorker worker =
                new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 100, 1,
                        5_000L, 10L, (io.micrometer.core.instrument.MeterRegistry) null);

        Thread.currentThread().interrupt();
        try {
            worker.cleanupExpiredEvents();
            assertTrue(Thread.currentThread().isInterrupted());
            verify(repository, times(1)).deleteRetainedBatchBefore(any(Instant.class), eq(100));
            verify(repository, times(1)).findOldestDeletableCreatedAtBefore(any(Instant.class));
        } finally {
            Thread.interrupted();
        }
    }



    @Test
    void productiveCatchupRoundUsesConfiguredPauseBeforeContinuing() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.deleteRetainedBatchBefore(any(Instant.class), eq(100)))
                .thenReturn(100, 0);
        when(repository.findOldestDeletableCreatedAtBefore(any(Instant.class)))
                .thenReturn(Optional.of(Instant.now().minusSeconds(40L * 86_400L)),
                        Optional.empty());

        SearchEventRetentionWorker worker =
                new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 100, 1,
                        5_000L, 1L, (io.micrometer.core.instrument.MeterRegistry) null);

        worker.cleanupExpiredEvents();

        verify(repository, times(2)).deleteRetainedBatchBefore(any(Instant.class), eq(100));
        verify(repository, times(2)).findOldestDeletableCreatedAtBefore(any(Instant.class));
    }

    @Test
    void springConstructorUsesAvailableMeterRegistry() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(metrics);

        new SearchEventRetentionWorker(repository, 30L * 86_400_000L, 100, 2,
                5_000L, 0L, provider);

        assertEquals(0.0d, metrics.get("socp.search.event.retention.lag.seconds")
                .gauge().value());
        assertEquals(0.0d, metrics.get("socp.search.event.retention.cleanup.duration.ms")
                .gauge().value());
    }

}
