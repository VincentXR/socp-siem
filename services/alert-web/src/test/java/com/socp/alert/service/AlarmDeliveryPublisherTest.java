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
import java.util.concurrent.atomic.AtomicReference;

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
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(notifyClient.notifyAlert(delivery.getPayload())).willAnswer(invocation -> {
            assertEquals("tenant-b", TenantContext.get());
            return ok();
        });
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).markDelivered(eq(delivery.getId()), any(Instant.class), anyString());
        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any(), anyString());
    }

    @Test
    void connectorExceptionSchedulesRetryUnderDeliveryTenant() {
        assertFailureStateTenant(false, false);
    }

    @Test
    void acknowledgementExceptionSchedulesRetryUnderDeliveryTenant() {
        assertFailureStateTenant(true, false);
    }

    @Test
    void connectorExceptionAtRetryLimitMarksDeadUnderDeliveryTenant() {
        assertFailureStateTenant(false, true);
    }

    private void assertFailureStateTenant(boolean acknowledgementFails, boolean exhausted) {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        AtomicReference<String> stateTenant = new AtomicReference<>();
        AtomicBoolean systemScope = new AtomicBoolean(true);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        if (acknowledgementFails) {
            given(notifyClient.notifyAlert(delivery.getPayload())).willReturn(ok());
            given(repository.markDelivered(eq(delivery.getId()), any(Instant.class), anyString()))
                    .willThrow(new IllegalStateException("acknowledgement unavailable"));
        } else {
            given(notifyClient.notifyAlert(delivery.getPayload()))
                    .willThrow(new IllegalStateException("connector unavailable"));
        }
        org.mockito.stubbing.Answer<Integer> captureTenant = invocation -> {
            stateTenant.set(TenantContext.get());
            systemScope.set(TenantContext.isSystemScope());
            return 1;
        };
        if (exhausted) {
            given(repository.markDead(eq(delivery.getId()), anyString(), any(Instant.class), anyString()))
                    .willAnswer(captureTenant);
        } else {
            given(repository.scheduleRetry(eq(delivery.getId()), any(Instant.class),
                    anyString(), any(Instant.class), anyString())).willAnswer(captureTenant);
        }
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, exhausted ? 1 : 12, 60_000L);
        TenantContext.set("tenant-a");

        publisher.publish();

        assertEquals("tenant-b", stateTenant.get());
        assertEquals(false, systemScope.get());
        assertEquals("tenant-a", TenantContext.get());
    }

    @Test
    void failedDestinationIsReleasedWithBackoffWithoutBlockingOthers() {
        AlarmDelivery notify = delivery(AlarmDeliveryDestination.NOTIFY);
        AlarmDelivery incident = delivery(AlarmDeliveryDestination.INCIDENT);
        incident.setId("delivery-incident");
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(notify, incident));
        given(repository.claim(any(), any(), anyInt(), anyInt(), anyString())).willReturn(1);
        given(notifyClient.notifyAlert(notify.getPayload())).willReturn(new ServiceCall(
                SocpService.NOTIFY, "http://notify", false, 503, "", "unavailable", 1, true, 1));
        given(incidentClient.createFromAlarm(incident.getPayload())).willReturn(ok());
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).scheduleRetry(eq(notify.getId()), any(Instant.class), eq("unavailable"), any(Instant.class), anyString());
        verify(repository).markDelivered(eq(incident.getId()), any(Instant.class), anyString());
    }

    @Test
    void retryLimitMovesFailedDeliveryToDead() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), eq(1), anyInt(), anyString())).willReturn(1);
        given(notifyClient.notifyAlert(delivery.getPayload())).willReturn(new ServiceCall(
                SocpService.NOTIFY, "http://notify", false, 503, "", "unavailable", 1, true, 1));
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, 1, 60_000L);

        publisher.publish();

        verify(repository).markDead(eq(delivery.getId()), eq("unavailable"), any(Instant.class), anyString());
        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any(), anyString());
    }

    @Test
    void asyncTriggerRunsCrossTenantScanInsideSystemScope() {
        AtomicBoolean systemScope = new AtomicBoolean();
        given(repository.markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100))).willAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            return 0;
        });
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of());
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        verify(repository, timeout(2_000)).markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100));
        assertEquals(true, systemScope.get());
    }

    @Test
    void asyncDeliveryBindsEventTenantAndDoesNotDeadlockSingleWorker() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        AtomicBoolean tenantScope = new AtomicBoolean();
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willAnswer(invocation -> {
            tenantScope.set("tenant-b".equals(TenantContext.get()) && !TenantContext.isSystemScope());
            return 1;
        });
        given(notifyClient.notifyAlert(delivery.getPayload())).willReturn(ok());
        given(repository.markDelivered(eq(delivery.getId()), any(Instant.class), anyString())).willReturn(1);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        verify(repository, timeout(2_000)).markDelivered(eq(delivery.getId()), any(Instant.class), anyString());
        assertEquals(true, tenantScope.get());
    }

    @Test
    void clickHouseDeliveryUsesDurableReporterBeforeAcknowledging() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.CLICKHOUSE);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(ckReporter.reportAlarmAndAwait(any(Alarm.class))).willReturn(true);
        given(repository.markDelivered(eq(delivery.getId()), any(Instant.class), anyString())).willReturn(1);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(ckReporter).reportAlarmAndAwait(any(Alarm.class));
        verify(repository).markDelivered(eq(delivery.getId()), any(Instant.class), anyString());
    }

    @Test
    void clickHouseRejectionSchedulesARecoverableRetry() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.CLICKHOUSE);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(ckReporter.reportAlarmAndAwait(any(Alarm.class))).willReturn(false);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).scheduleRetry(eq(delivery.getId()), any(Instant.class),
                eq("ClickHouse rejected alarm"), any(Instant.class), anyString());
        verify(repository, never()).markDelivered(eq(delivery.getId()), any(Instant.class), anyString());
    }

    @Test
    void lostClaimDoesNotInvokeAnyDownstreamConnector() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.SOAR);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(0);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(soarClient, never()).evaluate(any());
        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any(), anyString());
    }

    @Test
    void nullDownstreamResponseIsRetriedWithDestinationContext() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.SOAR);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(soarClient.evaluate(delivery.getPayload())).willReturn(null);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).scheduleRetry(eq(delivery.getId()), any(Instant.class),
                eq("SOAR returned no result"), any(Instant.class), anyString());
    }

    @Test
    void stateConflictAfterSuccessfulCallDoesNotScheduleDuplicateRetry() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.INCIDENT);
        given(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(incidentClient.createFromAlarm(delivery.getPayload())).willReturn(ok());
        given(repository.markDelivered(eq(delivery.getId()), any(Instant.class), anyString())).willReturn(0);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any(), anyString());
    }

    @Test
    void cleanupRemovesExpiredRowsAndSwallowsStorageFailures() {
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, 12, 60_000L);
        given(repository.deleteDeliveredBatchBefore(any(Instant.class), anyInt())).willReturn(2);

        assertDoesNotThrow(() -> publisher.cleanupDelivered());
        verify(repository).deleteDeliveredBatchBefore(any(Instant.class), anyInt());

        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).deleteDeliveredBatchBefore(any(Instant.class), anyInt());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "retry", "dead"})
    void completionRetainsTheExactClaimToken(String outcome) {
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient, null, 1, 2, 60000);
        AlarmDelivery row = delivery(AlarmDeliveryDestination.NOTIFY);
        row.setAttempts("dead".equals(outcome) ? 1 : 0);
        org.mockito.Mockito.when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any())).thenReturn(List.of(row));
        org.mockito.Mockito.when(repository.claim(eq(row.getId()), any(), eq(2), eq(row.getAttempts()), anyString())).thenReturn(1);
        org.mockito.Mockito.when(notifyClient.notifyAlert(any())).thenReturn("success".equals(outcome) ? ok() : null);
        TenantContext.runAsSystem(publisher::publish);
        var owner = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).claim(eq(row.getId()), any(), eq(2), eq(row.getAttempts()), owner.capture());
        java.util.UUID.fromString(owner.getValue());
        switch (outcome) {
            case "success" -> verify(repository).markDelivered(eq(row.getId()), any(), eq(owner.getValue()));
            case "retry" -> verify(repository).scheduleRetry(eq(row.getId()), any(), any(), any(), eq(owner.getValue()));
            case "dead" -> verify(repository).markDead(eq(row.getId()), any(), any(), eq(owner.getValue()));
            default -> throw new AssertionError(outcome);
        }
    }

    @Test
    void overlappingDrainsScanOnlyOnce() throws Exception {
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient, null, 1, 2, 60000);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            return List.of();
        });
        var first = java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::publish));
        try {
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::publish))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS);
            verify(repository).findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any());
        } finally { release.countDown(); }
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
