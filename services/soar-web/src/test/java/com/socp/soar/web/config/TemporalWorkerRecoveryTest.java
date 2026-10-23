package com.socp.soar.web.config;

import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TemporalWorkerRecoveryTest {
    @Test
    void retriesFailedStartupAndStopsRetryingOncePollersStarted() {
        WorkerFactory factory = mock(WorkerFactory.class);
        doThrow(new IllegalStateException("namespace unavailable")).doNothing().when(factory).start();
        TemporalWorkerRecovery recovery = new TemporalWorkerRecovery(factory, new TemporalProperties());

        recovery.recover();
        recovery.recover();
        when(factory.isStarted()).thenReturn(true);
        recovery.recover();

        verify(factory, times(2)).start();
        recovery.stop();
    }

    @Test
    void doesNotStartDisabledOrShuttingDownWorkers() {
        WorkerFactory factory = mock(WorkerFactory.class);
        TemporalProperties properties = new TemporalProperties();
        properties.setEnabled(false);
        TemporalWorkerRecovery recovery = new TemporalWorkerRecovery(factory, properties);
        recovery.recover();
        verifyNoInteractions(factory);

        properties.setEnabled(true);
        when(factory.isShutdown()).thenReturn(true);
        recovery.recover();
        verify(factory, never()).start();
        recovery.stop();
    }

    @Test
    void shutdownCancelsFurtherRecovery() {
        WorkerFactory factory = mock(WorkerFactory.class);
        TemporalWorkerRecovery recovery = new TemporalWorkerRecovery(factory, new TemporalProperties());
        recovery.start();
        recovery.stop();
        recovery.recover();
        verifyNoInteractions(factory);
    }
}
