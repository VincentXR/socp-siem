package com.socp.hips.web.api.controller;

import com.socp.hips.web.persistence.store.EndpointForwardingStore;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EndpointForwardingController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "socp.security.dev-bypass=true")
class EndpointForwardingControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EndpointForwardingStore store;

    @Test void adminListIsBoundedAndUsesTheRequestTenant() throws Exception {
        when(store.list("tenant-a", "DEAD", 20)).thenReturn(List.of(Map.of("eventId", "event-1")));
        try (var ignored = TenantContext.open("tenant-a")) {
            mvc.perform(get("/api/v1/endpoints/forwarding").header("Authorization", "Bearer test-token")
                            .header("X-Role", "admin").header("X-Tenant-Id", "tenant-a").param("limit", "20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].eventId").value("event-1"));
        }
        verify(store).list("tenant-a", "DEAD", 20);
    }

    @Test void invalidLimitsAndStatusesDoNotQueryStorage() throws Exception {
        for (String limit : List.of("0", "501")) {
            mvc.perform(get("/api/v1/endpoints/forwarding").header("Authorization", "Bearer test-token")
                    .header("X-Role", "admin").param("limit", limit)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/endpoints/forwarding").header("Authorization", "Bearer test-token")
                .header("X-Role", "admin").param("status", "UNKNOWN")).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @Test void nonAdminsCannotInspectOrRequeue() throws Exception {
        for (String role : List.of("viewer", "analyst")) {
            mvc.perform(get("/api/v1/endpoints/forwarding").header("Authorization", "Bearer test-token")
                    .header("X-Role", role)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/endpoints/forwarding/event-1/requeue").header("Authorization", "Bearer test-token")
                    .header("X-Role", role)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(store);
    }

    @Test void requeueUsesTenantAndMissingOrNonDeadRowsConflict() throws Exception {
        when(store.requeue(eq("tenant-a"), eq("event-1"), any())).thenReturn(true);
        try (var ignored = TenantContext.open("tenant-a")) {
            mvc.perform(post("/api/v1/endpoints/forwarding/event-1/requeue").header("Authorization", "Bearer test-token")
                    .header("X-Role", "admin").header("X-Tenant-Id", "tenant-a"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
        }
        try (var ignored = TenantContext.open("tenant-b")) {
            mvc.perform(post("/api/v1/endpoints/forwarding/event-1/requeue").header("Authorization", "Bearer test-token")
                    .header("X-Role", "admin").header("X-Tenant-Id", "tenant-b")).andExpect(status().isConflict());
        }
        verify(store).requeue(eq("tenant-a"), eq("event-1"), any());
        verify(store).requeue(eq("tenant-b"), eq("event-1"), any());
    }
}
