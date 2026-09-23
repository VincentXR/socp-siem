package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DetectionAlertOutboxPublisherTest {

    @Mock
    private DetectionAlertOutboxRepository repository;

    @Mock
    private AlertClient alertClient;

    @Mock
    private AlarmKafkaProducer alarmProducer;

    @Test
    void failedAlertWebDeliveryRemainsPendingForRetry() {
        allowFailureWrite();
        DetectionAlertOutboxEntity event = event("alert-1");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-1"), eq("PENDING"), anyInt(), anyString(), any(Instant.class), anyInt())).willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(failure());

        DetectionAlertOutboxPublisher publisher = publisher();
        publisher.publishDue();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getAttempts());
        verify(alarmProducer, never()).sendAndAwait(any(), anyString(), any());
    }

    @Test
    void acceptedAlertIsThenPublishedToTheOriginalAlarmStream() {
        allowPublishedWrite();
        DetectionAlertOutboxEntity event = event("alert-2");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-2"), eq("PENDING"), anyInt(), anyString(), any(Instant.class), anyInt())).willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(success());
        given(alarmProducer.sendAndAwait(any(), eq("alert-2"), any())).willAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return true;
        });

        DetectionAlertOutboxPublisher publisher = publisher();
        publisher.publishDue();

        assertEquals("PUBLISHED", event.getStatus());
        verify(alertClient).forwardAlarm(argThat(payload ->
                payload.contains("\"id\":\"alert-2\"")
                        && payload.contains("\"detectionOutboxClaimedAt\"")));
        verify(alarmProducer).sendAndAwait(any(), eq("alert-2"), any());
        var order = org.mockito.Mockito.inOrder(alertClient, alarmProducer, repository);
        order.verify(alertClient).forwardAlarm(anyString());
        order.verify(alarmProducer).sendAndAwait(any(), eq("alert-2"), any());
        order.verify(repository).markPublished(eq("alert-2"), eq(event.getClaimToken()),
                any(Instant.class), any(Instant.class));
        verify(repository, never()).claim(eq("alert-2"), eq("DELIVERED"), anyInt(), anyString(), any(Instant.class), anyInt());
    }

    @Test
    void duplicatePublisherCannotClaimTheSameStage() {
        DetectionAlertOutboxEntity event = event("alert-3");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-3"), eq("PENDING"), anyInt(), anyString(), any(Instant.class), anyInt())).willReturn(0);

        DetectionAlertOutboxPublisher publisher = publisher();
        publisher.publishDue();

        verify(alertClient, never()).forwardAlarm(anyString());
        verify(alarmProducer, never()).sendAndAwait(any(), anyString(), any());
    }

    @Test
    void originalAlarmFailureKeepsTheAlertInTheSecondStage() {
        allowFailureWrite();
        DetectionAlertOutboxEntity event = event("alert-4");
        event.setStatus("DELIVERED");
        event.setDeliveredAt(Instant.now());
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of());
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("DELIVERED"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-4"), eq("DELIVERED"), anyInt(), anyString(), any(Instant.class), anyInt())).willReturn(1);
        given(alarmProducer.sendAndAwait(any(), eq("alert-4"), any())).willReturn(false);

        publisher().publishDue();

        assertEquals("DELIVERED", event.getStatus());
        assertEquals(1, event.getAttempts());
        verify(alertClient, never()).forwardAlarm(anyString());
    }

    @Test
    void secondStageFailureAfterHttpSuccessPersistsDeliveredRecoveryPoint() {
        allowFailureWrite();
        DetectionAlertOutboxEntity event = event("alert-inline-failure");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-inline-failure"), eq("PENDING"), anyInt(), anyString(), any(Instant.class), anyInt()))
                .willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(success());
        given(alarmProducer.sendAndAwait(any(), eq("alert-inline-failure"), any())).willReturn(false);

        publisher().publishDue();

        assertEquals("DELIVERED", event.getStatus());
        assertEquals(1, event.getAttempts());
        assertTrue(event.alertDelivered());
        verify(repository, never()).claim(
                eq("alert-inline-failure"), eq("DELIVERED"), anyInt(), anyString(), any(Instant.class), anyInt());
    }

    @Test
    void retryLimitMovesFailedAlertHandoffToDead() {
        allowFailureWrite();
        DetectionAlertOutboxEntity event = event("alert-dead");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-dead"), eq("PENDING"), anyInt(), anyString(), any(Instant.class), anyInt())).willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(failure());

        DetectionAlertOutboxPublisher publisher = new DetectionAlertOutboxPublisher(
                repository, alertClient, alarmProducer, null, 1, 1, 60_000L);
        try {
            publisher.publishDue();
        } finally {
            publisher.stopDeliveryExecutor();
        }

        assertEquals("DEAD", event.getStatus());
        assertEquals(1, event.getAttempts());
        verify(repository).markFailed(eq(event.getAlertId()), eq(event.getClaimToken()), eq("DEAD"),
                isNull(), any(Instant.class), anyString(), any(Instant.class));
    }

    @Test
    void recoveryAndRetryLimitScansUseBoundedBatches() {
        publisher().publishDue();

        verify(repository).recoverStaleBatch(any(Instant.class), any(Instant.class), eq(12), eq(100));
        verify(repository).markExhaustedBatch(eq(12), any(Instant.class), eq(100));
        verify(repository, never()).save(any(DetectionAlertOutboxEntity.class));
    }

    @Test
    void pendingAlertDeliveryUsesConfiguredBoundedConcurrency() {
        allowPublishedWrite();
        List<DetectionAlertOutboxEntity> events = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> event("parallel-" + i))
                .toList();
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(events);
        given(repository.claim(anyString(), eq("PENDING"), anyInt(), anyString(), any(Instant.class), anyInt())).willReturn(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        given(alertClient.forwardAlarm(anyString())).willAnswer(invocation -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(20);
                return success();
            } finally {
                active.decrementAndGet();
            }
        });
        given(alarmProducer.sendAndAwait(any(), anyString(), any())).willReturn(true);

        DetectionAlertOutboxPublisher publisher = new DetectionAlertOutboxPublisher(
                repository, alertClient, alarmProducer, null, 4);
        try {
            publisher.publishDue();
        } finally {
            publisher.stopDeliveryExecutor();
        }

        assertTrue(peak.get() > 1, "delivery should no longer be globally serial");
        assertTrue(peak.get() <= 4, "delivery must respect the configured bound");
    }

    @Test
    void asyncTriggerRunsCrossTenantScanInsideSystemScope() {
        AtomicBoolean systemScope = new AtomicBoolean();
        given(repository.markExhaustedBatch(anyInt(), any(Instant.class), eq(100))).willAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            return 0;
        });
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                anyString(), any(Instant.class))).willReturn(List.of());

        DetectionAlertOutboxPublisher publisher = publisher();
        try {
            TenantContext.set("tenant-a");
            publisher.triggerAsync();

            verify(repository, org.mockito.Mockito.timeout(2_000))
                    .markExhaustedBatch(anyInt(), any(Instant.class), eq(100));
            assertTrue(systemScope.get());
        } finally {
            publisher.stopDeliveryExecutor();
            TenantContext.clear();
        }
    }

    @Test
    void drainsMoreThanThreePendingBatchesWithinOneWindow() {
        DetectionAlertOutboxEntity event = event("alert-backlog");
        List<DetectionAlertOutboxEntity> fullBatch = java.util.Collections.nCopies(100, event);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .willReturn(fullBatch, fullBatch, fullBatch, List.of(event));
        DetectionAlertOutboxPublisher publisher = new DetectionAlertOutboxPublisher(
                repository, alertClient, alarmProducer, null, 1, 12, 60_000L, 8, 10_000L);
        try {
            publisher.publishDue();
        } finally {
            publisher.stopDeliveryExecutor();
        }

        verify(repository, org.mockito.Mockito.times(4))
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq("PENDING"), any(Instant.class));
    }

    @Test
    void pendingBacklogCannotStarveTheDeliveredStage() {
        allowPublishedWrite();
        DetectionAlertOutboxEntity pending = event("pending-backlog");
        DetectionAlertOutboxEntity delivered = event("delivered-backlog");
        delivered.setStatus("DELIVERED");
        delivered.setDeliveredAt(Instant.now());
        List<DetectionAlertOutboxEntity> fullBatch = java.util.Collections.nCopies(100, pending);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(fullBatch);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("DELIVERED"), any(Instant.class))).willReturn(List.of(delivered));
        given(repository.claim(eq("pending-backlog"), eq("PENDING"),
                anyInt(), anyString(), any(Instant.class), eq(12))).willReturn(0);
        given(repository.claim(eq("delivered-backlog"), eq("DELIVERED"),
                anyInt(), anyString(), any(Instant.class), eq(12))).willReturn(1);
        given(alarmProducer.sendAndAwait(any(), eq("delivered-backlog"), any())).willReturn(true);
        DetectionAlertOutboxPublisher publisher = new DetectionAlertOutboxPublisher(
                repository, alertClient, alarmProducer, null, 1, 12, 60_000L, 8, 10_000L);
        try {
            publisher.publishDue();
        } finally {
            publisher.stopDeliveryExecutor();
        }

        verify(alarmProducer).sendAndAwait(any(), eq("delivered-backlog"), any());
        assertEquals("PUBLISHED", delivered.getStatus());
    }

    @Test
    void lostClaimAfterBrokerAcknowledgementDoesNotWriteDetachedState() {
        var event = event("expired-owner");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq(event.getAlertId()), eq("PENDING"), eq(0), anyString(),
                any(Instant.class), eq(12))).willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(success());
        given(alarmProducer.sendAndAwait(any(), eq(event.getAlertId()), any())).willReturn(true);

        publisher().publishDue();

        verify(repository).markPublished(eq(event.getAlertId()), eq(event.getClaimToken()),
                any(Instant.class), any(Instant.class));
        verify(repository, never()).save(any(DetectionAlertOutboxEntity.class));
        verify(repository, never()).markFailed(anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void queuedDeliveryDoesNotClaimBeforeCapacityOrAfterDrainDeadline() throws Exception {
        allowPublishedWrite();
        var first = event("capacity-first");
        var second = event("capacity-second");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(first, second));
        given(repository.claim(eq(first.getAlertId()), eq("PENDING"), eq(0), anyString(),
                any(Instant.class), eq(12))).willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return success();
        });
        given(alarmProducer.sendAndAwait(any(), eq(first.getAlertId()), any())).willReturn(true);
        var publisher = new DetectionAlertOutboxPublisher(repository, alertClient, alarmProducer,
                null, 1, 12, 60_000L, 8, 200L);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var drain = executor.submit(publisher::publishDue);
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                // A scheduled/triggered overlap must not add another local backlog.
                publisher.publishDue();
                verify(repository, org.mockito.Mockito.times(1))
                        .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                                eq("PENDING"), any(Instant.class));
                verify(repository, never()).claim(eq(second.getAlertId()), anyString(), anyInt(),
                        anyString(), any(Instant.class), anyInt());
                Thread.sleep(250);
            } finally {
                release.countDown();
            }
            drain.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            publisher.stopDeliveryExecutor();
        }
        verify(repository, never()).claim(eq(second.getAlertId()), anyString(), anyInt(),
                anyString(), any(Instant.class), anyInt());
    }

    private final List<DetectionAlertOutboxPublisher> publishers = new java.util.ArrayList<>();

    @AfterEach
    void closePublishers() {
        publishers.forEach(DetectionAlertOutboxPublisher::stopDeliveryExecutor);
    }

    private DetectionAlertOutboxPublisher publisher() {
        var publisher = new DetectionAlertOutboxPublisher(repository, alertClient, alarmProducer);
        publishers.add(publisher);
        return publisher;
    }

    private void allowFailureWrite() {
        given(repository.markFailed(anyString(), anyString(), anyString(), any(),
                any(Instant.class), anyString(), any(Instant.class))).willAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return 1;
        });
    }

    private void allowPublishedWrite() {
        given(repository.markPublished(anyString(), anyString(), any(Instant.class), any(Instant.class)))
                .willAnswer(invocation -> {
                    assertEquals("tenant-a", TenantContext.get());
                    return 1;
                });
    }

    private static DetectionAlertOutboxEntity event(String id) {
        return new DetectionAlertOutboxEntity(id, "tenant-a", "{\"id\":\"" + id + "\"}", Instant.now());
    }

    private static ServiceCall success() {
        return new ServiceCall(SocpService.ALERT, "http://alert", true, 200,
                "{}", null, 1, false, 1);
    }

    private static ServiceCall failure() {
        return new ServiceCall(SocpService.ALERT, "http://alert", false, 503,
                "", null, 1, true, 1);
    }
}
