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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        verify(outboxRepository, never()).claim(eq(event.getId()), any(Instant.class), anyInt(), anyInt(), anyString());
    }

    @Test
    void publishesAndMarksEachPendingEvent() {
        OutboxEvent event = event("alarm-2");
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(outboxRepository.markPublished(eq(event.getId()), any(Instant.class), anyString())).willReturn(1);
        given(kafkaPublisher.sendAlarmEventAndAwait("alarm-2", "{\"id\":\"alarm-2\"}")).willReturn(true);

        publisher().publish();

        verify(kafkaPublisher).sendAlarmEventAndAwait("alarm-2", "{\"id\":\"alarm-2\"}");
        verify(outboxRepository).markPublished(eq(event.getId()), any(Instant.class), anyString());
    }

    @Test
    void brokerAcknowledgementFailureLeavesEventPending() {
        OutboxEvent event = event("alarm-3");
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(kafkaPublisher.sendAlarmEventAndAwait("alarm-3", "{\"id\":\"alarm-3\"}")).willReturn(false);

        publisher().publish();

        verify(outboxRepository).scheduleRetry(eq(event.getId()), any(Instant.class),
                eq("Kafka broker did not acknowledge the event"), any(Instant.class), anyString());
        verify(outboxRepository, never()).markPublished(eq(event.getId()), any(Instant.class), anyString());
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
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(0);

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
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt(), anyInt(), anyString()))
                .willThrow(new IllegalStateException("database timeout"));

        publisher().publish();

        verify(outboxRepository, never()).scheduleRetry(eq(event.getId()), any(), any(), any(), anyString());
        verify(kafkaPublisher, never()).sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload());
    }

    @Test
    void staleClaimsAreRecoveredBeforeTheNextBatch() {
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .willReturn(List.of());
        given(outboxRepository.recoverStaleBatch(any(Instant.class), any(Instant.class), eq(100))).willReturn(3);

        publisher().publish();
        publisher.publish();

        verify(outboxRepository).recoverStaleBatch(any(Instant.class), any(Instant.class), eq(100));
    }

    @Test
    void brokerFailureAtRetryLimitMovesRowToDead() {
        OutboxEvent event = event("alarm-dead");
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), eq(1), anyInt(), anyString())).willReturn(1);
        given(kafkaPublisher.sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload())).willReturn(false);
        publisher = new OutboxPublisher(outboxRepository, kafkaPublisher, null, 1, 1, 60_000L);

        publisher.publish();

        verify(outboxRepository).markDead(eq(event.getId()),
                eq("Kafka broker did not acknowledge the event"), any(Instant.class), anyString());
        verify(outboxRepository, never()).scheduleRetry(eq(event.getId()), any(), any(), any(), anyString());
    }

    @Test
    void asyncTriggerRunsCrossTenantScanInsideSystemScope() throws InterruptedException {
        AtomicBoolean systemScope = new AtomicBoolean();
        CountDownLatch scopeCaptured = new CountDownLatch(1);
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100))).willAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            scopeCaptured.countDown();
            return 0;
        });
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of());
        publisher = publisher();

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        assertEquals(true, scopeCaptured.await(2, TimeUnit.SECONDS));
        verify(outboxRepository, timeout(2_000)).markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100));
        assertEquals(true, systemScope.get());
    }

    @Test
    void asyncDeliveryBindsEventTenantAndDoesNotDeadlockSingleWorker() {
        OutboxEvent event = event("alarm-async");
        AtomicBoolean tenantScope = new AtomicBoolean();
        given(kafkaPublisher.isAvailable()).willReturn(true);
        given(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(outboxRepository.claim(eq(event.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willAnswer(invocation -> {
            tenantScope.set("tenant-a".equals(TenantContext.get()) && !TenantContext.isSystemScope());
            return 1;
        });
        given(kafkaPublisher.sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload())).willReturn(true);
        given(outboxRepository.markPublished(eq(event.getId()), any(Instant.class), anyString())).willReturn(1);
        publisher = publisher();

        TenantContext.set("tenant-b");
        publisher.triggerAsync();

        verify(outboxRepository, timeout(2_000)).markPublished(eq(event.getId()), any(Instant.class), anyString());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "retry", "dead"})
    void completionRetainsTheExactClaimToken(String outcome) {
        org.mockito.Mockito.when(kafkaPublisher.isAvailable()).thenReturn(true);
        publisher = new OutboxPublisher(outboxRepository, kafkaPublisher, null, 1, 2, 60000);
        OutboxEvent row = event("alarm-token");
        row.setAttempts("dead".equals(outcome) ? 1 : 0);
        org.mockito.Mockito.when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any())).thenReturn(List.of(row));
        org.mockito.Mockito.when(outboxRepository.claim(eq(row.getId()), any(), eq(2), eq(row.getAttempts()), anyString())).thenReturn(1);
        org.mockito.Mockito.when(kafkaPublisher.sendAlarmEventAndAwait(any(), any())).thenReturn("success".equals(outcome));
        TenantContext.runAsSystem(publisher::publish);
        var owner = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).claim(eq(row.getId()), any(), eq(2), eq(row.getAttempts()), owner.capture());
        java.util.UUID.fromString(owner.getValue());
        switch (outcome) {
            case "success" -> verify(outboxRepository).markPublished(eq(row.getId()), any(), eq(owner.getValue()));
            case "retry" -> verify(outboxRepository).scheduleRetry(eq(row.getId()), any(), any(), any(), eq(owner.getValue()));
            case "dead" -> verify(outboxRepository).markDead(eq(row.getId()), any(), any(), eq(owner.getValue()));
            default -> throw new AssertionError(outcome);
        }
    }

    @Test
    void overlappingDrainsScanOnlyOnce() throws Exception {
        publisher = new OutboxPublisher(outboxRepository, kafkaPublisher, null, 1, 2, 60000);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            return List.of();
        });
        var first = java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::publish));
        try {
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::publish))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS);
            verify(outboxRepository).findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any());
        } finally { release.countDown(); }
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
