package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.RuleEntity;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class RuleCatalogPersistenceTest {
    @Autowired protected RuleRepository repository;

    @BeforeEach void tenant() { TenantContext.set("catalog-a"); }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void pagesAndSearchIncludeRulesAfterTheFirstFiveHundred() {
        for (int i = 0; i < 501; i++) rule("catalog-a", String.format("rule-%04d", i), "Rule " + i, "DRAFT", Map.of());
        var second = repository.findByTenantId("catalog-a", PageRequest.of(1, 500, Sort.by("ruleId")));
        assertEquals(501, second.getTotalElements());
        assertEquals(List.of("rule-0500"), second.map(RuleEntity::getId).getContent());
        var found = repository.search("catalog-a", "rule-0500", "DRAFT", "", "", PageRequest.of(0, 20));
        assertEquals(1, found.getTotalElements());
        assertEquals("rule-0500", found.getContent().getFirst().getId());
    }

    @Test
    void filtersTreatPercentUnderscoreAndUnicodeAsLiteralText() {
        rule("catalog-a", "literal", "异常 100% _ admin", "DRAFT", Map.of());
        rule("catalog-a", "ordinary", "100 percent x admin", "ACTIVE", Map.of());
        assertEquals(1, repository.search("catalog-a", "% _", "", "", "", PageRequest.of(0, 20)).getTotalElements());
        assertEquals(1, repository.search("catalog-a", "异常", "DRAFT", "", "", PageRequest.of(0, 20)).getTotalElements());
        assertEquals(0, repository.search("catalog-a", "异常", "ACTIVE", "", "", PageRequest.of(0, 20)).getTotalElements());
    }

    @Test
    void referenceLookupMatchesExactNamesAndExecutableConditionsAcrossGroups() {
        rule("catalog-a", "nested", "Nested", "ACTIVE", Map.of("matchAny", List.of(List.of(
                Map.of("field", "user", "op", "notinlist", "value", "users_%你好")))));
        rule("catalog-a", "prefix", "Prefix", "ACTIVE", Map.of("match", List.of(
                Map.of("field", "user", "op", "inlist", "value", "users_%你好-suffix"))));
        rule("catalog-a", "metadata", "Unrelated metadata", "ACTIVE", Map.of("evidence",
                Map.of("op", "inlist", "value", "users_%你好")));
        var result = repository.search("catalog-a", "", "", token(" USERS_%你好 "), "", PageRequest.of(0, 20));
        assertEquals(List.of("nested"), result.map(RuleEntity::getId).getContent());
    }

    @Test
    void optionsAndLookupReturnOnlyTheOwningTenantsCompactMetadata() {
        rule("catalog-a", "same-id", "Owner A", "ACTIVE", Map.of("message", "private detection logic"));
        TenantContext.runWith("catalog-b", () -> rule("catalog-b", "same-id", "Owner B", "DRAFT", Map.of()));
        var options = repository.options("catalog-a", "owner", PageRequest.of(0, 50));
        assertEquals(1, options.getTotalElements());
        assertEquals("Owner A", options.getContent().getFirst().get("name"));
        assertFalse(options.getContent().getFirst().containsKey("spec"));
        assertFalse(options.getContent().getFirst().containsKey("message"));
        var lookup = repository.lookup("catalog-a", List.of("same-id", "missing"));
        assertEquals(1, lookup.size());
        assertEquals("ACTIVE", lookup.getFirst().get("status"));
        assertTrue(repository.findByRuleIdAndTenantId("same-id", "catalog-c").isEmpty());
    }

    @Test
    void editingSpecUpdatesSearchStatusReferencesAndCoverageInTheSameRow() throws Exception {
        var row = rule("catalog-a", "edit", "Before", "ACTIVE", Map.of("mitre", "T1110"));
        row.setSpec(Json.mapper().writeValueAsString(Map.of("id", "edit", "name", "After", "type", "pattern",
                "status", "DISABLED", "mitre", "T1078", "match", List.of(Map.of("op", "inlist", "value", "new-list")))));
        repository.saveAndFlush(row);
        assertEquals(0, repository.search("catalog-a", "before", "", "", "", PageRequest.of(0, 20)).getTotalElements());
        assertEquals(1, repository.search("catalog-a", "after", "DISABLED", token("new-list"), "", PageRequest.of(0, 20)).getTotalElements());
        assertTrue(repository.activeTechniques("catalog-a", PageRequest.of(0, 100)).isEmpty());
    }

    @Test
    void coverageIncludesOnlyActiveRulesAndAllDeclaredTechniqueIds() {
        rule("catalog-a", "active", "Active", "ACTIVE", Map.of("mitre", "T1110",
                "mitreIds", List.of("T1078", "T1110.001", "T1110")));
        rule("catalog-a", "draft", "Draft", "DRAFT", Map.of("mitre", "T1059"));
        TenantContext.runWith("catalog-b", () -> rule("catalog-b", "other", "Other tenant", "ACTIVE", Map.of("mitre", "T9999")));
        assertEquals(List.of("T1078\nT1110\nT1110.001"), repository.activeTechniques("catalog-a", PageRequest.of(0, 100)));
    }

    protected RuleEntity rule(String tenant, String id, String name, String status, Map<String, Object> extra) {
        var row = new RuleEntity();
        row.setStorageId(UUID.randomUUID().toString());
        row.setId(id);
        row.setTenantId(tenant);
        var spec = new java.util.LinkedHashMap<String, Object>(extra);
        spec.putAll(Map.of("id", id, "name", name, "type", "pattern", "status", status));
        try { row.setSpec(Json.mapper().writeValueAsString(spec)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        return repository.saveAndFlush(row);
    }

    private static String token(String name) { return com.socp.detect.web.model.RuleCatalogMetadata.referenceToken(name); }
}
