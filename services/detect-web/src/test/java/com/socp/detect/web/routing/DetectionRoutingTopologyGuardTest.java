package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.repository.DetectionRouteTopologyRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.detect.web.persistence.repository.RuleRevisionRepository;
import com.socp.detect.web.persistence.repository.RuleContentConflictRepository;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionRoutingTopologyGuardTest {
    @Autowired DetectionRouteTopologyRepository topology;
    @Autowired RuleRepository rules;
    @Autowired RuleRevisionRepository revisions;
    @Autowired RuleContentConflictRepository conflicts;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void incompatibleWritesAndRestoreRollbackWhileContentTuningRemainsAvailable() {
        var guard = new DetectionRoutingTopologyGuard(topology, rules, transactions, 8);
        var store = new RuleSpecStore(rules, revisions, conflicts, guard);
        TenantContext.set("audit-topology");
        Map<String, Object> original = rule("CUSTOM", "custom.entity", "DISABLED");
        store.save(original);
        var active = new LinkedHashMap<>(original);
        active.put("status", "ACTIVE");
        store.save(active);
        guard.pinIfNeeded("audit-topology", () -> DetectionRoutingPlan.compile(store.list(), 8));

        assertEquals(409, assertThrows(ApiException.class, () -> store.save(original)).getCode());
        assertEquals(409, assertThrows(ApiException.class, () -> store.delete("CUSTOM")).getCode());
        assertEquals(409, assertThrows(ApiException.class, () -> store.restoreRevision("CUSTOM", 1)).getCode());
        assertEquals("ACTIVE", store.get("CUSTOM").get("status"));
        assertEquals(2, revisions.findByTenantIdAndRuleIdOrderByRevisionAsc("audit-topology", "CUSTOM").size());
        active.put("threshold", 9);
        assertDoesNotThrow(() -> store.save(active));
        assertTrue(guard.compatible("audit-topology", DetectionRoutingPlan.compile(store.list(), 8)));

        store.save(rule("NEW-DIMENSION", "another.entity", "DRAFT"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> store.save(rule("NEW-DIMENSION", "another.entity", "ACTIVE"))).getCode());
    }

    static Map<String, Object> rule(String id, String dimension, String status) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id); rule.put("name", id); rule.put("type", "threshold");
        rule.put("version", "1"); rule.put("severity", "HIGH"); rule.put("threshold", 3);
        rule.put("status", status); rule.put("groupBy", dimension); rule.put("routingField", dimension);
        rule.put("dataSources", List.of("auth"));
        rule.put("match", List.of(Map.of("field", "msg", "op", "contains", "value", "failed")));
        return rule;
    }
}
