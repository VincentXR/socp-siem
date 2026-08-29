package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
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
        DetectionAlertOutboxEntity event = event("alert-1");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-1"), eq("PENDING"), any(Instant.class), any(Integer.class))).willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(failure());

        DetectionAlertOutboxPublisher publisher = publisher();
        publisher.publishDue();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getAttempts());
        verify(alarmProducer, never()).sendAndAwait(any(), anyString());
    }

    @Test
    void acceptedAlertIsThenPublishedToTheOriginalAlarmStream() {
        DetectionAlertOutboxEntity event = event("alert-2");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-2"), eq("PENDING"), any(Instant.class), any(Integer.class))).willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(success());
        given(alarmProducer.sendAndAwait(any(), eq("alert-2"))).willAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return true;
        });

        DetectionAlertOutboxPublisher publisher = publisher();
        publisher.publishDue();

        assertEquals("PUBLISHED", event.getStatus());
        verify(alertClient).forwardAlarm(argThat(payload ->
                payload.contains("\"id\":\"alert-2\"")
                        && payload.contains("\"detectionOutboxClaimedAt\"")));
        verify(alarmProducer).sendAndAwait(any(), eq("alert-2"));
        verify(repository, never()).claim(eq("alert-2"), eq("DELIVERED"), any(Instant.class), any(Integer.class));
    }

    @Test
    void duplicatePublisherCannotClaimTheSameStage() {
        DetectionAlertOutboxEntity event = event("alert-3");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-3"), eq("PENDING"), any(Instant.class), any(Integer.class))).willReturn(0);

        DetectionAlertOutboxPublisher publisher = publisher();
        publisher.publishDue();

        verify(alertClient, never()).forwardAlarm(anyString());
        verify(alarmProducer, never()).sendAndAwait(any(), anyString());
    }

    @Test
    void originalAlarmFailureKeepsTheAlertInTheSecondStage() {
        DetectionAlertOutboxEntity event = event("alert-4");
        event.setStatus("DELIVERED");
        event.setDeliveredAt(Instant.now());
        given(repository.findByStatusAndUpdatedAtBefore(eq("PROCESSING"), any(Instant.class)))
                .willReturn(List.of());
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of());
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("DELIVERED"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-4"), eq("DELIVERED"), any(Instant.class), any(Integer.class))).willReturn(1);
        given(alarmProducer.sendAndAwait(any(), eq("alert-4"))).willReturn(false);

        publisher().publishDue();

        assertEquals("DELIVERED", event.getStatus());
        assertEquals(1, event.getAttempts());
        verify(alertClient, never()).forwardAlarm(anyString());
    }

    @Test
    void secondStageFailureAfterHttpSuccessPersistsDeliveredRecoveryPoint() {
        DetectionAlertOutboxEntity event = event("alert-inline-failure");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-inline-failure"), eq("PENDING"), any(Instant.class), any(Integer.class)))
                .willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(success());
        given(alarmProducer.sendAndAwait(any(), eq("alert-inline-failure"))).willReturn(false);

        publisher().publishDue();

        assertEquals("DELIVERED", event.getStatus());
        assertEquals(1, event.getAttempts());
        assertTrue(event.alertDelivered());
        verify(repository, never()).claim(
                eq("alert-inline-failure"), eq("DELIVERED"), any(Instant.class), any(Integer.class));
    }

    @Test
    void retryLimitMovesFailedAlertHandoffToDead() {
        DetectionAlertOutboxEntity event = event("alert-dead");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-dead"), eq("PENDING"), any(Instant.class), any(Integer.class))).willReturn(1);
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
        verify(repository).save(event);
    }

    @Test
    void stalePublisherClaimReturnsToTheCorrectStage() {
        DetectionAlertOutboxEntity pending = event("alert-5");
        pending.setStatus("PROCESSING");
        DetectionAlertOutboxEntity delivered = event("alert-6");
        delivered.setStatus("PROCESSING");
        delivered.setDeliveredAt(Instant.now());
        given(repository.findByStatusAndUpdatedAtBefore(eq("PROCESSING"), any(Instant.class)))
                .willReturn(List.of(pending, delivered));

        publisher().publishDue();

        assertEquals("PENDING", pending.getStatus());
        assertEquals("DELIVERED", delivered.getStatus());
        verify(repository, org.mockito.Mockito.times(2)).save(any(DetectionAlertOutboxEntity.class));
    }

    @Test
    void pendingAlertDeliveryUsesConfiguredBoundedConcurrency() {
        List<DetectionAlertOutboxEntity> events = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> event("parallel-" + i))
                .toList();
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(events);
        given(repository.claim(anyString(), eq("PENDING"), any(Instant.class), any(Integer.class))).willReturn(1);
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
        given(alarmProducer.sendAndAwait(any(), anyString())).willReturn(true);

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
        given(repository.findByStatusAndUpdatedAtBefore(eq("PROCESSING"), any(Instant.class)))
                .willReturn(List.of());
        given(repository.markExhausted(any(Integer.class), anyString(), any(Instant.class))).willAnswer(invocation -> {
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
                    .markExhausted(any(Integer.class), anyString(), any(Instant.class));
            assertTrue(systemScope.get());
        } finally {
            publisher.stopDeliveryExecutor();
            TenantContext.clear();
        }
    }

    private DetectionAlertOutboxPublisher publisher() {
        return new DetectionAlertOutboxPublisher(repository, alertClient, alarmProducer);
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
