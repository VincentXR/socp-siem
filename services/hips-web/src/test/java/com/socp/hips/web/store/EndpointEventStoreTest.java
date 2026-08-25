package com.socp.hips.web.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.model.Endpoint;
import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EndpointEventStoreTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void eventsAreIsolatedAndRequestTenantCannotOverrideContext() {
        EndpointStore endpoints = mock(EndpointStore.class);
        given(endpoints.list()).willReturn(List.<Endpoint>of());
        EndpointEventRepository repository = mock(EndpointEventRepository.class);
        EndpointEventStore store = new EndpointEventStore(endpoints, repository, new ObjectMapper());

        TenantContext.set("tenant-a");
        store.add(Map.of("hostname", "web-01", "tenantId", "tenant-b"));

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(event ->
                "tenant-a".equals(event.getTenantId())
                        && event.getPayloadJson().contains("tenant-a")));
        TenantContext.set("tenant-b");
        given(repository.findTop200ByTenantIdOrderByReceivedAtDesc("tenant-b")).willReturn(List.of());
        assertThat(store.list()).isEmpty();
    }
}
