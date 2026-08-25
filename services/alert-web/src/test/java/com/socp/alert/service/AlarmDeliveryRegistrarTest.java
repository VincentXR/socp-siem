package com.socp.alert.service;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlarmDeliveryRegistrarTest {

    @Mock
    private AlarmDeliveryRepository repository;

    @Test
    void createsOneDurableIntentPerDestination() {
        given(repository.findAllById(anyList())).willReturn(List.of());
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
        given(repository.findAllById(anyList())).willAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            existing.setId(ids.getFirst());
            return List.of(existing);
        });
        AlarmDeliveryRegistrar registrar = new AlarmDeliveryRegistrar(repository);

        registrar.register("tenant-a", "AL-1", "{\"id\":\"AL-1\"}");

        ArgumentCaptor<Iterable<AlarmDelivery>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(3, StreamSupport.stream(captor.getValue().spliterator(), false).count());
    }
}
