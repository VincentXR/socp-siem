package com.socp.hips.web.api.controller;

import com.socp.hips.web.api.request.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.persistence.store.EndpointEventStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EndpointCollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "socp.security.dev-bypass=true")
class EndpointCollectionControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private EndpointEventStore events;

    @MockitoBean
    private SocpHttpClient http;

    @Test
    void collectionUsesSharedEventStoreAndForwardsToCanonicalPipeline() throws Exception {
        Map<String, Object> event = Map.of(
                "eventId", "event-1", "tenantId", "tenant-a", "hostname", "web-01");
        given(events.add(org.mockito.ArgumentMatchers.anyMap())).willReturn(event);
        given(events.list()).willReturn(List.of(event));
        given(http.post(eq(SocpService.SEARCH), eq("/api/v1/ingest"),
                org.mockito.ArgumentMatchers.anyString(), eq(SocpHttpClient.NDJSON), eq(5000)))
                .willReturn(new ServiceCall(SocpService.SEARCH, "http://search", true, 202,
                        "accepted", null, 1, false, 1));

        mvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("hostname", "web-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.eventId").value("event-1"))
                .andExpect(jsonPath("$.forwarded").value(true));

        verify(http).post(eq(SocpService.SEARCH), eq("/api/v1/ingest"),
                contains("\"tenantId\":\"tenant-a\""), eq(SocpHttpClient.NDJSON), eq(5000));
    }
}
