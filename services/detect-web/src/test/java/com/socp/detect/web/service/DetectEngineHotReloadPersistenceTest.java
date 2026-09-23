package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.persistence.repository.DetectionRouteTopologyRepository;
import com.socp.detect.web.persistence.repository.RuleContentConflictRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.detect.web.persistence.repository.RuleRevisionRepository;
import com.socp.detect.web.persistence.store.RuleCatalogCoordinator;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.detect.web.routing.DetectionRoutingTopologyGuard;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DataJpaTest
@Import(RuleCatalogCoordinator.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectEngineHotReloadPersistenceTest {
    @Autowired RuleRepository rules;
    @Autowired RuleRevisionRepository revisions;
    @Autowired RuleContentConflictRepository conflicts;
    @Autowired DetectionRouteTopologyRepository topology;
    @Autowired RuleCatalogCoordinator catalog;
    @Autowired PlatformTransactionManager transactions;

    private DetectEngineService engine;

    @AfterEach
    void tearDown() {
        if (engine != null) engine.stop();
        TenantContext.clear();
    }

    @Test
    void committedActivationRebuildsTheLocalEngineWithARealTransaction() {
        TenantContext.set("hot-reload-test");
        var guard = new DetectionRoutingTopologyGuard(topology, rules, transactions, 8);
        var store = new RuleSpecStore(rules, revisions, conflicts, catalog, guard);
        store.save(Map.of(
                "id", "HOT-RELOAD", "name", "Hot reload fixture", "type", "pattern",
                "severity", "HIGH", "status", "DRAFT",
                "match", List.of(Map.of("field", "msg", "op", "contains", "value", "probe"))));
        engine = new DetectEngineService(store, new RecentAlertSink(10, null, null),
                mock(AlertForwarder.class), mock(RuleChangePublisher.class));
        engine.configureCommittedReloadTransaction(transactions);

        new TransactionTemplate(transactions).executeWithoutResult(
                status -> engine.activateRule("HOT-RELOAD"));

        @SuppressWarnings("unchecked")
        var loaded = (List<Map<String, Object>>) engine.stats().get("ruleStats");
        assertTrue(loaded.stream().anyMatch(rule -> "HOT-RELOAD".equals(rule.get("id"))),
                "the just-committed rule must be effective before the local activation returns");
    }
}
