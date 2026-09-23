package com.socp.search.config.persistence.store;

import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.persistence.repository.TenantCatalogEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantCatalogMutationTest {
    @Test void retriesTheEntireOperationAfterCommitConflict() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertEquals(TransactionDefinition.ISOLATION_SERIALIZABLE, definition.getIsolationLevel());
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            assertEquals(5, definition.getTimeout());
            return new SimpleTransactionStatus();
        });
        doThrow(new RuntimeException(new SQLException("retry", "40001"))).doNothing().when(manager).commit(any());
        var persistence = new TenantCatalogPersistence(mock(TenantCatalogEntryRepository.class), manager);
        var calls = new AtomicInteger();
        assertEquals(2, persistence.mutate(calls::incrementAndGet));
        verify(manager, times(2)).getTransaction(any());
    }

    @Test void retryExhaustionIsBoundedAndNonRetryableFailuresAreNotRepeated() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var persistence = new TenantCatalogPersistence(mock(TenantCatalogEntryRepository.class), manager);
        var calls = new AtomicInteger();
        ApiException failure = assertThrows(ApiException.class, () -> persistence.mutate(() -> {
            calls.incrementAndGet();
            throw new RuntimeException(new SQLException("unique collision", "23505"));
        }));
        assertEquals(503, failure.getCode());
        assertEquals(5, calls.get());
        var invalid = new IllegalArgumentException("invalid");
        assertSame(invalid, assertThrows(IllegalArgumentException.class, () -> persistence.mutate(() -> {
            calls.incrementAndGet(); throw invalid;
        })));
        assertEquals(6, calls.get());
    }
}
