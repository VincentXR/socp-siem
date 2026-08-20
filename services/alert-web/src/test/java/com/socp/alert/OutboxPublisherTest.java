package com.socp.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private AlertKafkaPublisher kafkaPublisher;

    private OutboxPublisher publisher;

    private OutboxPublisher publisher() {
        publisher = new OutboxPublisher(outboxRepository, kafkaPublisher);
        return publisher;
    }

    @AfterEach
    void stopPublisher() {
        if (publisher != null) publisher.stop();
    }

    @Test
    void keepsPendingEventsWhenKafkaIsUnavailable() {
        OutboxEvent event = event("alarm-1");
        given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING")).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(false);

        publisher().publish();

        assertEquals("PENDING", event.getStatus());
        assertNotNull(event.getCreatedAt());
        verify(kafkaPublisher, never()).sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload());
        verify(outboxRepository, never()).claim(eq(event.getId()), any(Instant.class));
    }

    @Test
    void publishesAndMarksEachPendingEvent() {
        OutboxEvent event = event("alarm-2");
        given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING")).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class))).willReturn(1);
        given(outboxRepository.markPublished(eq(event.getId()), any(Instant.class))).willReturn(1);
        given(kafkaPublisher.sendAlarmEventAndAwait("alarm-2", "{\"id\":\"alarm-2\"}")).willReturn(true);

        publisher().publish();

        verify(kafkaPublisher).sendAlarmEventAndAwait("alarm-2", "{\"id\":\"alarm-2\"}");
        verify(outboxRepository).markPublished(eq(event.getId()), any(Instant.class));
    }

    @Test
    void brokerAcknowledgementFailureLeavesEventPending() {
        OutboxEvent event = event("alarm-3");
        given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING")).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class))).willReturn(1);
        given(kafkaPublisher.sendAlarmEventAndAwait("alarm-3", "{\"id\":\"alarm-3\"}")).willReturn(false);

        publisher().publish();

        verify(outboxRepository).release(eq(event.getId()), any(Instant.class));
        verify(outboxRepository, never()).markPublished(eq(event.getId()), any(Instant.class));
    }

    @Test
    void databaseReadFailureDoesNotBreakScheduledPublisher() {
        given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING"))
                .willThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> publisher().publish());
        verify(kafkaPublisher, never()).isAvailable();
    }

    @Test
    void competingPublisherThatLosesClaimDoesNotPublish() {
        OutboxEvent event = event("alarm-4");
        given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING"))
                .willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class))).willReturn(0);

        publisher().publish();

        verify(kafkaPublisher, never()).sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload());
    }

    @Test
    void claimFailureCannotReleaseAnotherPublishersClaim() {
        OutboxEvent event = event("alarm-claim-error");
        given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING"))
                .willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class)))
                .willThrow(new IllegalStateException("database timeout"));

        publisher().publish();

        verify(outboxRepository, never()).release(eq(event.getId()), any(Instant.class));
        verify(kafkaPublisher, never()).sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload());
    }

    @Test
    void staleClaimsAreRecoveredBeforeTheNextBatch() {
        given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING"))
                .willReturn(List.of());
        given(outboxRepository.recoverStale(any(Instant.class), any(Instant.class))).willReturn(3);

        publisher().publish();
        publisher.publish();

        verify(outboxRepository).recoverStale(any(Instant.class), any(Instant.class));
    }

    private static OutboxEvent event(String id) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setAggregateId(id);
        event.setPayload("{\"id\":\"" + id + "\"}");
        event.setStatus("PENDING");
        event.setCreatedAt(Instant.now());
        return event;
    }
}
