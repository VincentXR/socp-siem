package com.socp.alert.service;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpService;
import com.socp.platform.client.SoarClient;
import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
