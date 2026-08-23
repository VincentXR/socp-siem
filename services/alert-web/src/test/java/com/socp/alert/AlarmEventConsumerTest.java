package com.socp.alert;

import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

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
}
