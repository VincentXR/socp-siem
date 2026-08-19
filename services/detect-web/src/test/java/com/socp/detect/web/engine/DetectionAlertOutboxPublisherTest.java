package com.socp.detect.web.engine;

import com.socp.detect.web.store.DetectionAlertOutboxEntity;
import com.socp.detect.web.store.DetectionAlertOutboxRepository;
import com.socp.platform.client.AlertClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        given(repository.claim(eq("alert-1"), eq("PENDING"), any(Instant.class))).willReturn(1);
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
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("DELIVERED"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-2"), eq("PENDING"), any(Instant.class))).willReturn(1);
        given(repository.claim(eq("alert-2"), eq("DELIVERED"), any(Instant.class))).willReturn(1);
        given(alertClient.forwardAlarm(anyString())).willReturn(success());
        given(alarmProducer.sendAndAwait(any(), eq("alert-2"))).willReturn(true);

        DetectionAlertOutboxPublisher publisher = publisher();
        publisher.publishDue();

        assertEquals("PUBLISHED", event.getStatus());
        verify(alertClient).forwardAlarm("{\"id\":\"alert-2\"}");
        verify(alarmProducer).sendAndAwait(any(), eq("alert-2"));
    }

    @Test
    void duplicatePublisherCannotClaimTheSameStage() {
        DetectionAlertOutboxEntity event = event("alert-3");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(event));
        given(repository.claim(eq("alert-3"), eq("PENDING"), any(Instant.class))).willReturn(0);

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
        given(repository.claim(eq("alert-4"), eq("DELIVERED"), any(Instant.class))).willReturn(1);
        given(alarmProducer.sendAndAwait(any(), eq("alert-4"))).willReturn(false);

        publisher().publishDue();

        assertEquals("DELIVERED", event.getStatus());
        assertEquals(1, event.getAttempts());
        verify(alertClient, never()).forwardAlarm(anyString());
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
