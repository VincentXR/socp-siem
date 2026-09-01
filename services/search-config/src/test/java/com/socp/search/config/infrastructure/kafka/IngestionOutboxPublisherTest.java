package com.socp.search.config.infrastructure.kafka;

import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionOutboxPublisherTest {

    private IngestionOutboxPublisher publisher;

    @AfterEach
    void stopPublisher() {
        if (publisher != null) publisher.stop();
    }

    @Test
    void marksPublishedOnlyAfterBrokerAcknowledgement() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending(
                "event-1", "default|user|alice", "{}", "00-trace-01");
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), anyInt())).thenReturn(1);
        when(producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent()))
                .thenReturn(true);
        when(repository.markPublished(any(), any(Instant.class))).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(repository).markPublished(any(), any(Instant.class));
        verify(repository, never()).scheduleRetry(any(), any(Instant.class), any(), any(Instant.class));
    }

    @Test
    void releasesClaimWhenBrokerDoesNotAcknowledge() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending(
                "event-1", "route", "{}", null);
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), anyInt())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(false);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(repository).scheduleRetry(any(), any(Instant.class),
                org.mockito.ArgumentMatchers.eq("Kafka broker did not acknowledge the event"), any(Instant.class));
        verify(repository, never()).markPublished(any(), any(Instant.class));
    }

    @Test
    void competingInstanceThatLosesClaimDoesNotPublish() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending("event-1", "route", "{}", null);
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), anyInt())).thenReturn(0);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(producer, never()).sendAndAwait(any(), any(), any());
    }

    @Test
    void disabledKafkaDoesNotCreateAClaimReleaseWriteLoop() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(false);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(repository, never()).recoverStale(any(), any());
        verify(repository, never())
                .findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(any(), any());
    }

    @Test
    void throttlesStaleClaimRecoveryIndependentlyFromFastPolling() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class))).thenReturn(List.of());
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();
        publisher.publish();

        verify(repository).recoverStale(any(), any());
    }

    @Test
    void retryLimitMovesUnacknowledgedEventToDead() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending("event-dead", "route", "{}", null);
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class))).thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), org.mockito.ArgumentMatchers.eq(1))).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(false);
        publisher = new IngestionOutboxPublisher(repository, producer, null, 1, 1, 60_000L, 100, 2);

        publisher.publish();

        verify(repository).markDead(any(),
                org.mockito.ArgumentMatchers.eq("Kafka broker did not acknowledge the event"), any(Instant.class));
        verify(repository, never()).scheduleRetry(any(), any(), any(), any());
    }

    @Test
    void asyncTriggerRunsCrossTenantScanInsideSystemScope() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        AtomicBoolean systemScope = new AtomicBoolean();
        when(repository.markExhausted(anyInt(), anyString(), any(Instant.class))).thenAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            return 0;
        });
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class))).thenReturn(List.of());
        publisher = new IngestionOutboxPublisher(repository, producer);

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        verify(repository, org.mockito.Mockito.timeout(2_000))
                .markExhausted(anyInt(), anyString(), any(Instant.class));
        org.junit.jupiter.api.Assertions.assertTrue(systemScope.get());
    }

    @Test
    void asyncDeliveryBindsEventTenantAndDoesNotDeadlockSingleWorker() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        IngestionOutboxEvent event = pending("event-async", "route", "{}", null);
        AtomicBoolean tenantScope = new AtomicBoolean();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), anyInt())).thenAnswer(invocation -> {
            tenantScope.set("tenant-a".equals(TenantContext.get()) && !TenantContext.isSystemScope());
            return 1;
        });
        when(producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent()))
                .thenReturn(true);
        when(repository.markPublished(any(), any(Instant.class))).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer);

        TenantContext.set("tenant-b");
        publisher.triggerAsync();

        verify(repository, org.mockito.Mockito.timeout(2_000)).markPublished(any(), any(Instant.class));
        org.junit.jupiter.api.Assertions.assertTrue(tenantScope.get());
    }

    @Test
    void reportsClaimBatchSeparatelyFromTheTrueBacklogAndOldestAge() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class))).thenReturn(List.of());
        when(repository.countByStatus("PENDING")).thenReturn(827L);
        when(repository.findOldestCreatedAtByStatus("PENDING"))
                .thenReturn(Instant.now().minusSeconds(120));
        when(repository.countByStatus("DEAD")).thenReturn(3L);
        when(repository.findOldestUpdatedAtByStatus("DEAD"))
                .thenReturn(Instant.now().minusSeconds(300));
        publisher = new IngestionOutboxPublisher(repository, producer, registry,
                1, 12, 60_000L, 100, 2);

        publisher.publish();

        assertEquals(0.0, registry.get("socp.ingestion.outbox.claim.batch.size").gauge().value());
        assertEquals(827.0, registry.get("socp.ingestion.outbox.pending.count").gauge().value());
        double oldestAge = registry.get("socp.ingestion.outbox.oldest.pending.age.seconds").gauge().value();
        org.junit.jupiter.api.Assertions.assertTrue(oldestAge >= 119 && oldestAge <= 121);
        assertEquals(3.0, registry.get("socp.ingestion.outbox.dead.count").gauge().value());
        double oldestDeadAge = registry.get("socp.ingestion.outbox.oldest.dead.age.seconds").gauge().value();
        org.junit.jupiter.api.Assertions.assertTrue(oldestDeadAge >= 299 && oldestDeadAge <= 301);
    }

    @Test
    void refreshesBacklogMetricsWhenScanFails() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.markExhausted(anyInt(), anyString(), any(Instant.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(repository.countByStatus("PENDING")).thenReturn(11L);
        when(repository.countByStatus("DEAD")).thenReturn(2L);
        publisher = new IngestionOutboxPublisher(repository, producer, registry,
                1, 12, 60_000L, 100, 2);

        publisher.publish();

        assertEquals(11.0, registry.get("socp.ingestion.outbox.pending.count").gauge().value());
        assertEquals(2.0, registry.get("socp.ingestion.outbox.dead.count").gauge().value());
    }

    @Test
    void drainsMoreThanThreeClaimBatchesWithinOneWindow() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending("backlog", "route", "{}", null);
        List<IngestionOutboxEvent> fullBatch = java.util.Collections.nCopies(200, event);
        when(repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class)))
                .thenReturn(fullBatch, fullBatch, fullBatch, fullBatch, List.of());
        publisher = new IngestionOutboxPublisher(repository, producer, null,
                1, 12, 60_000L, 100, 2, 8, 10_000L);

        publisher.publish();

        verify(repository, org.mockito.Mockito.times(5))
                .findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                        org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class));
    }

    @Test
    void cleanupUsesConfiguredBoundedBatches() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(repository.deletePublishedBatchBefore(any(Instant.class), org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(100, 100, 100);
        publisher = new IngestionOutboxPublisher(repository, producer, null,
                1, 12, 60_000L, 100, 2);

        publisher.cleanupPublished();

        verify(repository, org.mockito.Mockito.times(2))
                .deletePublishedBatchBefore(any(Instant.class), org.mockito.ArgumentMatchers.eq(100));
    }

    private static IngestionOutboxEvent pending(String eventId, String routingKey,
                                                String payload, String traceparent) {
        IngestionOutboxEvent event = IngestionOutboxEvent.pending(eventId, routingKey, payload, traceparent);
        event.setTenantId("tenant-a");
        return event;
    }
}
