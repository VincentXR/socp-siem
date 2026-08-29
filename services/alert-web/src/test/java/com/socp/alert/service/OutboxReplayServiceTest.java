package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.socp.platform.error.exception.ApiException;
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

@ExtendWith(MockitoExtension.class)
class OutboxReplayServiceTest {

    @Mock private OutboxRepository eventRepository;
    @Mock private AlarmDeliveryRepository deliveryRepository;
    @Mock private AlarmRepository alarmRepository;

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
        given(eventRepository.requeueDead(eq("event-1"), any(Instant.class))).willReturn(1);
        OutboxReplayService service = service();

        OutboxReplayResult result = service.requeueAlarmEvent("event-1");

        assertEquals("PENDING", result.status());
        assertEquals("tenant-a", result.tenantId());
        verify(eventRepository).requeueDead(eq("event-1"), any(Instant.class));
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
        given(deliveryRepository.requeueDead(eq("delivery-1"), eq("tenant-a"), any(Instant.class)))
                .willReturn(0);

        ApiException failure = assertThrows(ApiException.class,
                () -> service().requeueAlarmDelivery("delivery-1"));

        assertEquals(400, failure.getCode());
    }

    private OutboxReplayService service() {
        return new OutboxReplayService(eventRepository, deliveryRepository, alarmRepository);
    }
}
