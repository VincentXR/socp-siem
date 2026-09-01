package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmDelivery;
import com.socp.alert.domain.AlarmDeliveryDestination;
import com.socp.alert.persistence.repository.AlarmDeliveryRepository;


import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.client.service.SoarClient;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlarmDeliveryPublisherTest {

    @Mock private AlarmDeliveryRepository repository;
    @Mock private CkReporter ckReporter;
    @Mock private NotifyClient notifyClient;
    @Mock private IncidentClient incidentClient;
    @Mock private SoarClient soarClient;
    private AlarmDeliveryPublisher publisher;

    @AfterEach
    void stop() {
        TenantContext.clear();
        if (publisher != null) publisher.stop();
    }

    @Test
    void acknowledgedDeliveryIsMarkedDeliveredUnderItsTenant() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt())).willReturn(1);
        given(notifyClient.notifyAlert(delivery.getPayload())).willAnswer(invocation -> {
            assertEquals("tenant-b", TenantContext.get());
            return ok();
        });
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).markDelivered(eq(delivery.getId()), any(Instant.class));
        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any());
    }

    @Test
    void failedDestinationIsReleasedWithBackoffWithoutBlockingOthers() {
        AlarmDelivery notify = delivery(AlarmDeliveryDestination.NOTIFY);
        AlarmDelivery incident = delivery(AlarmDeliveryDestination.INCIDENT);
        incident.setId("delivery-incident");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(notify, incident));
        given(repository.claim(any(), any(), anyInt())).willReturn(1);
        given(notifyClient.notifyAlert(notify.getPayload())).willReturn(new ServiceCall(
                SocpService.NOTIFY, "http://notify", false, 503, "", "unavailable", 1, true, 1));
        given(incidentClient.createFromAlarm(incident.getPayload())).willReturn(ok());
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).scheduleRetry(eq(notify.getId()), any(Instant.class), eq("unavailable"), any(Instant.class));
        verify(repository).markDelivered(eq(incident.getId()), any(Instant.class));
    }

    @Test
    void retryLimitMovesFailedDeliveryToDead() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), eq(1))).willReturn(1);
        given(notifyClient.notifyAlert(delivery.getPayload())).willReturn(new ServiceCall(
                SocpService.NOTIFY, "http://notify", false, 503, "", "unavailable", 1, true, 1));
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, 1, 60_000L);

        publisher.publish();

        verify(repository).markDead(eq(delivery.getId()), eq("unavailable"), any(Instant.class));
        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any());
    }

    @Test
    void asyncTriggerRunsCrossTenantScanInsideSystemScope() {
        AtomicBoolean systemScope = new AtomicBoolean();
        given(repository.markExhausted(anyInt(), anyString(), any(Instant.class))).willAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            return 0;
        });
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of());
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        verify(repository, timeout(2_000)).markExhausted(anyInt(), anyString(), any(Instant.class));
        assertEquals(true, systemScope.get());
    }

    @Test
    void asyncDeliveryBindsEventTenantAndDoesNotDeadlockSingleWorker() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        AtomicBoolean tenantScope = new AtomicBoolean();
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt())).willAnswer(invocation -> {
            tenantScope.set("tenant-b".equals(TenantContext.get()) && !TenantContext.isSystemScope());
            return 1;
        });
        given(notifyClient.notifyAlert(delivery.getPayload())).willReturn(ok());
        given(repository.markDelivered(eq(delivery.getId()), any(Instant.class))).willReturn(1);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        verify(repository, timeout(2_000)).markDelivered(eq(delivery.getId()), any(Instant.class));
        assertEquals(true, tenantScope.get());
    }

    @Test
    void clickHouseDeliveryUsesDurableReporterBeforeAcknowledging() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.CLICKHOUSE);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt())).willReturn(1);
        given(ckReporter.reportAlarmAndAwait(any(Alarm.class))).willReturn(true);
        given(repository.markDelivered(eq(delivery.getId()), any(Instant.class))).willReturn(1);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(ckReporter).reportAlarmAndAwait(any(Alarm.class));
        verify(repository).markDelivered(eq(delivery.getId()), any(Instant.class));
    }

    @Test
    void clickHouseRejectionSchedulesARecoverableRetry() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.CLICKHOUSE);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt())).willReturn(1);
        given(ckReporter.reportAlarmAndAwait(any(Alarm.class))).willReturn(false);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).scheduleRetry(eq(delivery.getId()), any(Instant.class),
                eq("ClickHouse rejected alarm"), any(Instant.class));
        verify(repository, never()).markDelivered(eq(delivery.getId()), any(Instant.class));
    }

    @Test
    void lostClaimDoesNotInvokeAnyDownstreamConnector() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.SOAR);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt())).willReturn(0);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(soarClient, never()).evaluate(any());
        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any());
    }

    @Test
    void nullDownstreamResponseIsRetriedWithDestinationContext() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.SOAR);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt())).willReturn(1);
        given(soarClient.evaluate(delivery.getPayload())).willReturn(null);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).scheduleRetry(eq(delivery.getId()), any(Instant.class),
                eq("SOAR returned no result"), any(Instant.class));
    }

    @Test
    void stateConflictAfterSuccessfulCallDoesNotScheduleDuplicateRetry() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.INCIDENT);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt())).willReturn(1);
        given(incidentClient.createFromAlarm(delivery.getPayload())).willReturn(ok());
        given(repository.markDelivered(eq(delivery.getId()), any(Instant.class))).willReturn(0);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any());
    }

    @Test
    void cleanupRemovesExpiredRowsAndSwallowsStorageFailures() {
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, 12, 60_000L);
        given(repository.deleteDeliveredBefore(any(Instant.class))).willReturn(2);

        assertDoesNotThrow(() -> publisher.cleanupDelivered());
        verify(repository).deleteDeliveredBefore(any(Instant.class));

        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).deleteDeliveredBefore(any(Instant.class));
        assertDoesNotThrow(() -> publisher.cleanupDelivered());
    }

    @Test
    void drainsMoreThanThreeDeliveryBatchesWithinOneWindow() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        List<AlarmDelivery> fullBatch = java.util.Collections.nCopies(100, delivery);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .willReturn(fullBatch, fullBatch, fullBatch, fullBatch, List.of());
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, 12, 60_000L, 8, 10_000L);

        publisher.publish();

        verify(repository, org.mockito.Mockito.times(5))
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq("PENDING"), any(Instant.class));
    }

    private static AlarmDelivery delivery(AlarmDeliveryDestination destination) {
        AlarmDelivery delivery = new AlarmDelivery();
        delivery.setId("delivery-" + destination.name().toLowerCase());
        delivery.setTenantId("tenant-b");
        delivery.setAlarmId("AL-100");
        delivery.setDestination(destination.name());
        delivery.setPayload("{\"id\":\"AL-100\",\"tenantId\":\"tenant-b\"}");
        delivery.setStatus("PENDING");
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(Instant.now());
        delivery.setCreatedAt(Instant.now());
        delivery.setUpdatedAt(Instant.now());
        return delivery;
    }

    private static ServiceCall ok() {
        return new ServiceCall(SocpService.ALERT, "http://service", true,
                200, "", null, 1, false, 1);
    }
}
