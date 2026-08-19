package com.socp.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private AlertKafkaPublisher kafkaPublisher;

    @InjectMocks
    private OutboxPublisher publisher;

    @Test
    void keepsPendingEventsWhenKafkaIsUnavailable() {
        OutboxEvent event = event("alarm-1");
        given(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(false);

        publisher.publish();

        assertEquals("PENDING", event.getStatus());
        assertNotNull(event.getCreatedAt());
        verify(kafkaPublisher, never()).sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload());
        verify(outboxRepository, never()).save(event);
    }

    @Test
    void publishesAndMarksEachPendingEvent() {
        OutboxEvent event = event("alarm-2");
        given(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(kafkaPublisher.sendAlarmEventAndAwait("alarm-2", "{\"id\":\"alarm-2\"}")).willReturn(true);

        publisher.publish();

        verify(kafkaPublisher).sendAlarmEventAndAwait("alarm-2", "{\"id\":\"alarm-2\"}");
        verify(outboxRepository).save(event);
        assertEquals("PUBLISHED", event.getStatus());
        assertNotNull(event.getPublishedAt());
    }

    @Test
    void brokerAcknowledgementFailureLeavesEventPending() {
        OutboxEvent event = event("alarm-3");
        given(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(kafkaPublisher.sendAlarmEventAndAwait("alarm-3", "{\"id\":\"alarm-3\"}")).willReturn(false);

        publisher.publish();

        assertEquals("PENDING", event.getStatus());
        verify(outboxRepository, never()).save(event);
    }

    @Test
    void databaseReadFailureDoesNotBreakScheduledPublisher() {
        given(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING"))
                .willThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> publisher.publish());
        verify(kafkaPublisher, never()).isAvailable();
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
