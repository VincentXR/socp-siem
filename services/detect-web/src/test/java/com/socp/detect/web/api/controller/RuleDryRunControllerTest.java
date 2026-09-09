package com.socp.detect.web.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.service.RuleDryRunService;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.Watchlists;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuleDryRunControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RuleDryRunController(new RuleDryRunService()))
            .setValidator(new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator())).build();

    private Map<String, Object> rule(String type, String op, String value) {
        return Map.of("id", "dry-run-test", "name", "Dry run", "type", type, "severity", "HIGH",
                "keyField", "src_ip", "threshold", 2, "status", "DRAFT",
                "match", List.of(Map.of("field", "user", "op", op, "value", value)));
    }

    private Map<String, Object> event(String tenant) {
        return Map.of("timestamp", "2026-01-01T00:00:00Z", "source", "auth", "severity", "HIGH",
                "fields", Map.of("tenant_id", tenant, "user", "Admin", "src_ip", "192.0.2.1"));
    }

    @Test
    void usesServerCaseSemanticsAndAuthenticatedTenant() throws Exception {
        try (var ignored = TenantContext.open("dry-run-tenant")) {
            mvc.perform(post("/api/v1/rules/test").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("rules", List.of(rule("pattern", "eq", "admin")),
                                    "events", List.of(event("forged"))))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].matched").value(true))
                    .andExpect(jsonPath("$[0].alerts[0].evidence[0].fields.tenant_id").value("dry-run-tenant"));
        }
    }

    @Test
    void evaluatesWindowsWithFreshStateOnEveryRequest() throws Exception {
        try (var ignored = TenantContext.open("dry-run-tenant")) {
            var rules = List.of(rule("threshold", "eq", "admin"));
            mvc.perform(post("/api/v1/rules/test").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("rules", rules, "events", List.of(event("x"), event("x"))))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].alerts.length()").value(1));
            mvc.perform(post("/api/v1/rules/test").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("rules", rules, "events", List.of(event("x"))))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].matched").value(false));
        }
    }

    @Test
    void resolvesTheTenantWatchlistWithoutChangingIt() throws Exception {
        Watchlists.put("dry-run-tenant", "review-users", List.of("Admin"));
        try (var ignored = TenantContext.open("dry-run-tenant")) {
            mvc.perform(post("/api/v1/rules/test").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("rules", List.of(rule("pattern", "inlist", "review-users")),
                                    "events", List.of(event("other"))))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].matched").value(true));
        } finally { Watchlists.delete("dry-run-tenant", "review-users"); }
    }

    @Test
    void rejectsEmptySequencesAndInvalidRules() throws Exception {
        try (var ignored = TenantContext.open("dry-run-tenant")) {
            mvc.perform(post("/api/v1/rules/test").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("rules", List.of(rule("pattern", "eq", "admin")), "events", List.of()))))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/v1/rules/test").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("rules", List.of(rule("correlation", "eq", "admin")), "events", List.of(event("x"))))))
                    .andExpect(status().isBadRequest());
        }
    }
}
