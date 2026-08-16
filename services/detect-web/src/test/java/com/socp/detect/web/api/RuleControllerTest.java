package com.socp.detect.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.engine.AlertStreamHub;
import com.socp.detect.web.service.DetectEngineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RuleController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class RuleControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private DetectEngineService engine;

    @MockitoBean
    private AlertStreamHub streamHub;

    @Test
    void updateInjectsPathIdBeforeDelegating() throws Exception {
        given(engine.updateRule(any())).willAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(put("/api/v1/rules/{id}", "AUTH-BRUTE")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LinkedHashMap<>(Map.of(
                                "name", "SSH brute force",
                                "type", "threshold",
                                "threshold", 5)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("AUTH-BRUTE"))
                .andExpect(jsonPath("$.name").value("SSH brute force"));

        verify(engine).updateRule(org.mockito.ArgumentMatchers.argThat(rule ->
                "AUTH-BRUTE".equals(rule.get("id"))));
    }

    @Test
    void bulkIngestCountsMalformedAndBackpressuredRows() throws Exception {
        given(engine.ingest(any())).willReturn(true, false);
        given(engine.stats()).willReturn(Map.of("queueLoad", 2));
        String body = ""
                + "{\"source\":\"auth\",\"msg\":\"failed login\"}\n"
                + "{not-json}\n"
                + "{\"source\":\"auth\",\"msg\":\"failed login\"}\n";

        mvc.perform(post("/api/v1/ingest/bulk")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_NDJSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.queueLoad").value(2));

        verify(engine, times(2)).ingest(any());
    }

    @Test
    void reloadReturnsCurrentRuleCount() throws Exception {
        given(engine.listRules()).willReturn(java.util.List.of(Map.of("id", "R-1"), Map.of("id", "R-2")));

        mvc.perform(post("/api/v1/rules/reload")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reloaded").value(true))
                .andExpect(jsonPath("$.rules").value(2));

        verify(engine).reload();
    }
}
