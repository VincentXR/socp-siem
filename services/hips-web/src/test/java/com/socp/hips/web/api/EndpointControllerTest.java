package com.socp.hips.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.model.Endpoint;
import com.socp.hips.web.store.EndpointStore;
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

    @Test
    void listReturnsRegisteredAgents() throws Exception {
        given(store.list()).willReturn(List.of(
                Endpoint.register("web01", "10.0.0.5", "Ubuntu 22.04", "falco-0.39"),
                Endpoint.register("web02", "10.0.0.6", "Ubuntu 22.04", "falco-0.39")));

        mvc.perform(get("/api/v1/endpoints")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].hostname").value("web01"))
                .andExpect(jsonPath("$[0].status").value("ONLINE"))
                .andExpect(jsonPath("$[0].agentVersion").value("falco-0.39"));
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
                .andExpect(jsonPath("$.hostname").value("app01"))
                .andExpect(jsonPath("$.agentVersion").value("falco-0.40"))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void heartbeatOnUnknownEndpointYieldsEmptyBody() throws Exception {
        given(store.heartbeat("ghost")).willReturn(null);

        mvc.perform(post("/api/v1/endpoints/{id}/heartbeat", "ghost")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    void deleteReportsRemoval() throws Exception {
        given(store.delete("e-1")).willReturn(true);

        mvc.perform(delete("/api/v1/endpoints/{id}", "e-1")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));
    }
}
