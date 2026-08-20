package com.socp.search.config.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class IngestionCommitServiceTest {

    @AfterEach
    void cleanupSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void persistsEventAndOutboxBeforeUpdatingTheSearchWindowAfterCommit() {
        SearchEventRepository events = mock(SearchEventRepository.class);
        IngestionOutboxRepository outbox = mock(IngestionOutboxRepository.class);
        SearchStore store = mock(SearchStore.class);
        IngestionCommitService service = new IngestionCommitService(events, outbox, store);
        SearchEvent event = new SearchEvent("event-1", Instant.parse("2026-08-21T00:00:00Z"),
                "auth", "host-1", "HIGH", "failure",
                Map.of("tenant_id", "default", "user", "alice"), Map.of());

        TransactionSynchronizationManager.initSynchronization();
        service.commit(List.of(event));

        verify(events).saveAll(anyList());
        verify(outbox).saveAll(anyList());
        verify(store, never()).rememberBatch(anyList());

        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(store).rememberBatch(List.of(event));
    }
}
