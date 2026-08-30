package com.socp.alert.service;


import com.socp.alert.config.AlertKafkaProperties;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AlarmEventConsumerTest {

    @Mock
    private AlarmDeliveryRegistrar registrar;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void replayRegistersIdempotentDeliveryIntentsUnderCarriedTenant() throws Exception {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        String payload = "{\"id\":\"AL-100\",\"tenantId\":\"tenant-b\",\"severity\":\"HIGH\"}";

        consumer.registerEvent(payload);

        verify(registrar).register("tenant-b", "AL-100", payload);
    }

    @Test
    void rejectsMissingIdentityBeforeRegistration() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);

        assertThrows(IllegalArgumentException.class,
                () -> consumer.registerEvent("{\"tenantId\":\"tenant-b\"}"));
    }

    @Test
    void rejectsInvalidTenantBeforeRegistration() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);

        assertThrows(IllegalArgumentException.class,
                () -> consumer.registerEvent("{\"id\":\"AL-100\",\"tenantId\":\"../other\"}"));
    }

    @Test
    void acceptsLegacyTenantIdFieldAndKeepsScopeForRegistrar() throws Exception {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertEquals("tenant-c", TenantContext.get());
            return null;
        }).when(registrar).register("tenant-c", "AL-101", "{\"id\":\"AL-101\",\"tenant_id\":\"tenant-c\"}");

        String payload = "{\"id\":\"AL-101\",\"tenant_id\":\"tenant-c\"}";
        consumer.registerEvent(payload);

        verify(registrar).register("tenant-c", "AL-101", payload);
    }

    @Test
    void rejectsMissingTenantAndMalformedJsonBeforeRegistration() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);

        assertThrows(IllegalArgumentException.class,
                () -> consumer.registerEvent("{\"id\":\"AL-102\"}"));
        assertThrows(Exception.class, () -> consumer.registerEvent("{broken"));

        verify(registrar, never()).register(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertNull(TenantContext.get());
    }

    @Test
    void disabledConsumerDoesNotStartKafkaWorker() {
        AlertKafkaProperties properties = new AlertKafkaProperties();
        properties.setEnabled(false);
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar, properties);

        consumer.start();

        verify(registrar, never()).register(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
