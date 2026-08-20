package com.socp.search.config.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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
        IngestionOutboxEvent event = IngestionOutboxEvent.pending(
                "event-1", "default|user|alice", "{}", "00-trace-01");
        when(repository.findTop200ByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class))).thenReturn(1);
        when(producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent()))
                .thenReturn(true);
        when(repository.markPublished(any(), any(Instant.class))).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(repository).markPublished(any(), any(Instant.class));
        verify(repository, never()).release(any(), any(Instant.class));
    }

    @Test
    void releasesClaimWhenBrokerDoesNotAcknowledge() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = IngestionOutboxEvent.pending(
                "event-1", "route", "{}", null);
        when(repository.findTop200ByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class))).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(false);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(repository).release(any(), any(Instant.class));
        verify(repository, never()).markPublished(any(), any(Instant.class));
    }

    @Test
    void competingInstanceThatLosesClaimDoesNotPublish() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = IngestionOutboxEvent.pending("event-1", "route", "{}", null);
        when(repository.findTop200ByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class))).thenReturn(0);
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
        verify(repository, never()).findTop200ByStatusOrderByCreatedAtAsc(any());
    }

    @Test
    void throttlesStaleClaimRecoveryIndependentlyFromFastPolling() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findTop200ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of());
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();
        publisher.publish();

        verify(repository).recoverStale(any(), any());
    }
}
