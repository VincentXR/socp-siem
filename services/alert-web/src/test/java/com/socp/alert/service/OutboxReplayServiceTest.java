package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmDelivery;
import com.socp.alert.domain.OutboxEvent;
import com.socp.alert.domain.OutboxReplayResult;
import com.socp.alert.persistence.repository.AlarmDeliveryRepository;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.alert.persistence.repository.OutboxRepository;


import com.socp.platform.error.exception.ApiException;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OutboxReplayServiceTest {

    @Mock private OutboxRepository eventRepository;
    @Mock private AlarmDeliveryRepository deliveryRepository;
    @Mock private AlarmRepository alarmRepository;
    @Mock private NotifyClient notifyClient;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void requeuesTerminalAlarmEventOnlyWithinTheCurrentTenant() {
        TenantContext.set("tenant-a");
        OutboxEvent event = new OutboxEvent();
        event.setId("event-1");
        event.setAggregateId("alarm-1");
        event.setStatus("DEAD");
        given(eventRepository.findByIdAndTenantId("event-1", "tenant-a")).willReturn(Optional.of(event));
        given(alarmRepository.findByTenantIdAndId("tenant-a", "alarm-1"))
                .willReturn(Optional.of(new Alarm()));
        given(eventRepository.requeueDead(eq("event-1"), eq("tenant-a"), any(Instant.class)))
                .willReturn(1);
        OutboxReplayService service = service();

        OutboxReplayResult result = service.requeueAlarmEvent("event-1");

        assertEquals("PENDING", result.status());
        assertEquals("tenant-a", result.tenantId());
        verify(eventRepository).requeueDead(eq("event-1"), eq("tenant-a"), any(Instant.class));
    }

    @Test
    void requeuesTerminalDeliveryOnlyWithinTheCurrentTenant() {
        TenantContext.set("tenant-a");
        AlarmDelivery delivery = new AlarmDelivery();
        delivery.setId("delivery-1");
        delivery.setTenantId("tenant-a");
        delivery.setStatus("DEAD");
        given(deliveryRepository.findByIdAndTenantId("delivery-1", "tenant-a"))
                .willReturn(Optional.of(delivery));
        given(deliveryRepository.requeueDead(eq("delivery-1"), eq("tenant-a"), any(Instant.class)))
                .willReturn(1);
        OutboxReplayService service = service();

        OutboxReplayResult result = service.requeueAlarmDelivery("delivery-1");

        assertEquals("ALARM_DELIVERY", result.type());
        verify(deliveryRepository).requeueDead(eq("delivery-1"), eq("tenant-a"), any(Instant.class));
    }

    @Test
    void refusesToReplayANonTerminalRow() {
        TenantContext.set("tenant-a");
        AlarmDelivery delivery = new AlarmDelivery();
        delivery.setId("delivery-1");
        delivery.setTenantId("tenant-a");
        delivery.setStatus("DELIVERED");
        given(deliveryRepository.findByIdAndTenantId("delivery-1", "tenant-a"))
                .willReturn(Optional.of(delivery));
        ApiException failure = assertThrows(ApiException.class,
                () -> service().requeueAlarmDelivery("delivery-1"));

        assertEquals(400, failure.getCode());
    }

    @Test
    void notificationReplayReopensRemoteReceiptBeforeTheLocalPublisherCanRaceIt() {
        TenantContext.set("tenant-a");
        AlarmDelivery delivery = new AlarmDelivery();
        delivery.setId("delivery-notify");
        delivery.setTenantId("tenant-a");
        delivery.setAlarmId("alarm-1");
        delivery.setDestination("NOTIFY");
        delivery.setStatus("DEAD");
        given(deliveryRepository.findByIdAndTenantId("delivery-notify", "tenant-a"))
                .willReturn(Optional.of(delivery));
        given(notifyClient.recoverAlarmDeliveries(any(String.class))).willReturn(new ServiceCall(
                SocpService.NOTIFY, "notify recovery", true, 200, "{}", null, 1, false, 1));
        given(deliveryRepository.requeueDead(eq("delivery-notify"), eq("tenant-a"), any(Instant.class)))
                .willReturn(1);
        OutboxReplayService service = new OutboxReplayService(
                eventRepository, deliveryRepository, alarmRepository, null, notifyClient);

        service.requeueAlarmDelivery("delivery-notify", "credentials rotated", true);

        var order = org.mockito.Mockito.inOrder(notifyClient, deliveryRepository);
        order.verify(notifyClient).recoverAlarmDeliveries(org.mockito.ArgumentMatchers.argThat(json ->
                json.contains("\"alarmId\":\"alarm-1\"")
                        && json.contains("\"confirmUnknown\":true")
                        && json.contains("credentials rotated")));
        order.verify(deliveryRepository).requeueDead(eq("delivery-notify"), eq("tenant-a"), any(Instant.class));
    }

    @Test
    void notificationRecoveryFailureLeavesTheLocalDeliveryDead() {
        TenantContext.set("tenant-a");
        AlarmDelivery delivery = new AlarmDelivery();
        delivery.setId("delivery-notify");
        delivery.setTenantId("tenant-a");
        delivery.setAlarmId("alarm-1");
        delivery.setDestination("NOTIFY");
        delivery.setStatus("DEAD");
        given(deliveryRepository.findByIdAndTenantId("delivery-notify", "tenant-a"))
                .willReturn(Optional.of(delivery));
        given(notifyClient.recoverAlarmDeliveries(any(String.class))).willReturn(new ServiceCall(
                SocpService.NOTIFY, "notify recovery", false, 409, "{}", "confirmation required", 1, false, 1));
        OutboxReplayService service = new OutboxReplayService(
                eventRepository, deliveryRepository, alarmRepository, null, notifyClient);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.requeueAlarmDelivery("delivery-notify", "not verified", false));

        assertEquals(409, failure.getCode());
        verify(deliveryRepository, never()).requeueDead(any(), any(), any());
    }

    @Test
    void discardsTerminalRowWithAnOperatorReason() {
        TenantContext.set("tenant-a");
        OutboxEvent event = new OutboxEvent();
        event.setId("event-1");
        event.setTenantId("tenant-a");
        event.setStatus("DEAD");
        given(eventRepository.findByIdAndTenantId("event-1", "tenant-a"))
                .willReturn(Optional.of(event));
        given(eventRepository.discardDead(eq("event-1"), eq("tenant-a"),
                eq("operator discard: duplicate upstream event"), any(Instant.class))).willReturn(1);

        var result = service().discardAlarmEvent("event-1", "duplicate upstream event");

        assertEquals("DISCARDED", result.status());
        verify(eventRepository).discardDead(eq("event-1"), eq("tenant-a"),
                eq("operator discard: duplicate upstream event"), any(Instant.class));
    }

    @Test
    void requiresReasonBeforeDiscarding() {
        TenantContext.set("tenant-a");
        AlarmDelivery delivery = new AlarmDelivery();
        delivery.setId("delivery-1");
        given(deliveryRepository.findByIdAndTenantId("delivery-1", "tenant-a"))
                .willReturn(Optional.of(delivery));

        ApiException failure = assertThrows(ApiException.class,
                () -> service().discardAlarmDelivery("delivery-1", " "));

        assertEquals(400, failure.getCode());
    }

    private OutboxReplayService service() {
        return new OutboxReplayService(eventRepository, deliveryRepository, alarmRepository);
    }
}
