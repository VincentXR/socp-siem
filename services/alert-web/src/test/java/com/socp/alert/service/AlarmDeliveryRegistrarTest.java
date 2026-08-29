package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlarmDeliveryRegistrarTest {

    @Mock
    private AlarmDeliveryRepository repository;

    @AfterEach
    void clearTenant() {
        com.socp.platform.tenant.context.TenantContext.clear();
    }

    @Test
    void createsOneDurableIntentPerDestination() {
        given(repository.findByTenantIdAndIdIn(org.mockito.ArgumentMatchers.eq("tenant-a"), anyList()))
                .willReturn(List.of());
        AlarmDeliveryRegistrar registrar = new AlarmDeliveryRegistrar(repository);

        registrar.register("tenant-a", "AL-1", "{\"id\":\"AL-1\"}");

        ArgumentCaptor<Iterable<AlarmDelivery>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        List<AlarmDelivery> deliveries = StreamSupport.stream(captor.getValue().spliterator(), false).toList();
        assertEquals(4, deliveries.size());
        assertEquals(4, deliveries.stream().map(AlarmDelivery::getId).distinct().count());
        assertTrue(deliveries.stream().allMatch(delivery -> "tenant-a".equals(delivery.getTenantId())));
        assertTrue(deliveries.stream().allMatch(delivery -> "PENDING".equals(delivery.getStatus())));
    }

    @Test
    void replayOnlyFillsMissingDestinations() {
        AlarmDelivery existing = new AlarmDelivery();
        given(repository.findByTenantIdAndIdIn(org.mockito.ArgumentMatchers.eq("tenant-a"), anyList()))
                .willAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            existing.setId(ids.getFirst());
            return List.of(existing);
        });
        AlarmDeliveryRegistrar registrar = new AlarmDeliveryRegistrar(repository);

        registrar.register("tenant-a", "AL-1", "{\"id\":\"AL-1\"}");

        ArgumentCaptor<Iterable<AlarmDelivery>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(3, StreamSupport.stream(captor.getValue().spliterator(), false).count());
    }

    @Test
    void rejectsInvalidOrCrossTenantRegistration() {
        AlarmDeliveryRegistrar registrar = new AlarmDeliveryRegistrar(repository);

        assertThrows(IllegalArgumentException.class,
                () -> registrar.register("", "AL-1", "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> registrar.register("tenant-a", "", "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> registrar.register("tenant-a", "AL-1", ""));

        com.socp.platform.tenant.context.TenantContext.set("tenant-a");
        assertThrows(IllegalArgumentException.class,
                () -> registrar.register("tenant-b", "AL-1", "{}"));
    }

    @Test
    void statusReturnsSafeDeliveryProjectionWithinTenantScope() {
        com.socp.platform.tenant.context.TenantContext.set("tenant-a");
        AlarmDelivery delivery = new AlarmDelivery();
        delivery.setId("delivery-1");
        delivery.setTenantId("tenant-a");
        delivery.setAlarmId("AL-1");
        delivery.setDestination("NOTIFY");
        delivery.setStatus("DELIVERED");
        delivery.setAttempts(2);
        delivery.setNextAttemptAt(java.time.Instant.EPOCH);
        given(repository.findByTenantIdAndAlarmIdOrderByDestinationAsc("tenant-a", "AL-1"))
                .willReturn(List.of(delivery));

        var result = new AlarmDeliveryRegistrar(repository).status("tenant-a", "AL-1");

        assertEquals("delivery-1", result.getFirst().get("deliveryId"));
        assertEquals("tenant-a", result.getFirst().get("tenantId"));
        assertEquals("DELIVERED", result.getFirst().get("status"));
        assertEquals(2, result.getFirst().get("attempts"));
    }
}
