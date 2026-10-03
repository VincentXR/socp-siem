package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the actual catalogue service and repository SQL, with no mocked persistence. */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.socp.soar.web.service.SoarCatalogQueryPersistenceTest$SqlCapture"
})
class SoarCatalogQueryPersistenceTest {
    private static final String TENANT = "catalog-query-a";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @Autowired SoarPlaybookRepository playbooks;
    @Autowired PlaybookVersionRepository versions;
    @Autowired SoarRunRepository runs;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    private SoarQueryService queries;
    private Statistics statistics;

    @BeforeEach void setup() {
        TenantContext.set(TENANT);
        // Only the catalogue dependencies are used; all three repositories and the mapper are real.
        queries = new SoarQueryService(playbooks, versions, runs, null, null, null, null,
                null, null, null, new SoarReadModelMapper(new ObjectMapper()),
                new SoarRecords(playbooks, versions, runs));
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    @AfterEach void cleanup() { TenantContext.clear(); SqlCapture.clear(); }

    @Test void filtersLiteralTagsBeforePagingAndKeepsTotalsOnAnEmptyLatePage() {
        for (int i = 136; i >= 0; i--) {
            playbook(TENANT, String.format("match-%03d", i), "[\"Ops%_\\\"\\\\路径\",\"BLUE\"]", "ACTIVE", "Alice");
        }
        playbook(TENANT, "substring", "[\"prefixOps%_\\\"\\\\路径suffix\",\"BLUEISH\"]", "ACTIVE", "Alice");
        playbook(TENANT, "wildcards", "[\"OpsXXZZ路径\",\"blue%\"]", "ACTIVE", "Alice");
        playbook(TENANT, "other-owner", "[\"BLUE\"]", "ACTIVE", "Bob");
        playbook(TENANT, "other-status", "[\"BLUE\"]", "ARCHIVED", "Alice");
        playbook("catalog-query-b", "other-tenant", "[\"BLUE\"]", "ACTIVE", "Alice");
        resetEvidence();

        List<String> actual = new ArrayList<>();
        for (int page = 0; page < 6; page++) {
            Page<Map<String, Object>> result = queries.listPlaybooks(PageRequest.of(page, 25),
                    " active ", " ALICE ", "bLuE", null);
            assertThat(result.getTotalElements()).isEqualTo(137);
            assertThat(result.getTotalPages()).isEqualTo(6);
            result.forEach(row -> actual.add((String) row.get("id")));
        }
        assertThat(actual).containsExactlyElementsOf(IntStream.range(0, 137)
                .mapToObj(i -> String.format("match-%03d", i)).toList());
        assertThat(queries.listPlaybooks(PageRequest.of(0, 200), null, null,
                "oPs%_\"\\路径", null).getTotalElements()).isEqualTo(137);
        Page<Map<String, Object>> empty = queries.listPlaybooks(PageRequest.of(20, 25),
                "ACTIVE", "alice", "blue", null);
        assertThat(empty.getContent()).isEmpty();
        assertThat(empty.getTotalElements()).isEqualTo(137);
        assertThat(empty.getTotalPages()).isEqualTo(6);
    }

    @Test void riskUsesLatestPublishedRevisionAndTenantScopedExactMetadata() {
        playbook(TENANT, "high", "[\"triage\"]", "ACTIVE", "Alice");
        version(TENANT, "high", 1, "PUBLISHED", 0, 2);
        version(TENANT, "high", 2, "PUBLISHED", 1, 2);
        version(TENANT, "high", 3, "DRAFT", 0, 0);
        playbook(TENANT, "medium", "[\"triage\"]", "ACTIVE", "Alice");
        version(TENANT, "medium", 1, "PUBLISHED", 4, 4);
        version(TENANT, "medium", 2, "PUBLISHED", 0, 3);
        version(TENANT, "medium", 3, "ARCHIVED", 6, 6);
        playbook(TENANT, "empty", "[\"triage\"]", "ACTIVE", "Alice");
        version(TENANT, "empty", 1, "PUBLISHED", 0, 0);
        playbook(TENANT, "unpublished", "[\"triage\"]", "ACTIVE", "Alice");
        version(TENANT, "unpublished", 5, "DRAFT", 5, 5);
        playbook(TENANT, "untagged-high", "[]", "ACTIVE", "Alice");
        version(TENANT, "untagged-high", 1, "PUBLISHED", 1, 1);
        playbook("catalog-query-b", "private-high", "[\"triage\"]", "ACTIVE", "Alice");
        version("catalog-query-b", "private-high", 99, "PUBLISHED", 7, 7);
        // Deliberately stale denormalized pointers must not override the latest published row.
        jdbc.update("update t_soar_playbook set latest_published_version=1 where id in ('high','medium')");
        resetEvidence();

        assertRisk(" high ", "high");
        assertRisk("CRITICAL", "high");
        assertRisk("MEDIUM", "medium");
        assertRisk("LOW", "empty", "medium");
        assertRisk("READ_ONLY", "empty", "medium");
        assertRisk("NONE", "empty", "unpublished");
        assertRisk("UNKNOWN");
        assertThat(queries.listPlaybooks(PageRequest.of(0, 1), null, null, null, "HIGH")
                .getTotalElements()).isEqualTo(2);
        assertThat(statistics.getEntityStatistics(PlaybookVersionEntity.class.getName()).getLoadCount()).isZero();
    }

    @Test void hydratesOnlyPageMetadataWithConstantQueriesDespiteLargeRetainedHistory() {
        for (int i = 0; i < 31; i++) {
            String id = String.format("batch-%02d", i);
            playbook(TENANT, id, "[]", "ACTIVE", "Alice");
            version(TENANT, id, 1, "PUBLISHED", 0, 1);
            for (int revision = 2; revision <= 18; revision++) version(TENANT, id, revision, "ARCHIVED", 0, 1);
            version(TENANT, id, 19, "DRAFT", 0, 2);
            version(TENANT, id, 20, "DRAFT", 1, 3);
            for (int history = 0; history < 12; history++) {
                run(TENANT, id + "-history-" + history, id, NOW.minusSeconds(1000L + history), null, null);
            }
            run(TENANT, id + "-latest-a", id, NOW, null, null);
            run(TENANT, id + "-latest-z", id, NOW, null, null);
        }
        playbook("catalog-query-b", "private-batch", "[]", "ACTIVE", "Alice");
        version("catalog-query-b", "private-batch", 999, "DRAFT", 1, 1);
        resetEvidence();

        Page<Map<String, Object>> result = queries.listPlaybooks(PageRequest.of(0, 25));
        assertThat(result.getTotalElements()).isEqualTo(31);
        assertThat(result.getContent()).hasSize(25).allSatisfy(row -> {
            assertThat(row.get("draftVersion")).isEqualTo(20);
            assertThat(((Map<?, ?>) row.get("latestRun")).get("runId")).isEqualTo(row.get("id") + "-latest-z");
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4); // page + count + two batch projections
        assertThat(statistics.getEntityLoadCount()).isEqualTo(25);
        assertThat(statistics.getEntityStatistics(PlaybookVersionEntity.class.getName()).getLoadCount()).isZero();
        assertThat(statistics.getEntityStatistics(SoarRunEntity.class.getName()).getLoadCount()).isZero();
        assertThat(SqlCapture.statements()).hasSize(4).noneMatch(sql -> sql.contains("definition_json")
                || sql.contains("input_json") || sql.contains("output_json") || sql.contains("risk_summary_json"));
        assertThat(SqlCapture.statements().stream().filter(sql -> sql.contains("t_soar_playbook_version")).toList())
                .singleElement().satisfies(sql -> assertThat(sql).contains("max(", " in (", "group by", "tenant_id"));
        assertThat(SqlCapture.statements().stream().filter(sql -> sql.contains("t_soar_run")).toList())
                .singleElement().satisfies(sql -> assertThat(sql).contains("not exists", " in (", "tenant_id"));

        resetEvidence();
        assertThat(queries.listPlaybooks(PageRequest.of(0, 5)).getContent()).hasSize(5);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(5);
    }

    @Test void persistsDerivedMetadataAtomicallyWhenJsonChanges() {
        playbook(TENANT, "updated", "[\"old\"]", "ACTIVE", "Alice");
        version(TENANT, "updated", 1, "PUBLISHED", 1, 2);
        resetEvidence();
        SoarPlaybookEntity playbook = playbooks.findByTenantIdAndId(TENANT, "updated").orElseThrow();
        PlaybookVersionEntity version = versions.findByTenantIdAndPlaybookIdAndVersionNo(TENANT, "updated", 1).orElseThrow();
        playbook.setTagsJson("[\"new%_\",\"NEW%_\"]");
        version.setRiskSummaryJson("{\"highRiskActionCount\":0,\"actionCount\":3}");
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.listPlaybooks(PageRequest.of(0, 10), null, null, "old", null)).isEmpty();
        assertThat(queries.listPlaybooks(PageRequest.of(0, 10), null, null, "NEW%_", "MEDIUM")
                .getContent()).extracting(row -> row.get("id")).containsExactly("updated");
        assertThat(queries.listPlaybooks(PageRequest.of(0, 10), null, null, null, "HIGH")).isEmpty();
        assertThat(jdbc.queryForObject("select tag_tokens from t_soar_playbook where id='updated'", String.class))
                .isEqualTo("|bmV3JV8|");
    }

    @Test void batchProjectionsKeepOldRunsAndRejectRequestedForeignTenantIds() {
        playbook(TENANT, "old", "[]", "ACTIVE", "Alice");
        version(TENANT, "old", 1, "PUBLISHED", 0, 1);
        version(TENANT, "old", 2, "DRAFT", 0, 1);
        version(TENANT, "old", 3, "PUBLISHED", 0, 2);
        version(TENANT, "old", 4, "DRAFT", 0, 2);
        version(TENANT, "old", 99, "ARCHIVED", 1, 3);
        Instant retained = NOW.minusSeconds(172800);
        run(TENANT, "old-tie-a", "old", retained, null, null);
        run(TENANT, "old-tie-z", "old", retained, null, null);
        playbook(TENANT, "no-history", "[]", "ACTIVE", "Alice");
        version(TENANT, "no-history", 1, "PUBLISHED", 0, 0);
        playbook(TENANT, "recent", "[]", "ACTIVE", "Alice");
        version(TENANT, "recent", 1, "PUBLISHED", 0, 1);
        for (int i = 0; i < 180; i++) run(TENANT, "recent-" + i, "recent", NOW, null, null);
        playbook("catalog-query-b", "private", "[]", "ACTIVE", "Alice");
        version("catalog-query-b", "private", 1, "PUBLISHED", 0, 1);
        version("catalog-query-b", "private", 100, "DRAFT", 1, 1);
        run("catalog-query-b", "private-run", "private", NOW.plusSeconds(1), null, null);
        resetEvidence();

        List<String> requested = List.of("old", "no-history", "private", "missing");
        assertThat(versions.findDraftMetadata(TENANT, requested)).singleElement().satisfies(draft -> {
            assertThat(draft.getPlaybookId()).isEqualTo("old");
            assertThat(draft.getVersionNo()).isEqualTo(4);
        });
        assertThat(runs.findLatestMetadataByTenantIdAndPlaybookIds(TENANT, requested))
                .singleElement().satisfies(latest -> {
                    assertThat(latest.getId()).isEqualTo("old-tie-z");
                    assertThat(latest.getPlaybookId()).isEqualTo("old");
                    assertThat(latest.getStatus()).isEqualTo("SUCCEEDED");
                    assertThat(latest.getCreatedAt()).isEqualTo(retained);
                });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(SqlCapture.statements()).noneMatch(sql -> sql.contains("definition_json") || sql.contains("input_json"));
    }

    @Test void investigationOriginsAndInputsSurviveReloadAndFiltersStayTenantBound() {
        playbook(TENANT, "investigation", "[]", "ACTIVE", "Alice");
        version(TENANT, "investigation", 1, "PUBLISHED", 0, 1);
        run(TENANT, "investigation-match", "investigation", NOW, "alarm-1", "case-1");
        run(TENANT, "different-case", "investigation", NOW.minusSeconds(1), "alarm-1", "case-2");
        run(TENANT, "unrelated", "investigation", NOW.minusSeconds(2), "alarm-2", "case-1");
        playbook("catalog-query-b", "private-investigation", "[]", "ACTIVE", "Alice");
        version("catalog-query-b", "private-investigation", 1, "PUBLISHED", 0, 1);
        run("catalog-query-b", "private-run", "private-investigation", NOW, "alarm-1", "case-1");
        resetEvidence();

        SoarRunEntity loaded = runs.findByTenantIdAndId(TENANT, "investigation-match").orElseThrow();
        assertThat(loaded.getOriginAlarmId()).isEqualTo("alarm-1");
        assertThat(loaded.getOriginCaseId()).isEqualTo("case-1");
        assertThat(loaded.getInputJson()).isEqualTo("{\"target\":\"host-a\",\"enabled\":false,\"count\":0}");
        assertThat(runs.searchInvestigation(TENANT, "alarm-1", "case-1", "SUCCEEDED", "investigation",
                PageRequest.of(0, 1))).extracting(SoarRunEntity::getId).containsExactly("investigation-match");
        assertThat(runs.searchInvestigation(TENANT, "alarm-1", null, null, null,
                PageRequest.of(0, 1)).getTotalElements()).isEqualTo(2);
        assertThat(runs.searchInvestigation(TENANT, null, null, null, "%_", PageRequest.of(0, 20))).isEmpty();
        assertThat(runs.searchInvestigation(TENANT, "alarm-1", "case-1", "FAILED", null,
                PageRequest.of(0, 20))).isEmpty();
    }

    private void assertRisk(String risk, String... ids) {
        var page = queries.listPlaybooks(PageRequest.of(0, 1), null, null, "triage", risk);
        assertThat(page.getTotalElements()).as("risk %s", risk).isEqualTo(ids.length);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            queries.listPlaybooks(PageRequest.of(i, 1), null, null, "triage", risk)
                    .forEach(row -> result.add((String) row.get("id")));
        }
        assertThat(result).containsExactly(ids);
    }

