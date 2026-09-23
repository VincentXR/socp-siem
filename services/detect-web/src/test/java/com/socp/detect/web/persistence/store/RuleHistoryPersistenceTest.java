package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.RuleContentConflictEntity;
import com.socp.detect.web.persistence.entity.RuleRevisionEntity;
import com.socp.detect.web.persistence.repository.RuleContentConflictRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.detect.web.persistence.repository.RuleRevisionRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.socp.detect.web.persistence.store.RuleHistoryPersistenceTest$SqlInspector"})
class RuleHistoryPersistenceTest {
    @Autowired RuleRepository rules;
    @Autowired RuleRevisionRepository revisions;
    @Autowired RuleContentConflictRepository conflicts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private RuleSpecStore store;

    public static final class SqlInspector implements StatementInspector {
        static final ConcurrentLinkedQueue<String> statements = new ConcurrentLinkedQueue<>();
        @Override public String inspect(String sql) { statements.add(sql); return sql; }
    }

    @BeforeEach void prepare() {
        TenantContext.set("history-a");
        store = new RuleSpecStore(rules, revisions, conflicts, new RuleCatalogCoordinator(jdbc, transactions));
        store.count();
        SqlInspector.statements.clear();
    }

    @AfterEach void cleanup() { TenantContext.clear(); SqlInspector.statements.clear(); }

    @Test void largeHistoriesPageMetadataWithoutLoadingRuleBodies() {
        for (int i = 1; i <= 201; i++) revision("history-a", "custom", i, "not valid JSON");
        TenantContext.runWith("history-b", () -> revision("history-b", "custom", 202, "{}"));
        revisions.flush();
        SqlInspector.statements.clear();

        var page = store.revisionPage("custom", 2, 100);
        assertEquals(201, page.getTotalElements());
        assertEquals(100, page.getNumberOfElements());
        assertEquals(101L, page.getContent().getFirst().get("revision"));
        assertEquals(2L, page.getContent().getLast().get("revision"));
        assertTrue(page.getContent().stream().noneMatch(row -> row.containsKey("spec")));
        var selects = SqlInspector.statements.stream().filter(sql -> sql.contains(" from t_rule_revision ")).toList();
        assertFalse(selects.isEmpty());
        assertTrue(selects.stream().noneMatch(sql -> sql.contains(".spec")), selects.toString());
        assertEquals(1L, store.revisionPage("custom", 3, 100).getContent().getFirst().get("revision"));
        assertTrue(store.revisionPage("custom", 4, 100).isEmpty());
        assertThrows(ApiException.class, () -> store.revisions("custom"));
    }

    @Test void legacyHistoryRetainsAscendingFullSpecsAndSingleDetailsRemainTenantScoped() {
        revision("history-a", "custom", 1, "{\"name\":\"first\"}");
        revision("history-a", "custom", 2, "{\"name\":\"second\"}");
        TenantContext.runWith("history-b", () -> revision("history-b", "custom", 3, "{\"name\":\"private\"}"));
        var legacy = store.revisions("custom");
        assertEquals(List.of(1L, 2L), legacy.stream().map(row -> row.get("revision")).toList());
        assertEquals(java.util.Map.of("name", "first"), legacy.getFirst().get("spec"));
        assertEquals(java.util.Map.of("name", "second"), store.revision("custom", 2).get("spec"));
        assertNull(store.revision("custom", 3));
        assertTrue(store.revisionPage("absent", 1, 20).isEmpty());
    }

    @Test void conflictPagesRemainStableForEqualTimestampsAndDoNotCrossTenants() {
        for (int i = 1; i <= 101; i++) conflict("history-a", i, "PENDING");
        conflict("history-a", 102, "RESOLVED");
        TenantContext.runWith("history-b", () -> conflict("history-b", 103, "PENDING"));
        var first = store.contentConflictPage(1, 100);
        var last = store.contentConflictPage(2, 100);
        assertEquals(101, first.getTotalElements());
        assertEquals(100, first.getNumberOfElements());
        assertEquals("rule-101", last.getContent().getFirst().get("ruleId"));
        assertThrows(ApiException.class, store::contentConflicts);
        assertTrue(store.contentConflictPage(3, 100).isEmpty());
    }

    @Test void invalidPageSizesAndRevisionIdsFailBeforeAQuery() {
        for (int[] page : List.of(new int[]{0, 20}, new int[]{1, 0}, new int[]{1, 101})) {
            assertThrows(ApiException.class, () -> store.revisionPage("custom", page[0], page[1]));
            assertThrows(ApiException.class, () -> store.contentConflictPage(page[0], page[1]));
        }
        assertThrows(ApiException.class, () -> store.revision("custom", 0));
        assertTrue(SqlInspector.statements.isEmpty());
    }

    private void revision(String tenant, String rule, long number, String spec) {
        RuleRevisionEntity row = new RuleRevisionEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setRuleId(rule);
        row.setRevision(number);
        row.setSpec(spec);
        row.setSource("EDIT");
        row.setStatus("DRAFT");
        row.setChangedAt(Instant.parse("2026-01-01T00:00:00Z"));
        revisions.save(row);
    }

    private void conflict(String tenant, int number, String status) {
        RuleContentConflictEntity row = new RuleContentConflictEntity();
        row.setId(String.format("%036d", number));
        row.setTenantId(tenant);
        row.setRuleId("rule-" + number);
        row.setContentPack("fixture");
        row.setPackVersion("2");
        row.setStatus(status);
        row.setDetectedAt(Instant.parse("2026-01-01T00:00:00Z"));
        conflicts.save(row);
    }
}
