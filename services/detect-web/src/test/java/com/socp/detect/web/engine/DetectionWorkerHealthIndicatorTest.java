package com.socp.detect.web.engine;

import com.socp.detect.web.service.DetectEngineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetectionWorkerHealthIndicatorTest {
    @Test void aDeadConsumerFailsHealthEvenWhenTheHttpEngineIsReady() {
        assertEquals(Status.DOWN, health(true, false));
    }

    @Test void aLiveConsumerCannotHideIncompleteRecovery() {
        assertEquals(Status.DOWN, health(false, true));
    }

    @Test void aRecoveredLiveWorkerIsHealthy() {
        assertEquals(Status.UP, health(true, true));
    }

    @Test void anApiOnlyProcessDoesNotRequireAConsumer() {
        DetectEngineService engine = mock(DetectEngineService.class);
        when(engine.isReady()).thenReturn(true);
        when(engine.recoveryStatus()).thenReturn(DetectEngineService.RecoveryStatus.READY);
        @SuppressWarnings("unchecked") ObjectProvider<KafkaEventConsumer> consumers = mock(ObjectProvider.class);
        assertEquals(Status.UP, new DetectionWorkerHealthIndicator(engine, consumers).health().getStatus());
    }

    private Status health(boolean ready, boolean pollerAlive) {
        DetectEngineService engine = mock(DetectEngineService.class);
        when(engine.isReady()).thenReturn(ready);
        when(engine.recoveryStatus()).thenReturn(ready ? DetectEngineService.RecoveryStatus.READY
                : DetectEngineService.RecoveryStatus.DEGRADED);
        KafkaEventConsumer consumer = mock(KafkaEventConsumer.class);
        when(consumer.isHealthy()).thenReturn(pollerAlive);
        @SuppressWarnings("unchecked") ObjectProvider<KafkaEventConsumer> consumers = mock(ObjectProvider.class);
        when(consumers.getIfAvailable()).thenReturn(consumer);
        return new DetectionWorkerHealthIndicator(engine, consumers).health().getStatus();
    }
}
