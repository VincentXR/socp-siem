package com.socp.hips.web.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.persistence.store.EndpointEventStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.socp.hips.web.api.request.EndpointEventRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EndpointCollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "socp.security.dev-bypass=true",
        "socp.security.service-secret=hips-request-fixture-secret-0123456789",
        "socp.security.collector-credentials=hips-test|tenant-a|test-token",
        "socp.security.allow-global-ingest-token=false",
        "socp.security.ingest-paths[0]=/api/v1/events"
})
class EndpointCollectionControllerTest {

    @Test
    void requestKeyUsesRegisteredCollectorIdentityRatherThanPayloadAgent() throws Exception {
        var event = Map.<String, Object>of("eventId", "keyed-event", "tenantId", "tenant-a");
        given(delivery.accept(org.mockito.ArgumentMatchers.anyMap(), eq("collector:hips-test"), eq("request-1"))).willReturn(event);
        given(delivery.status(event)).willReturn("PENDING");
        mvc.perform(post("/api/v1/events").header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "request-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"host\",\"agent\":\"spoofed-producer\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eventId").value("keyed-event"));
        verify(delivery).accept(org.mockito.ArgumentMatchers.anyMap(), eq("collector:hips-test"), eq("request-1"));
    }

    @Test
    void verifiedServiceDelegationSuppliesTheEffectiveProducerIdentity() throws Exception {
        var event = Map.<String, Object>of("eventId", "service-event", "tenantId", "tenant-b");
        given(delivery.accept(org.mockito.ArgumentMatchers.anyMap(), eq("service:search-config"), eq("request-1"))).willReturn(event);
        given(delivery.status(event)).willReturn("PENDING");
        String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());
        String nonce = java.util.UUID.randomUUID().toString();
        String signature = com.socp.platform.tenant.security.ServiceRequestSignature.sign(
                "hips-request-fixture-secret-0123456789", "search-config", "POST", "/api/v1/events", "tenant-b", timestamp, nonce);
        mvc.perform(post("/api/v1/events").header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "request-1").header("X-Tenant-Id", "tenant-b")
                        .header("X-Socp-Service", "search-config").header("X-Socp-Service-Timestamp", timestamp)
                        .header("X-Socp-Service-Nonce", nonce).header("X-Socp-Service-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\"host\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eventId").value("service-event"));
        verify(delivery).accept(org.mockito.ArgumentMatchers.anyMap(), eq("service:search-config"), eq("request-1"));
    }

    @Test
    void rejectsOversizedRawBodyBeforeParsingOrDelivery() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer test-token")
                        .header("X-SOCP-Collector", "hips-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{".repeat(262145)))
                .andExpect(status().isPayloadTooLarge());
        org.mockito.Mockito.verifyNoInteractions(delivery, events);
    }

    @org.junit.jupiter.api.AfterEach
    void clearRequestTenant() { com.socp.platform.tenant.context.TenantContext.clear(); }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EndpointCollectionController controller;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private EndpointEventStore events;

    @MockitoBean
    private com.socp.hips.web.service.EndpointEventDelivery delivery;

    @Test
    void collectionUsesSharedEventStoreAndForwardsToCanonicalPipeline() throws Exception {
        Map<String, Object> event = Map.of(
                "eventId", "event-1", "tenantId", "tenant-a", "hostname", "web-01");
        given(delivery.accept(org.mockito.ArgumentMatchers.anyMap(), eq("collector:hips-test"), org.mockito.ArgumentMatchers.isNull())).willReturn(event);
        given(delivery.forward(event)).willReturn(true);
        given(delivery.status(event)).willReturn("DELIVERED");
        given(events.count()).willReturn(1L);
        mvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer test-token")
                        .header("X-SOCP-Collector", "hips-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("hostname", "web-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.eventId").value("event-1"))
                .andExpect(jsonPath("$.data.deliveryStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.forwarded").value(true));

        verify(delivery).forward(event);
    }

    @Test
    void queuedReceiptIsAcceptedEvenWhenSearchHasNotAcknowledged() throws Exception {
        Map<String, Object> event = Map.of("eventId", "queued-event", "tenantId", "tenant-a");
        given(delivery.accept(org.mockito.ArgumentMatchers.anyMap(), eq("collector:hips-test"), org.mockito.ArgumentMatchers.isNull())).willReturn(event);
        given(delivery.forward(event)).willReturn(false);
        given(delivery.status(event)).willReturn("PENDING");
        mvc.perform(post("/api/v1/events").header("Authorization", "Bearer test-token")
                        .header("X-SOCP-Collector", "hips-test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"web-01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.forwarded").value(false))
                .andExpect(jsonPath("$.data.deliveryStatus").value("PENDING"));
    }

    @Test
    void collectionRequiresCollectorOrServiceIdentityBeforeAdmission() throws Exception {
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("{".repeat(262145)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\"host\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/events").header("Authorization", "Bearer operator-token").header("X-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\"host\"}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(delivery);
    }

    @Test
    void structuredFieldValidationRunsBeforeDurableAdmission() throws Exception {
        for (var body : List.of(Map.of("hostname", " "),
                Map.of("hostname", "host", "output_fields", Map.of("proc.name", Map.of("nested", "value"))),
                Map.of("hostname", "host", "output_fields", Map.of("proc.cmdline", "x".repeat(4097))))) {
            mvc.perform(post("/api/v1/events").header("Authorization", "Bearer test-token")
                            .header("X-SOCP-Collector", "hips-test").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(delivery);
    }

    @Test
    void eventsReturnsPagedEnvelopeAndRejectsInvalidRanges() throws Exception {
        Map<String, Object> event = Map.of("eventId", "event-1", "hostname", "web-01");
        given(events.page(1, 1)).willReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(event), org.springframework.data.domain.PageRequest.of(0, 1), 1));

        var result = controller.events(1, 1);
        assertThat(result.data().items()).containsExactly(event);
        assertThat(result.data().total()).isEqualTo(1);
        assertThat(result.data().page()).isEqualTo(1);
        assertThat(result.data().size()).isEqualTo(1);

        assertThatThrownBy(() -> controller.events(1, 501))
                .isInstanceOf(ResponseStatusException.class);
    }
}
