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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RuleController.class, DetectionRuntimeController.class})
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class RuleControllerTest {

    @Test void ruleReadsExposeTheSpecificValidatorAndMissingPreconditionsNeverReachTheEngine() throws Exception {
        var current = Map.<String, Object>of("id", "custom", "revisionToken", "a".repeat(64));
        given(engine.getRule("custom")).willReturn(current);
        mvc.perform(get("/api/v1/rules/custom").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revisionToken").value("a".repeat(64)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("ETag", "\"" + "a".repeat(64) + "\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        org.mockito.Mockito.clearInvocations(engine);
        for (var request : java.util.List.of(
                put("/api/v1/rules/custom").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"rule\",\"type\":\"pattern\",\"severity\":\"HIGH\"}"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/rules/custom"),
                post("/api/v1/rules/custom/activate"), post("/api/v1/rules/custom/revisions/1/restore"))) {
            mvc.perform(request.header("Authorization", BEARER).header("X-Role", "admin"))
                    .andExpect(status().is(428)).andExpect(jsonPath("$.code").value(428))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        }
        org.mockito.Mockito.verifyNoInteractions(engine);
    }

    @Test void restoreForwardsAnExplicitAbsenceCheckAndStaleUpdatesKeepTheErrorEnvelope() throws Exception {
        given(engine.restoreRuleRevision(org.mockito.ArgumentMatchers.eq("custom"), org.mockito.ArgumentMatchers.eq(1L), any()))
                .willAnswer(invocation -> {
                    assertEquals(true, ((com.socp.detect.web.model.RuleWriteCondition) invocation.getArgument(2)).absent());
                    return Map.of("id", "custom", "revisionToken", "b".repeat(64));
                });
        mvc.perform(post("/api/v1/rules/custom/revisions/1/restore").header("Authorization", BEARER)
                        .header("X-Role", "admin").header("If-None-Match", "*"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revisionToken").value("b".repeat(64)));
        given(engine.updateRule(any(), any())).willThrow(com.socp.platform.error.exception.ApiException.of(412, "changed"));
        mvc.perform(put("/api/v1/rules/custom").header("Authorization", BEARER).header("X-Role", "analyst")
                        .header("If-Match", "\"old\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rule\",\"type\":\"pattern\",\"severity\":\"HIGH\"}"))
                .andExpect(status().is(412)).andExpect(jsonPath("$.code").value(412));
    }

    @Test
    void historyRoutesExposePagedMetadataAndSeparateFullDetails() throws Exception {
        var summary = Map.<String, Object>of("ruleId", "custom", "revision", 101L, "source", "EDIT");
        given(engine.ruleRevisionPage("custom", 2, 20)).willReturn(new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(summary), org.springframework.data.domain.PageRequest.of(1, 20), 121));
        given(engine.ruleRevision("custom", 101)).willReturn(Map.of("revision", 101L, "spec", Map.of("name", "old")));
        given(engine.ruleContentConflictPage(1, 20)).willReturn(new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(Map.of("ruleId", "custom"))));
        mvc.perform(get("/api/v1/rules/custom/revisions?page=2&size=20").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(121))
                .andExpect(jsonPath("$.data.page").value(2)).andExpect(jsonPath("$.data.items[0].revision").value(101))
                .andExpect(jsonPath("$.data.items[0].spec").doesNotExist());
        mvc.perform(get("/api/v1/rules/custom/revisions/101").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.spec.name").value("old"));
        mvc.perform(get("/api/v1/rules/content-conflicts?page=1").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].ruleId").value("custom"));
        given(engine.ruleRevision("custom", 102)).willThrow(com.socp.platform.error.exception.ApiException.notFound("missing revision"));
        mvc.perform(get("/api/v1/rules/custom/revisions/102").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void historyRetainsLegacyArraysAndPropagatesExplicitOverflow() throws Exception {
        given(engine.listRuleRevisions("custom")).willReturn(java.util.List.of(Map.of("revision", 1, "spec", Map.of("name", "old"))));
        given(engine.ruleContentConflicts()).willReturn(java.util.List.of(Map.of("ruleId", "custom")));
        mvc.perform(get("/api/v1/rules/custom/revisions").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].spec.name").value("old"));
        mvc.perform(get("/api/v1/rules/content-conflicts").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].ruleId").value("custom"));
        given(engine.listRuleRevisions("large")).willThrow(com.socp.platform.error.exception.ApiException.badRequest("use page and size"));
        mvc.perform(get("/api/v1/rules/large/revisions").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void historyRejectsInvalidBoundsAndViewerAccessBeforeCallingEngine() throws Exception {
        for (String route : java.util.List.of("/api/v1/rules/custom/revisions", "/api/v1/rules/content-conflicts")) {
            for (String query : java.util.List.of("?page=0", "?size=20", "?page=1&size=0", "?page=1&size=101")) {
                mvc.perform(get(route + query).header("Authorization", BEARER).header("X-Role", "analyst"))
                        .andExpect(status().isBadRequest());
            }
            mvc.perform(get(route + "?page=1").header("Authorization", BEARER).header("X-Role", "viewer"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/rules/custom/revisions/0").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rules/custom/revisions/1").header("Authorization", BEARER).header("X-Role", "viewer"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(engine);
    }

    @Test
    void catalogFiltersAreAppliedBeforePagination() throws Exception {
        var row = Map.<String, Object>of("id", "rule-501", "name", "Unicode 异常");
        given(engine.searchRules(2, 20, "异常_%", "DISABLED", "users_%", "alias"))
                .willReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(row),
                        org.springframework.data.domain.PageRequest.of(1, 20), 21));
        mvc.perform(get("/api/v1/rules").header("Authorization", BEARER).header("X-Role", "analyst").param("page", "2").param("size", "20")
                        .param("q", " 异常_% ").param("status", "disabled")
                        .param("reference", "users_%").param("referenceAlias", "alias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("rule-501"))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.page").value(2));
    }

    @Test
    void compactCatalogRoutesDoNotResolveAsRuleIds() throws Exception {
        var row = Map.<String, Object>of("id", "rule-501", "name", "Late rule", "status", "ACTIVE");
        given(engine.ruleOptions(1, 50, "late")).willReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(row)));
        given(engine.activeRuleTechniques()).willReturn(java.util.List.of("T1110"));
        given(engine.lookupRules(java.util.List.of("rule-501"))).willReturn(java.util.List.of(row));
        mvc.perform(get("/api/v1/rules/options").header("Authorization", BEARER).header("X-Role", "analyst").param("q", "late"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("rule-501"));
        mvc.perform(get("/api/v1/rules/active-techniques").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value("T1110"));
        mvc.perform(post("/api/v1/rules/lookup").header("Authorization", BEARER).header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"rule-501\",\"rule-501\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("rule-501"));
        verify(engine, org.mockito.Mockito.never()).getRule(any());
    }

    @Test
    void catalogRejectsOversizedOrInvalidQueriesBeforeCallingEngine() throws Exception {
        for (String path : java.util.List.of("/api/v1/rules?page=0", "/api/v1/rules?page=1&size=501",
                "/api/v1/rules?status=unknown", "/api/v1/rules/options?size=101", "/api/v1/rules/options?page=0")) {
            mvc.perform(get(path).header("Authorization", BEARER).header("X-Role", "analyst")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/rules").header("Authorization", BEARER).header("X-Role", "analyst").param("q", "q".repeat(257))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rules").header("Authorization", BEARER).header("X-Role", "analyst").param("reference", "r".repeat(4097))).andExpect(status().isBadRequest());
        for (Object ids : java.util.List.of(java.util.List.of(), java.util.List.of(" "),
                java.util.List.of("r".repeat(129)), java.util.Collections.nCopies(101, "rule"))) {
            mvc.perform(post("/api/v1/rules/lookup").header("Authorization", BEARER).header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("ids", ids))))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(engine);
    }

    @Test
    void directRuleLookupPreservesNotFoundEnvelope() throws Exception {
        given(engine.getRule("absent")).willThrow(com.socp.platform.error.exception.ApiException.notFound("rule not found"));
        mvc.perform(get("/api/v1/rules/absent").header("Authorization", BEARER).header("X-Role", "analyst"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }

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
        given(engine.updateRule(any(), any())).willAnswer(invocation -> {
            var spec = new LinkedHashMap<String, Object>(invocation.getArgument(0));
            spec.put("revisionToken", "a".repeat(64));
            return spec;
        });

        mvc.perform(put("/api/v1/rules/{id}", "AUTH-BRUTE").header("If-Match", "\"" + "a".repeat(64) + "\"")
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
                "AUTH-BRUTE".equals(rule.get("id"))), any());
    }

    @Test
    void updatePreservesNestedExtensionMetadataAndConditionWhitespace() throws Exception {
        given(engine.updateRule(any(), any())).willAnswer(invocation -> {
            var spec = new LinkedHashMap<String, Object>(invocation.getArgument(0));
            spec.put("revisionToken", "a".repeat(64));
            return spec;
        });
        mvc.perform(put("/api/v1/rules/{id}", "preserved").header("If-Match", "\"" + "a".repeat(64) + "\"")
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
    void manualIngressRejectsOversizedBytesBeforeParsingOrEngineAdmission() throws Exception {
        mvc.perform(post("/api/v1/ingest").header("Authorization", BEARER).header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON).content("{".repeat(256 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
        mvc.perform(post("/api/v1/ingest/bulk").header("Authorization", BEARER).header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_NDJSON)
                        .content("\u00e9".repeat(8 * 1024 * 1024 + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
        org.mockito.Mockito.verifyNoInteractions(engine);
    }

    @Test
    void manualIngressKeepsRoleChecksAheadOfBodyConversion() throws Exception {
        mvc.perform(post("/api/v1/ingest").header("Authorization", BEARER).header("X-Role", "viewer")
                        .contentType(MediaType.APPLICATION_JSON).content("{".repeat(256 * 1024 + 1)))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(engine);
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
