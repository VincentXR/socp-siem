package com.socp.alert.service;

import com.socp.alert.domain.OutboxEvent;
import com.socp.alert.persistence.repository.OutboxRepository;

import com.socp.platform.tenant.context.TenantContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(false);

        publisher().publish();

        assertEquals("PENDING", event.getStatus());
        assertNotNull(event.getCreatedAt());
        verify(kafkaPublisher, never()).sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload());
        verify(outboxRepository, never()).claim(eq(event.getId()), any(Instant.class), anyInt());
    }

    @Test
    void publishesAndMarksEachPendingEvent() {
        OutboxEvent event = event("alarm-2");
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt())).willReturn(1);
        given(outboxRepository.markPublished(eq(event.getId()), any(Instant.class))).willReturn(1);
        given(kafkaPublisher.sendAlarmEventAndAwait("alarm-2", "{\"id\":\"alarm-2\"}")).willReturn(true);

        publisher().publish();

        verify(kafkaPublisher).sendAlarmEventAndAwait("alarm-2", "{\"id\":\"alarm-2\"}");
        verify(outboxRepository).markPublished(eq(event.getId()), any(Instant.class));
    }

    @Test
    void brokerAcknowledgementFailureLeavesEventPending() {
        OutboxEvent event = event("alarm-3");
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt())).willReturn(1);
        given(kafkaPublisher.sendAlarmEventAndAwait("alarm-3", "{\"id\":\"alarm-3\"}")).willReturn(false);

        publisher().publish();

        verify(outboxRepository).scheduleRetry(eq(event.getId()), any(Instant.class),
                eq("Kafka broker did not acknowledge the event"), any(Instant.class));
        verify(outboxRepository, never()).markPublished(eq(event.getId()), any(Instant.class));
    }

    @Test
    void databaseReadFailureDoesNotBreakScheduledPublisher() {
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .willThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> publisher().publish());
        verify(kafkaPublisher, never()).isAvailable();
    }

    @Test
    void competingPublisherThatLosesClaimDoesNotPublish() {
        OutboxEvent event = event("alarm-4");
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt())).willReturn(0);

        publisher().publish();

        verify(kafkaPublisher, never()).sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload());
    }

    @Test
    void claimFailureCannotReleaseAnotherPublishersClaim() {
        OutboxEvent event = event("alarm-claim-error");
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt()))
                .willThrow(new IllegalStateException("database timeout"));

        publisher().publish();

        verify(outboxRepository, never()).scheduleRetry(eq(event.getId()), any(), any(), any());
        verify(kafkaPublisher, never()).sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload());
    }

    @Test
    void staleClaimsAreRecoveredBeforeTheNextBatch() {
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .willReturn(List.of());
        given(outboxRepository.recoverStale(any(Instant.class), any(Instant.class))).willReturn(3);

        publisher().publish();
        publisher.publish();

        verify(outboxRepository).recoverStale(any(Instant.class), any(Instant.class));
    }

    @Test
    void brokerFailureAtRetryLimitMovesRowToDead() {
        OutboxEvent event = event("alarm-dead");
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), eq(1))).willReturn(1);
        given(kafkaPublisher.sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload())).willReturn(false);
        publisher = new OutboxPublisher(outboxRepository, kafkaPublisher, null, 1, 1, 60_000L);

        publisher.publish();

        verify(outboxRepository).markDead(eq(event.getId()),
                eq("Kafka broker did not acknowledge the event"), any(Instant.class));
        verify(outboxRepository, never()).scheduleRetry(eq(event.getId()), any(), any(), any());
    }

    @Test
    void asyncTriggerRunsCrossTenantScanInsideSystemScope() {
        AtomicBoolean systemScope = new AtomicBoolean();
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.markExhausted(anyInt(), anyString(), any(Instant.class))).willAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            return 0;
        });
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of());
        publisher = publisher();

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        verify(outboxRepository, timeout(2_000)).markExhausted(anyInt(), anyString(), any(Instant.class));
        assertEquals(true, systemScope.get());
    }

    @Test
    void asyncDeliveryBindsEventTenantAndDoesNotDeadlockSingleWorker() {
        OutboxEvent event = event("alarm-async");
        AtomicBoolean tenantScope = new AtomicBoolean();
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt())).willAnswer(invocation -> {
            tenantScope.set("tenant-a".equals(TenantContext.get()) && !TenantContext.isSystemScope());
            return 1;
        });
        given(kafkaPublisher.sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload())).willReturn(true);
        given(outboxRepository.markPublished(eq(event.getId()), any(Instant.class))).willReturn(1);
        publisher = publisher();

        TenantContext.set("tenant-b");
        publisher.triggerAsync();

        verify(outboxRepository, timeout(2_000)).markPublished(eq(event.getId()), any(Instant.class));
        assertEquals(true, tenantScope.get());
    }

    @Test
    void drainsMoreThanThreeBatchesWithinOneWindow() {
        OutboxEvent event = event("alarm-backlog");
        List<OutboxEvent> fullBatch = java.util.Collections.nCopies(100, event);
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .willReturn(fullBatch, fullBatch, fullBatch, fullBatch, List.of());
        publisher = new OutboxPublisher(outboxRepository, kafkaPublisher, null,
                1, 12, 60_000L, 8, 10_000L);

        publisher.publish();

        verify(outboxRepository, org.mockito.Mockito.times(5))
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq("PENDING"), any(Instant.class));
    }

    private static OutboxEvent event(String id) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setTenantId("tenant-a");
        event.setAggregateId(id);
        event.setPayload("{\"id\":\"" + id + "\"}");
        event.setStatus("PENDING");
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now());
        event.setCreatedAt(Instant.now());
        return event;
    }
}
