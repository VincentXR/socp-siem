package com.socp.hips.web.store;

import com.socp.hips.web.model.Endpoint;
import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class EndpointEventStoreTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void eventsAreIsolatedAndRequestTenantCannotOverrideContext() {
        EndpointStore endpoints = mock(EndpointStore.class);
        given(endpoints.list()).willReturn(List.<Endpoint>of());
        EndpointEventStore store = new EndpointEventStore(endpoints);

        TenantContext.set("tenant-a");
        store.add(Map.of("hostname", "web-01", "tenantId", "tenant-b"));

        assertThat(store.list()).singleElement()
                .extracting(event -> event.get("tenantId"))
                .isEqualTo("tenant-a");
        TenantContext.set("tenant-b");
        assertThat(store.list()).isEmpty();
    }
}
