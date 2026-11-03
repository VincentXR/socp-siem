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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void persistedParentSurvivesAsynchronousDispatch() {
        io.opentelemetry.api.GlobalOpenTelemetry.resetForTest();
        io.opentelemetry.api.GlobalOpenTelemetry.set(io.opentelemetry.api.OpenTelemetry.propagating(
                io.opentelemetry.context.propagation.ContextPropagators.create(
                        io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance())));
        String parent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        try {
            AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
            delivery.setTraceparent(parent);
            given(repository.findDueBatch(
                    eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
            given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString()))
                    .willReturn(1);
            var observed = new java.util.concurrent.atomic.AtomicReference<String>();
            given(notifyClient.notifyAlert(delivery.getPayload())).willAnswer(call -> {
                assertEquals("tenant-b", TenantContext.get());
                observed.set(com.socp.platform.obs.web.TraceIdFilter.buildTraceparent());
                return ok();
            });
            publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);
            publisher.publish();
            assertEquals(parent, observed.get(), "dispatch must use the persisted real parent, not a random MDC span id");
            verify(repository).markDelivered(eq(delivery.getId()), any(Instant.class), anyString());
            assertFalse(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid());
        } finally {
            io.opentelemetry.api.GlobalOpenTelemetry.resetForTest();
        }
    }

    @Mock private AlarmDeliveryRepository repository;
    @Mock private CkReporter ckReporter;
    @Mock private NotifyClient notifyClient;
    @Mock private IncidentClient incidentClient;
    @Mock private SoarClient soarClient;
    private AlarmDeliveryPublisher publisher;

    @Test
    void deliveryTriggersDoNotRescanBacklogHistory() {
        var metrics = org.mockito.Mockito.mock(AlertPerformanceMetrics.class);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                metrics, 1, 12, 86_400_000L);
        for (int i = 0; i < 20; i++) publisher.publish();
        verify(repository, never()).countByStatus(anyString());
        verify(repository, never()).findOldestCreatedAtByStatus(anyString());
        verify(repository, never()).findOldestUpdatedAtByStatus(anyString());
        verify(metrics, org.mockito.Mockito.times(20)).outboxDrain(eq("alarm_delivery"), eq(0),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void scheduledBacklogUsesDurableCountsAndRetainsExplicitSystemScope() throws Exception {
        var metrics = org.mockito.Mockito.mock(AlertPerformanceMetrics.class);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                metrics, 1, 12, 86_400_000L);
        Instant pending = Instant.parse("2026-09-01T00:00:00Z");
        Instant dead = pending.minusSeconds(10);
        given(repository.countByStatus("PENDING")).willReturn(17L);
        given(repository.countByStatus("DEAD")).willReturn(5L);
        given(repository.findOldestCreatedAtByStatus("PENDING")).willReturn(pending);
        given(repository.findOldestUpdatedAtByStatus("DEAD")).willReturn(dead);
        publisher.refreshBacklog();
        verify(metrics).outboxBacklog("alarm_delivery", 17L, pending, 5L, dead);
        var job = AlarmDeliveryPublisher.class.getMethod("refreshBacklog");
        org.junit.jupiter.api.Assertions.assertNotNull(job.getAnnotation(
                com.socp.platform.tenant.persistence.TenantSystemJob.class));
        assertEquals(30_000L, job.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).fixedDelay());
        given(repository.countByStatus("PENDING")).willThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(publisher::refreshBacklog);
        verify(metrics, org.mockito.Mockito.times(1)).outboxBacklog("alarm_delivery", 17L, pending, 5L, dead);
    }

    @AfterEach
    void stop() {
        TenantContext.clear();
        if (publisher != null) publisher.stop();
    }

    @Test
    void enrichmentFailureRetainsTheDurableJobForRetry() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.ENRICHMENT);
        given(repository.findDueBatch(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        AlarmEnrichmentService enrichment = org.mockito.Mockito.mock(AlarmEnrichmentService.class);
        doThrow(new IllegalStateException("Threat unavailable")).when(enrichment).enrichDurably(delivery.getTenantId(), delivery.getAlarmId());
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "enrichmentService", enrichment);
        publisher.publish();
        verify(repository, never()).markDelivered(eq(delivery.getId()), any(), anyString());
        verify(repository).scheduleRetry(eq(delivery.getId()), any(), any(), any(), anyString());
    }

    @Test
    void acknowledgedDeliveryIsMarkedDeliveredUnderItsTenant() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        given(repository.findDueBatch(
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
        given(repository.findDueBatch(
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
        given(repository.findDueBatch(
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
    void permanentNotificationFailureIsDeadLetteredWithoutRetry() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        given(repository.findDueBatch(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(notifyClient.notifyAlert(delivery.getPayload())).willReturn(new ServiceCall(
                SocpService.NOTIFY, "http://notify", false, 422, "", "invalid channel credentials", 1, false, 1));
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(repository).markDead(eq(delivery.getId()), eq("invalid channel credentials"),
                any(Instant.class), anyString());
        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any(), anyString());
    }

    @Test
    void invalidClickHousePayloadIsDeadLetteredWithoutCallingReporter() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.CLICKHOUSE);
        delivery.setPayload("not-json");
        given(repository.findDueBatch(
                eq("PENDING"), any(Instant.class))).willReturn(List.of(delivery));
        given(repository.claim(eq(delivery.getId()), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        publisher.publish();

        verify(ckReporter, never()).reportAlarmAndAwait(any());
        verify(repository).markDead(eq(delivery.getId()), anyString(), any(Instant.class), anyString());
        verify(repository, never()).scheduleRetry(eq(delivery.getId()), any(), any(), any(), anyString());
    }

    @Test
    void retryLimitMovesFailedDeliveryToDead() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        given(repository.findDueBatch(
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
    void asyncTriggerRunsCrossTenantScanInsideSystemScope() throws Exception {
        var systemScope = new java.util.concurrent.CompletableFuture<Boolean>();
        var maintenanceScope = new AtomicBoolean();
        given(repository.markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100))).willAnswer(invocation -> {
            maintenanceScope.set(TenantContext.isSystemScope());
            return 0;
        });
        given(repository.findDueBatch(
                eq("PENDING"), any(Instant.class))).willAnswer(invocation -> {
            systemScope.complete(maintenanceScope.get() && TenantContext.isSystemScope());
            return List.of();
        });
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient);

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        // Wait for the last configured asynchronous observation, so test teardown
        // cannot interrupt the scan before its pending-row query uses the stub.
        assertEquals(true, systemScope.get(2, java.util.concurrent.TimeUnit.SECONDS).booleanValue());
        verify(repository).markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100));
    }

    @Test
    void asyncDeliveryBindsEventTenantAndDoesNotDeadlockSingleWorker() {
        AlarmDelivery delivery = delivery(AlarmDeliveryDestination.NOTIFY);
        AtomicBoolean tenantScope = new AtomicBoolean();
        given(repository.findDueBatch(
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
        given(repository.findDueBatch(
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
        given(repository.findDueBatch(
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
        given(repository.findDueBatch(
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
        given(repository.findDueBatch(
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
        given(repository.findDueBatch(
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
        given(repository.findDueBatch(
                eq("PENDING"), any(Instant.class)))
                .willReturn(fullBatch, fullBatch, fullBatch, fullBatch, List.of());
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, 12, 60_000L, 8, 10_000L);

        publisher.publish();

        verify(repository, org.mockito.Mockito.times(5))
                .findDueBatch(
                        eq("PENDING"), any(Instant.class));
    }

    @Test
    void productionCohortsKeepTenantBoundariesAndCommitReceiptsAfterDispatch() {
        var batchService = org.mockito.Mockito.spy(new AlarmDeliveryBatchService(repository));
        var properties = new com.socp.alert.config.AlertDeliveryProperties();
        properties.setConcurrency(2);
        var first = delivery(AlarmDeliveryDestination.NOTIFY);
        var second = delivery(AlarmDeliveryDestination.NOTIFY); second.setId("second");
        var other = delivery(AlarmDeliveryDestination.NOTIFY); other.setId("other"); other.setTenantId("tenant-a");
        other.setPayload("{\"id\":\"other\",\"tenantId\":\"tenant-a\"}");
        given(repository.findDueBatch(eq("PENDING"), any(Instant.class))).willReturn(List.of(first, second, other));
        given(repository.claimForTenant(anyString(), anyString(), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        given(repository.markDeliveredForTenant(anyString(), anyString(), any(Instant.class), anyString())).willReturn(1);
        var tenants = new java.util.concurrent.CopyOnWriteArrayList<String>();
        given(notifyClient.notifyAlert(anyString())).willAnswer(call -> { tenants.add(TenantContext.require()); return ok(); });
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, properties, batchService);
        publisher.publish();
        assertEquals(2, tenants.stream().filter("tenant-b"::equals).count());
        assertEquals(1, tenants.stream().filter("tenant-a"::equals).count());
        verify(batchService, org.mockito.Mockito.times(3)).claim(org.mockito.ArgumentMatchers.anyList(), eq(12));
        verify(batchService, org.mockito.Mockito.times(3)).acknowledge(org.mockito.ArgumentMatchers.anyList(), eq(12));
        verify(repository, org.mockito.Mockito.times(3)).markDeliveredForTenant(anyString(), anyString(), any(Instant.class), anyString());
        verify(repository, never()).claim(anyString(), any(Instant.class), anyInt(), anyInt(), anyString());
    }

    @Test
    void failedCohortClaimCannotSendAnyDownstreamRequest() {
        var batchService = org.mockito.Mockito.mock(AlarmDeliveryBatchService.class);
        given(repository.findDueBatch(eq("PENDING"), any(Instant.class)))
                .willReturn(List.of(delivery(AlarmDeliveryDestination.NOTIFY)));
        given(batchService.claim(org.mockito.ArgumentMatchers.anyList(), eq(12))).willThrow(new IllegalStateException("database unavailable"));
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, new com.socp.alert.config.AlertDeliveryProperties(), batchService);
        publisher.publish();
        verify(notifyClient, never()).notifyAlert(anyString());
        verify(batchService, never()).acknowledge(org.mockito.ArgumentMatchers.anyList(), anyInt());
    }

    @Test
    void slowCohortDoesNotBlockAnIndependentDeliveryCohort() throws Exception {
        var batchService = new AlarmDeliveryBatchService(repository);
        var properties = new com.socp.alert.config.AlertDeliveryProperties(); properties.setConcurrency(4);
        var slow1 = delivery(AlarmDeliveryDestination.NOTIFY); slow1.setId("slow-1");
        var slow2 = delivery(AlarmDeliveryDestination.NOTIFY); slow2.setId("slow-2");
        var fast1 = delivery(AlarmDeliveryDestination.SOAR); fast1.setId("fast-1");
        var fast2 = delivery(AlarmDeliveryDestination.SOAR); fast2.setId("fast-2");
        given(repository.findDueBatch(eq("PENDING"), any(Instant.class))).willReturn(List.of(slow1, slow2, fast1, fast2));
        given(repository.claimForTenant(anyString(), anyString(), any(Instant.class), anyInt(), anyInt(), anyString())).willReturn(1);
        var slowEntered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var fastAcknowledged = new java.util.concurrent.CountDownLatch(2);
        given(notifyClient.notifyAlert(anyString())).willAnswer(call -> {
            slowEntered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return ok();
        });
        given(soarClient.evaluate(anyString())).willReturn(ok());
        given(repository.markDeliveredForTenant(anyString(), anyString(), any(Instant.class), anyString())).willAnswer(call -> {
            if (call.<String>getArgument(1).startsWith("fast")) fastAcknowledged.countDown(); return 1;
        });
        publisher = new AlarmDeliveryPublisher(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, properties, batchService);
        var drain = java.util.concurrent.CompletableFuture.runAsync(publisher::publish);
        try {
            assertTrue(slowEntered.await(2, TimeUnit.SECONDS));
            assertTrue(fastAcknowledged.await(2, TimeUnit.SECONDS));
            assertFalse(drain.isDone());
        } finally { release.countDown(); }
        drain.get(5, TimeUnit.SECONDS);
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
        org.mockito.Mockito.when(repository.findDueBatch(eq("PENDING"), any())).thenReturn(List.of(row));
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
        org.mockito.Mockito.when(repository.findDueBatch(eq("PENDING"), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            return List.of();
        });
        var first = java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::publish));
        try {
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::publish))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS);
            verify(repository).findDueBatch(eq("PENDING"), any());
        } finally { release.countDown(); }
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
