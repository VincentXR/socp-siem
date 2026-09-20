package com.socp.search.config.persistence.store;

import com.socp.search.config.persistence.repository.SearchEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
}