    private void resetEvidence() {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        SqlCapture.clear();
    }

    private void playbook(String tenant, String id, String tags, String status, String owner) {
        SoarPlaybookEntity row = new SoarPlaybookEntity();
        row.setId(id); row.setTenantId(tenant); row.setName("Playbook " + id);
        row.setTagsJson(tags); row.setStatus(status); row.setOwner(owner);
        row.setCreatedAt(NOW); row.setUpdatedAt(NOW);
        TenantContext.runWith(tenant, () -> playbooks.saveAndFlush(row));
    }

    private void version(String tenant, String playbook, int revision, String status, int high, int count) {
        PlaybookVersionEntity row = new PlaybookVersionEntity();
        row.setId(playbook + "-v" + revision); row.setTenantId(tenant); row.setPlaybookId(playbook);
        row.setVersionNo(revision); row.setStatus(status); row.setSchemaVersion("2.0");
        row.setDefinitionJson("{\"inputSchema\":{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}}");
        row.setDefinitionHash("hash");
        row.setRiskSummaryJson("{\"highRiskActionCount\":" + high + ",\"actionCount\":" + count + "}");
        row.setCreatedBy("test"); row.setCreatedAt(NOW); row.setUpdatedAt(NOW);
        TenantContext.runWith(tenant, () -> versions.saveAndFlush(row));
    }

    private void run(String tenant, String id, String playbook, Instant created, String alarm, String caseId) {
        SoarRunEntity row = new SoarRunEntity();
        row.setId(id); row.setTenantId(tenant); row.setRequestId("request-" + id);
        row.setExecutionSeriesId("series-" + id); row.setPlaybookId(playbook);
        row.setPlaybookVersionId(playbook + "-v1"); row.setPlaybookVersionNo(1);
        row.setDefinitionHash("hash"); row.setTriggerType("MANUAL"); row.setStatus("SUCCEEDED");
        row.setRequestedBy("test"); row.setCreatedAt(created); row.setUpdatedAt(created);
        row.setOriginAlarmId(alarm); row.setOriginCaseId(caseId);
        row.setInputJson("{\"target\":\"host-a\",\"enabled\":false,\"count\":0}");
        TenantContext.runWith(tenant, () -> runs.saveAndFlush(row));
    }

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> SQL = ThreadLocal.withInitial(ArrayList::new);
        @Override public String inspect(String sql) { SQL.get().add(sql.toLowerCase(java.util.Locale.ROOT)); return sql; }
        static List<String> statements() { return List.copyOf(SQL.get()); }
        static void clear() { SQL.get().clear(); }
    }
}
