package com.socp.detect.web.api.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.engine.AlertStreamHub;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.socp.rule.model.SecurityEvent;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RuleController.class, DetectionRuntimeController.class})
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

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

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
                                "severity", "HIGH",
                                "threshold", 5)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("AUTH-BRUTE"))
                .andExpect(jsonPath("$.data.name").value("SSH brute force"));

        verify(engine).updateRule(org.mockito.ArgumentMatchers.argThat(rule ->
                "AUTH-BRUTE".equals(rule.get("id"))));
    }

    @Test
    void updatePreservesNestedExtensionMetadataAndConditionWhitespace() throws Exception {
        given(engine.updateRule(any())).willAnswer(invocation -> invocation.getArgument(0));
        mvc.perform(put("/api/v1/rules/{id}", "preserved")
                        .header("Authorization", BEARER).header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"wrong-id","name":"Roundtrip","type":"pattern","severity":"HIGH",
                                 "evidence":{"fields":["host","msg"]},
                                 "alert":{"title":"Title","grouping":{"strategy":"source"}},
                                 "lateEventPolicy":{"allowedLateness":"90s","handling":"ACCEPT","source":"content-pack"},
                                 "match":[{"field":"msg","op":"eq","value":" padded ","annotations":{"owner":"SOC"}}],
                                 "allowlist":[{"field":"host","op":"eq","value":"trusted","source":"import"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("preserved"))
                .andExpect(jsonPath("$.data.evidence.fields[1]").value("msg"))
                .andExpect(jsonPath("$.data.alert.grouping.strategy").value("source"))
                .andExpect(jsonPath("$.data.lateEventPolicy.source").value("content-pack"))
                .andExpect(jsonPath("$.data.match[0].value").value(" padded "))
                .andExpect(jsonPath("$.data.match[0].annotations.owner").value("SOC"))
                .andExpect(jsonPath("$.data.whitelist[0].source").value("import"));
    }

    @Test
    void extensionPreservationDoesNotDisableTypedValidation() throws Exception {
        mvc.perform(put("/api/v1/rules/{id}", "invalid")
                        .header("Authorization", BEARER).header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Invalid","type":"pattern","severity":"HIGH","metadata":{"keep":true},
                                 "match":[{"field":"msg","op":"eq","value":"","annotations":{"keep":true}}]}
                                """))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(engine);
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
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected").value(2))
                .andExpect(jsonPath("$.data.queueLoad").value(2));

        verify(engine, times(2)).ingest(any());
    }

    @Test
    void typedIngestContractPreservesBackpressureResponse() throws Exception {
        given(engine.ingest(any())).willReturn(false);
        given(engine.stats()).willReturn(Map.of("queueLoad", 9));

        mvc.perform(post("/api/v1/ingest")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"typed-ingest","source":"auth","host":"web-1",
                                 "severity":"HIGH","msg":"failed login",
                                 "fields":{"src_ip":"198.51.100.10","attempts":3}}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.error").value("queue_full"))
                .andExpect(jsonPath("$.data.queueLoad").value(9));

        verify(engine).ingest(org.mockito.ArgumentMatchers.argThat(event ->
                "3".equals(event.fields().get("attempts"))
                        && "failed login".equals(event.fields().get("msg"))));
    }

    @Test
    void idempotencyKeyKeepsRetryEventIdentityStable() throws Exception {
        given(engine.ingest(any())).willReturn(true);
        given(engine.stats()).willReturn(Map.of("queueLoad", 0));
        String body = "{\"source\":\"auth\",\"msg\":\"failed login\"}";

        mvc.perform(post("/api/v1/ingest")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .header("Idempotency-Key", "collector-batch-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/ingest")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .header("Idempotency-Key", "collector-batch-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/ingest")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .header("Idempotency-Key", "collector-batch-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"auth\",\"msg\":\"different event\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<SecurityEvent> events = org.mockito.ArgumentCaptor.forClass(SecurityEvent.class);
        verify(engine, times(3)).ingest(events.capture());
        assertEquals(events.getAllValues().get(0).id(), events.getAllValues().get(1).id());
        org.junit.jupiter.api.Assertions.assertNotEquals(events.getAllValues().get(0).id(),
                events.getAllValues().get(2).id());
    }

    @Test
    void reloadIsAReceiptThatTheReloadWasSubmittedNotAppliedEverywhere() throws Exception {
        given(engine.ruleCount()).willReturn(2L);

        mvc.perform(post("/api/v1/rules/reload")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                // reloaded=true is the submission receipt. Each worker replica
                // drains its in-flight work and rebuilds independently, so this
                // response can never claim the new ruleset is live everywhere.
                .andExpect(jsonPath("$.data.reloaded").value(true))
                .andExpect(jsonPath("$.data.effective").value("async-per-replica"))
                .andExpect(jsonPath("$.data.rules").value(2));

        verify(engine).reload();
        verify(engine).ruleCount();
        org.mockito.Mockito.verifyNoMoreInteractions(engine);
    }

    @Test
    void validateReportsPartitionLocalAdvisoriesWithoutRejectingTheRule() throws Exception {
        mvc.perform(post("/api/v1/rules/validate")
                        .header("Authorization", BEARER).header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"validate-advisory","name":"User brute force","type":"threshold",
                                 "severity":"HIGH","threshold":5,"window":"5m","groupBy":"user",
                                 "dataSources":["auth"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.errors.length()").value(0))
                .andExpect(jsonPath("$.data.advisories[0]")
                        .value(org.hamcrest.Matchers.containsString("route by 'src_ip'")))
                .andExpect(jsonPath("$.data.spec.groupBy").value("user"))
                .andExpect(jsonPath("$.data.spec.routingField").value("user"));

        org.mockito.Mockito.verifyNoInteractions(engine);
    }

    @Test
    void validateLeavesAdvisoriesEmptyWhenGroupingMatchesTheRoutingDimension() throws Exception {
        mvc.perform(post("/api/v1/rules/validate")
                        .header("Authorization", BEARER).header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"validate-clean","name":"Source brute force","type":"threshold",
                                 "severity":"HIGH","threshold":5,"window":"5m","groupBy":"src_ip",
                                 "dataSources":["auth"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.advisories.length()").value(0));
    }

    @Test
    void validateStillReportsAdvisoriesAlongsideErrors() throws Exception {
        mvc.perform(post("/api/v1/rules/validate")
                        .header("Authorization", BEARER).header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"No threshold","type":"threshold","severity":"HIGH",
                                 "window":"5m","groupBy":"user","dataSources":["auth"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.errors[0]").value("missing id"))
                .andExpect(jsonPath("$.data.advisories[0]")
                        .value(org.hamcrest.Matchers.containsString("'user'")));
    }

    @Test
    void directActivationIsRejectedInCreateAndUpdateContracts() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "name", "active rule", "type", "pattern", "severity", "HIGH", "status", "ACTIVE"));

        mvc.perform(post("/api/v1/rules")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/rules/{id}", "R-1")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}
