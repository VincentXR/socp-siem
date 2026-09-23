package com.socp.hips.web.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.domain.Endpoint;
import com.socp.hips.web.persistence.store.EndpointStore;
import com.socp.hips.web.persistence.store.EndpointEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HIPS 端点管理 API 切片测试（EndpointStore 被 mock）。
 */
@WebMvcTest(EndpointController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class EndpointControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private EndpointStore store;

    @MockitoBean
    private EndpointEventStore events;

    @Test
    void independentDetailAndHistoryRequireAnOwnedEndpoint() throws Exception {
        Endpoint endpoint = Endpoint.register("web01", "203.0.113.7", "Linux", "agent");
        given(store.get(endpoint.id())).willReturn(endpoint);
        given(events.forHostname("web01", 2, 20)).willReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(Map.<String, Object>of("eventId", "old-event")), org.springframework.data.domain.PageRequest.of(1, 20), 21));
        mvc.perform(get("/api/v1/endpoints/" + endpoint.id()).header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hostname").value("web01"));
        mvc.perform(get("/api/v1/endpoints/" + endpoint.id() + "/events?page=2&size=20").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.items[0].eventId").value("old-event"));
        for (String suffix : List.of("", "/events")) {
            mvc.perform(get("/api/v1/endpoints/missing" + suffix).header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/endpoints/" + endpoint.id() + suffix).header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "viewer"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/endpoints/" + endpoint.id() + "/events?size=501").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(events, org.mockito.Mockito.times(1)).forHostname(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void listReturnsPagedEnvelope() throws Exception {
        given(store.page(1, 500, "")).willReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(
                        Endpoint.register("web01", "10.0.0.5", "Ubuntu 22.04", "falco-0.39"),
                        Endpoint.register("web02", "10.0.0.6", "Ubuntu 22.04", "falco-0.39")),
                org.springframework.data.domain.PageRequest.of(0, 500), 2));

        mvc.perform(get("/api/v1/endpoints")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(500))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].hostname").value("web01"))
                .andExpect(jsonPath("$.data.items[0].status").value("ONLINE"))
                .andExpect(jsonPath("$.data.items[0].agentVersion").value("falco-0.39"));
    }

    @Test
    void listRejectsSizeAboveConfiguredLimit() throws Exception {
        mvc.perform(get("/api/v1/endpoints")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .param("page", "1")
                        .param("size", "501"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relatedLookupUsesExactNormalizedKeysAndBoundedPages() throws Exception {
        given(store.related(2, 20, "203.0.113.7", "Target-HOST")).willReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(Endpoint.register("Target-HOST", "203.0.113.7", "Linux", "agent")),
                org.springframework.data.domain.PageRequest.of(1, 20), 21));
        mvc.perform(get("/api/v1/endpoints/related").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst")
                        .param("ip", " 203.0.113.7 ").param("hostname", " Target-HOST ").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(21)).andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].hostname").value("Target-HOST"));
        mvc.perform(get("/api/v1/endpoints/related").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "viewer").param("ip", "203.0.113.7"))
                .andExpect(status().isForbidden());
        for (var params : List.of(Map.of("hostname", " "), Map.of("ip", "x".repeat(65)),
                Map.of("hostname", "x".repeat(129)), Map.of("ip", "203.0.113.7", "size", "501"),
                Map.of("ip", "203.0.113.7", "page", "0"))) {
            var request = get("/api/v1/endpoints/related").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst");
            params.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest());
        }
    }

    @Test
    void registerReturnsCreatedEndpoint() throws Exception {
        given(store.save(any(Endpoint.class))).willAnswer(inv -> inv.getArgument(0));

        Map<String, String> body = Map.of(
                "hostname", "app01", "ip", "10.0.0.30", "os", "RHEL 9", "agentVersion", "falco-0.40");

        mvc.perform(post("/api/v1/endpoints")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hostname").value("app01"))
                .andExpect(jsonPath("$.data.agentVersion").value("falco-0.40"))
                .andExpect(jsonPath("$.data.status").value("ONLINE"))
                .andExpect(jsonPath("$.data.id").isNotEmpty());
    }

    @Test
    void heartbeatOnUnknownEndpointYieldsEmptyData() throws Exception {
        given(store.heartbeat("ghost")).willReturn(null);

        mvc.perform(post("/api/v1/endpoints/{id}/heartbeat", "ghost")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void deleteReportsRemoval() throws Exception {
        given(store.delete("e-1")).willReturn(true);

        mvc.perform(delete("/api/v1/endpoints/{id}", "e-1")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(true));
    }

    @Test
    void ingestEventReturnsAcceptedRecord() throws Exception {
        Map<String, Object> event = Map.of("eventId", "event-1", "hostname", "web-01");
        given(events.add(any())).willReturn(event);
        given(events.count()).willReturn(1L);

        mvc.perform(post("/api/v1/endpoints/events")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("hostname", "web-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.eventId").value("event-1"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void eventsAndStatsExposeEndpointData() throws Exception {
        Endpoint online = Endpoint.register("web01", "10.0.0.5", "Linux", "agent-1");
        Endpoint offline = new Endpoint("e-2", "web02", "10.0.0.6", "Linux", "agent-1", "OFFLINE", null);
        given(store.stats()).willReturn(Map.of(
                "total", 2L,
                "online", 1L,
                "byStatus", Map.of("ONLINE", 1L, "OFFLINE", 1L)));
        given(events.list()).willReturn(List.of(
                Map.of("type", "process"), Map.of("type", "network"), Map.of("eventId", "event-3")));
        given(events.count()).willReturn(503L);
        given(events.page(1, 2)).willReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(Map.of("type", "process"), Map.of("type", "network")),
                org.springframework.data.domain.PageRequest.of(0, 2), 3));

        mvc.perform(get("/api/v1/endpoints/events").param("page", "1").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(3));

        mvc.perform(get("/api/v1/endpoints/stats")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.online").value(1))
                .andExpect(jsonPath("$.data.events").value(503))
                .andExpect(jsonPath("$.data.eventByTypeScope").value("LATEST_EVENTS"))
                .andExpect(jsonPath("$.data.eventByTypeSampleSize").value(3))
                .andExpect(jsonPath("$.data.eventByTypeSampleLimit").value(200))
                .andExpect(jsonPath("$.data.eventByType.process").value(1))
                .andExpect(jsonPath("$.data.eventByType.UNKNOWN").value(1));
    }
}
