package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
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
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real repository queries: pages beyond the old first-100 cut-off remain reachable and tenant-scoped. */
@DataJpaTest(showSql = false)
class SoarCatalogPagingPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @Autowired SoarPlaybookRepository playbooks;
    @Autowired SoarRunRepository runs;
    @Autowired SoarAutomationRuleRepository rules;
    @Autowired SoarConnectorRepository connections;
    @Autowired SoarManualTaskRepository tasks;
    @Autowired SoarApprovalRepository approvals;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setup() { TenantContext.set("catalog-a"); }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void traversesAllCatalogsWithoutTiedSortRowsDuplicatingOrLeakingTenants() {
        // Insert in reverse order and tie timestamps/priorities to require the explicit id tie-breakers.
        for (int i = 136; i >= 0; i--) fixture("catalog-a", String.format("a-%03d", i), NOW);
        fixture("catalog-b", "b-private", NOW);
        jdbc.update("update t_soar_connector set deleted_at = ? where id = 'connection-a-136'", NOW);

        List<String> expected = IntStream.range(0, 137).mapToObj(i -> String.format("a-%03d", i)).toList();
        assertThat(ids(page -> playbooks.searchByTenant("catalog-a", null, null, null, page),
                row -> row.getId().substring(3))).containsExactlyElementsOf(expected);
        assertThat(ids(page -> rules.findByTenantIdOrderByPriorityAscUpdatedAtDesc("catalog-a", page),
                row -> row.getId().substring(5))).containsExactlyElementsOf(expected);
        assertThat(ids(page -> connections.findByTenantIdAndDeletedAtIsNullOrderByNameAsc("catalog-a", page),
                row -> row.getId().substring(11))).containsExactlyElementsOf(expected.subList(0, 136));
        assertThat(ids(page -> tasks.findByTenantIdAndStatusOrderByDueAtAsc("catalog-a", "PENDING", page),
                row -> row.getId().substring(5))).containsExactlyElementsOf(expected);
        assertThat(ids(page -> tasks.findByTenantIdOrderByCreatedAtDesc("catalog-a", page),
                row -> row.getId().substring(5))).containsExactlyElementsOf(expected);
        assertThat(ids(page -> runs.searchByTenant("catalog-a", null, null, null, null, null, null, page),
                row -> row.getId().substring(4))).containsExactlyElementsOf(expected.reversed());
    }

    @Test void findsOldLatestRetainedRunAcrossVersionsWithoutScanningRecentTenantRuns() {
        fixture("catalog-a", "old", NOW.minusSeconds(86400));
        jdbc.update("insert into t_soar_playbook_version (id,tenant_id,playbook_id,version_no,status,schema_version,"
                + "definition_json,definition_hash,created_by,created_at,updated_at,row_version) "
                + "values ('version-old-new','catalog-a','pb-old',2,'PUBLISHED','2.0','{}','new-hash','test',?,?,0)",
                NOW, NOW);
        jdbc.update("update t_soar_playbook set latest_published_version = 2 where id = 'pb-old'");
        for (int i = 0; i < 120; i++) fixture("catalog-a", "recent-" + i, NOW);
        fixture("catalog-a", "never", NOW);
        jdbc.update("delete from t_soar_manual_task where run_id = 'run-never'");
        jdbc.update("delete from t_soar_run where id = 'run-never'");
        fixture("catalog-b", "private", NOW.plusSeconds(100));
        // Same timestamp: the id breaks the tie deterministically; a higher revision isn't required.
        insertRun("catalog-a", "z-old-tie", "old", NOW.minusSeconds(86400));
        insertRun("catalog-a", "older", "old", NOW.minusSeconds(172800));
        List<SoarRunEntity> latest = runs.findLatestByTenantIdAndPlaybookIds("catalog-a",
                List.of("pb-old", "pb-never", "pb-private"));
        assertThat(latest).extracting(SoarRunEntity::getId).containsExactly("run-z-old-tie");
        assertThat(latest.getFirst().getCreatedAt()).isEqualTo(NOW.minusSeconds(86400));
        assertThat(latest.getFirst().getPlaybookVersionNo()).isEqualTo(1);
        assertThat(runs.searchByTenant("catalog-a", null, null, null, null, null, null,
                PageRequest.of(0, 20))).noneMatch(row -> row.getPlaybookId().equals("pb-old"));
    }

    @Test void pagesPendingApprovalsPastTwoHundredWithTiedTimestampsAndTenantIsolation() {
        fixture("catalog-a", "approval-run", NOW);
        fixture("catalog-b", "private-run", NOW);
        for (int i = 0; i < 221; i++) approval("catalog-a", String.format("pending-%03d", i), "approval-run", "PENDING", NOW);
        for (int i = 0; i < 210; i++) approval("catalog-a", "newer-approved-" + i, "approval-run", "APPROVED", NOW.plusSeconds(3600));
        approval("catalog-b", "private-pending", "private-run", "PENDING", NOW);
        List<String> expected = IntStream.range(0, 221).mapToObj(i -> String.format("pending-%03d", i)).toList().reversed();
        assertThat(ids(page -> approvals.findByTenantIdAndStatusOrderByCreatedAtDesc("catalog-a", "PENDING", page),
                row -> row.getId())).containsExactlyElementsOf(expected);
        assertThat(approvals.findByTenantIdAndId("catalog-a", "pending-000")).isPresent();
        assertThat(approvals.findByTenantIdAndId("catalog-b", "pending-000")).isEmpty();
    }

    private void approval(String tenant, String id, String run, String status, Instant time) {
        jdbc.update("insert into t_soar_approval (id,tenant_id,run_id,approval_key,required_approvals,status,requested_by,created_at,expires_at) "
                + "values (?,?,?,?,1,?,'test',?,?)", id, tenant, "run-" + run, id, status, time, time.plusSeconds(7200));
    }

    private <T> List<String> ids(Function<PageRequest, Page<T>> query, Function<T, String> id) {
        List<String> result = new ArrayList<>();
        int page = 0;
        long total;
        do {
            Page<T> rows = query.apply(PageRequest.of(page++, 25));
            total = rows.getTotalElements();
            assertThat(rows.getSize()).isEqualTo(25);
            result.addAll(rows.map(id).getContent());
            if (!rows.hasNext()) break;
        } while (page < 10);
        assertThat(result).hasSize((int) total).doesNotHaveDuplicates();
        assertThat(page).isGreaterThan(4);
        return result;
    }

    private void fixture(String tenant, String id, Instant time) {
        jdbc.update("insert into t_soar_playbook (id,tenant_id,name,status,row_version,created_at,updated_at) "
                + "values (?,?,?,'ACTIVE',0,?,?)", "pb-" + id, tenant, "fixture", time, time);
        jdbc.update("insert into t_soar_playbook_version (id,tenant_id,playbook_id,version_no,status,schema_version,"
                + "definition_json,definition_hash,created_by,created_at,updated_at,row_version) "
                + "values (?,?,?,1,'PUBLISHED','2.0','{}','hash','test',?,?,0)",
                "version-" + id, tenant, "pb-" + id, time, time);
        insertRun(tenant, id, id, time);
        jdbc.update("insert into t_soar_automation_rule (id,tenant_id,name,enabled,priority,trigger_type,condition_json,"
                + "actions_json,revision,created_by,created_at,updated_at,row_version) "
                + "values (?,?,?,true,1,'manual','{}','[]',1,'test',?,?,0)",
                "rule-" + id, tenant, "fixture", time, time);
        jdbc.update("insert into t_soar_connector (id,tenant_id,name,connector_type,endpoint,allowed_hosts_json,"
                + "enabled,revision,created_by,created_at,updated_at,row_version) "
                + "values (?,?,?,'webhook','https://example.com','[]',true,1,'test',?,?,0)",
                "connection-" + id, tenant, id, time, time);
        jdbc.update("insert into t_soar_manual_task (id,tenant_id,run_id,node_id,form_schema_json,status,due_at,"
                + "created_at,updated_at,row_version) values (?,?,?,'manual','{}','PENDING',?,?,?,0)",
                "task-" + id, tenant, "run-" + id, time, time, time);
    }

    private void insertRun(String tenant, String id, String playbook, Instant time) {
        jdbc.update("insert into t_soar_run (id,tenant_id,request_id,execution_series_id,playbook_id,playbook_version_id,"
                + "playbook_version_no,definition_hash,trigger_type,status,requested_by,created_at,updated_at,row_version) "
                + "values (?,?,?,?,?,?,1,'hash','MANUAL','SUCCEEDED','test',?,?,0)",
                "run-" + id, tenant, "request-" + id, "series-" + id, "pb-" + playbook, "version-" + playbook, time, time);
    }
}
