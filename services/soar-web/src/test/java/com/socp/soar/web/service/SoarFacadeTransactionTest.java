package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.connector.SecretResolver;
import com.socp.soar.web.temporal.SoarActivityImpl;
import com.socp.soar.web.temporal.SoarRunUpdate;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Real Spring proxy + H2 transaction rollback, with fault injection only at the failing boundary. */
@DataJpaTest(showSql = false)
@Import({SoarService.class, SoarActivityImpl.class, SoarFacadeTransactionTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SoarFacadeTransactionTest {
    @Autowired SoarService service;
    @Autowired SoarActivityImpl activity;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SoarRunRepository runs;
    @Autowired SoarManualTaskRepository tasks;
    @Autowired SoarNodeRunRepository nodes;
    @Autowired PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean SoarApprovalRepository approvals;
    @MockitoBean TemporalExecutor temporal;
    @MockitoBean SoarConnectorRegistry connectors;
    @MockitoBean LegacyPlaybookActions legacyActions;
    @MockitoBean SecretResolver secrets;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean SoarRunEventRepository events;
    private String tenant;
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean SoarDefinitionValidator validator(ObjectMapper mapper) { return new SoarDefinitionValidator(mapper); }
    }
    @BeforeEach void tenant() {
        tenant = "tx-" + UUID.randomUUID(); TenantContext.set(tenant);
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void failedImportRollsBackCreatedMetadataAndDraft() throws Exception {
        var definition = mapper.readTree("{\"schemaVersion\":\"soar.playbook\",\"entryNodeId\":\"start\","
                + "\"password\":\"inline-secret\",\"nodes\":[{\"id\":\"start\",\"type\":\"START\"},{\"id\":\"end\",\"type\":\"END\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}");
        assertThatThrownBy(() -> service.importDraft("bad import", "", List.of(), definition, null))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("SOAR_SECRET_INLINE_FORBIDDEN");
        assertThat(count("t_soar_playbook")).isZero();
        assertThat(count("t_soar_playbook_version")).isZero();
    }

    @Test void eventFailureRollsBackRunAndDispatchTogether() {
        var created = service.createPlaybook("transaction fixture", "", List.of());
        String id = String.valueOf(created.get("id"));
        service.publish(id, 1);
        String version = jdbc.queryForObject("select id from t_soar_playbook_version where tenant_id=? and playbook_id=?", String.class, tenant, id);
        doThrow(new IllegalStateException("event insert fault")).when(events).save(any(SoarRunEventEntity.class));
        assertThatThrownBy(() -> service.queueManualRun("request-one", version, Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("event insert fault");
        assertThat(count("t_soar_run")).isZero();
        assertThat(count("t_soar_dispatch_outbox")).isZero();
        assertThat(count("t_soar_playbook")).isEqualTo(1);
    }
    @Test void humanGatePersistsItsReferencedNodeBeforeApprovalAndReusesItOnRetry() {
        String playbook = String.valueOf(service.createPlaybook("approval fixture", "", List.of()).get("id"));
        service.publish(playbook, 1);
        String version = jdbc.queryForObject("select id from t_soar_playbook_version where tenant_id=? and playbook_id=?",
                String.class, tenant, playbook);
        String run = String.valueOf(service.queueManualRun("gate-request", version, Map.of(), Map.of()).get("runId"));

        activity.markRunWaitingWithContext(tenant, run, "gate", 60, 1, "", "", "{}");
        activity.markRunWaitingWithContext(tenant, run, "gate", 60, 1, "", "", "{}");

        assertThat(count("t_soar_approval")).isEqualTo(1);
        assertThat(count("t_soar_node_run")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select n.status from t_soar_approval a join t_soar_node_run n "
                + "on a.node_run_id=n.id and a.tenant_id=n.tenant_id where a.tenant_id=?", String.class, tenant))
                .isEqualTo("WAITING_APPROVAL");
    }

    @Test void manualTaskExpiryReferencesTheNodeInsteadOfTheTaskIdentity() {
        String playbook = String.valueOf(service.createPlaybook("manual expiry fixture", "", List.of()).get("id"));
        service.publish(playbook, 1);
        String version = jdbc.queryForObject("select id from t_soar_playbook_version where tenant_id=? and playbook_id=?",
                String.class, tenant, playbook);
        String run = String.valueOf(service.queueManualRun("manual-request", version, Map.of(), Map.of()).get("runId"));

        activity.markManualTaskWaiting(tenant, run, "task", "{}", "", "");
        activity.markManualTaskExpired(tenant, run, "task");

        assertThat(jdbc.queryForObject("select status from t_soar_manual_task where tenant_id=?", String.class, tenant))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from t_soar_run_event e join t_soar_node_run n "
                + "on e.tenant_id=n.tenant_id and e.node_run_id=n.id where e.tenant_id=? and e.event_type='MANUAL_TASK_EXPIRED'",
                Integer.class, tenant)).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"CANCELLED,RUN_CANCELLED", "TIMED_OUT,EXECUTION_TIMEOUT", "FAILED,EXECUTION_TIMEOUT"})
    void terminalRunClosesEveryPendingHumanWaitAndRejectsLateInput(String terminal, String code) {
        String run = pendingHumanWork("terminal");
        String task = jdbc.queryForObject("select id from t_soar_manual_task where tenant_id=? and run_id=?",
                String.class, tenant, run);

        activity.markRunCompleted(new SoarRunUpdate(tenant, run, terminal, "{}", code, "fixture"));
        activity.markRunCompleted(new SoarRunUpdate(tenant, run, terminal, "{}", code, "duplicate"));

        boolean expired = "EXECUTION_TIMEOUT".equals(code);
        String workStatus = expired ? "EXPIRED" : "CANCELLED";
        assertThat(jdbc.queryForObject("select status from t_soar_manual_task where id=?", String.class, task))
                .isEqualTo(workStatus);
        assertThat(jdbc.queryForObject("select row_version from t_soar_manual_task where id=?", Long.class, task))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status from t_soar_approval where tenant_id=? and run_id=?",
                String.class, tenant, run)).isEqualTo(workStatus);
        assertThat(jdbc.queryForList("select status from t_soar_node_run where tenant_id=? and run_id=?",
                String.class, tenant, run)).containsOnly(expired ? "TIMED_OUT" : "CANCELLED");
        assertThatThrownBy(() -> service.completeManualTask(task, Map.of("confirmed", true)))
                .hasMessageContaining("SOAR_MANUAL_TASK_ALREADY_COMPLETED");
        assertThat(jdbc.queryForObject("select status from t_soar_run where id=?", String.class, run))
                .isEqualTo(terminal);
    }

    @Test void queuedCancellationClosesHumanWorkInItsOwnTransaction() {
        String run = pendingHumanWork("queued-cancellation");

        service.cancelRun(run, "cancel fixture");

        assertThat(jdbc.queryForObject("select status from t_soar_run where id=?", String.class, run))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from t_soar_manual_task where tenant_id=? and run_id=?",
                String.class, tenant, run)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from t_soar_approval where tenant_id=? and run_id=?",
                String.class, tenant, run)).isEqualTo("CANCELLED");
    }

    @Test void oldTerminalWorkIsRepairedIdempotentlyWithoutChangingActiveWorkOrCompletedInput() {
        String oldRun = pendingHumanWork("historical");
        String activeRun = pendingHumanWork("active");
        // Historical terminal row produced by an older worker, before atomic child cleanup.
        jdbc.update("update t_soar_run set status='FAILED',error_code='EXECUTION_TIMEOUT' where id=?", oldRun);
        jdbc.update("update t_soar_manual_task set status='COMPLETED',input_json=? where tenant_id=? and run_id=?",
                "{\"confirmed\":true}", tenant, oldRun);
        var repair = new SoarTerminalWorkWorker(runs, tasks, approvals, nodes, transactions);

        TenantContext.runAsSystem(repair::tick);
        var decidedAt = jdbc.queryForObject("select decided_at from t_soar_approval where tenant_id=? and run_id=?",
                java.sql.Timestamp.class, tenant, oldRun);
        TenantContext.runAsSystem(repair::tick);

        assertThat(jdbc.queryForObject("select status from t_soar_approval where tenant_id=? and run_id=?",
                String.class, tenant, oldRun)).isEqualTo("EXPIRED");
        assertThat(decidedAt).isNotNull();
        assertThat(jdbc.queryForObject("select decided_at from t_soar_approval where tenant_id=? and run_id=?",
                java.sql.Timestamp.class, tenant, oldRun)).isEqualTo(decidedAt);
        assertThat(jdbc.queryForObject("select input_json from t_soar_manual_task where tenant_id=? and run_id=?",
                String.class, tenant, oldRun)).isEqualTo("{\"confirmed\":true}");
        assertThat(jdbc.queryForObject("select status from t_soar_manual_task where tenant_id=? and run_id=?",
                String.class, tenant, oldRun)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select status from t_soar_manual_task where tenant_id=? and run_id=?",
                String.class, tenant, activeRun)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from t_soar_approval where tenant_id=? and run_id=?",
                String.class, tenant, activeRun)).isEqualTo("PENDING");
    }

    @Test void terminalCleanupFailureRollsBackRunAndEarlierChildUpdatesTogether() {
        String run = pendingHumanWork("rollback");
        doThrow(new IllegalStateException("approval closure fault")).when(approvals)
                .closePendingForRun(org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.eq(run),
                        org.mockito.ArgumentMatchers.eq("CANCELLED"), any());

        assertThatThrownBy(() -> activity.markRunCompleted(
                new SoarRunUpdate(tenant, run, "CANCELLED", "{}", "RUN_CANCELLED", "fixture")))
                .hasMessageContaining("approval closure fault");

        assertThat(jdbc.queryForObject("select status from t_soar_run where id=?", String.class, run))
                .isEqualTo("WAITING_APPROVAL");
        assertThat(jdbc.queryForObject("select status from t_soar_manual_task where tenant_id=? and run_id=?",
                String.class, tenant, run)).isEqualTo("PENDING");
    }

    private String pendingHumanWork(String name) {
        String playbook = String.valueOf(service.createPlaybook(name, "", List.of()).get("id"));
        service.publish(playbook, 1);
        String version = jdbc.queryForObject("select id from t_soar_playbook_version where tenant_id=? and playbook_id=?",
                String.class, tenant, playbook);
        String run = String.valueOf(service.queueManualRun(name, version, Map.of(), Map.of()).get("runId"));
        activity.markManualTaskWaiting(tenant, run, "task", "{}", "", "");
        activity.markRunWaitingWithContext(tenant, run, "gate", 60, 1, "", "", "{}");
        return run;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant);
    }
}
