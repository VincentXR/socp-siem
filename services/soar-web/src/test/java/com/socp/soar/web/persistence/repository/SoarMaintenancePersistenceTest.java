package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.service.SoarCancellationWorker;
import com.socp.soar.web.service.SoarRunRecoveryState;
import com.socp.soar.web.service.SoarRunRecoveryWorker;
import com.socp.soar.web.service.TemporalExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@Import(SoarRunRecoveryState.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(30)
class SoarMaintenancePersistenceTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant CUTOFF = NOW.plusSeconds(1);
    static final Set<String> ACTIVE = Set.of("RUNNING", "DISPATCHING", "CANCELLING", "WAITING_APPROVAL", "WAITING_INPUT");
    @Autowired SoarRunRepository runs;
    @Autowired SoarRunRecoveryState state;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean SoarActionAttemptRepository attempts;

    @BeforeEach void setup() {
        clear();
        TenantContext.set("tenant-a");
        for (String tenant : List.of("tenant-a", "tenant-b")) {
            jdbc.update("insert into t_soar_playbook (id,tenant_id,name,status,row_version,created_at,updated_at) "
                    + "values (?,?,?,'PUBLISHED',0,current_timestamp,current_timestamp)", "pb-" + tenant, tenant, "fixture");
            jdbc.update("insert into t_soar_playbook_version (id,tenant_id,playbook_id,version_no,status,schema_version,"
                    + "definition_json,definition_hash,created_by,created_at,updated_at,row_version) "
                    + "values (?,?,?,1,'PUBLISHED','2.0','{}','hash','test',current_timestamp,current_timestamp,0)",
                    "version-" + tenant, tenant, "pb-" + tenant);
        }
    }

    @AfterEach void cleanup() { TenantContext.clear(); clear(); }

    void clear() {
        jdbc.update("delete from t_soar_action_attempt");
        jdbc.update("delete from t_soar_node_run");
        jdbc.update("delete from t_soar_dispatch_outbox");
        jdbc.update("delete from t_soar_run");
        jdbc.update("delete from t_soar_playbook_version");
        jdbc.update("delete from t_soar_playbook");
    }

    @ParameterizedTest @ValueSource(strings = {"OPEN", "UNKNOWN"})
    void cancellationDoesNotBecomeTerminalWithoutAuthoritativeObservation(String observation) {
        var snapshot = run("one", "tenant-a", "CANCELLING");
        assertFalse(state.recover(snapshot, TemporalExecutor.WorkflowState.valueOf(observation), CUTOFF, NOW.plusSeconds(10)));
        assertEquals("CANCELLING", row("one").getStatus());
        assertNull(row("one").getCompletedAt());
        assertEquals(0L, row("one").getRowVersion());
    }

    @ParameterizedTest @ValueSource(strings = {"CLOSED", "NOT_FOUND"})
    void cancellationSettlesOnlyAfterAClosedOrAuthoritativelyAbsentWorkflow(String observation) {
        var snapshot = run("one", "tenant-a", "CANCELLING");
        assertTrue(state.recover(snapshot, TemporalExecutor.WorkflowState.valueOf(observation), CUTOFF, NOW.plusSeconds(10)));
        assertEquals("CANCELLED", row("one").getStatus());
        assertEquals("SOAR_RUN_CANCELLED", row("one").getErrorCode());
        assertNotNull(row("one").getCompletedAt());
        assertFalse(state.recover(snapshot, TemporalExecutor.WorkflowState.CLOSED, CUTOFF, NOW.plusSeconds(11)));
    }

    @ParameterizedTest @ValueSource(strings = {"RUNNING", "DISPATCHING", "WAITING_APPROVAL", "WAITING_INPUT"})
    void closedWorkflowWithoutRunningActionIsFailedRatherThanClaimingSuccess(String status) {
        var snapshot = run("one", "tenant-a", status);
        assertTrue(state.recover(snapshot, TemporalExecutor.WorkflowState.CLOSED, CUTOFF, NOW.plusSeconds(10)));
        assertEquals("FAILED", row("one").getStatus());
        assertEquals("SOAR_PROJECTION_STALE", row("one").getErrorCode());
    }

    @Test void missingWorkflowWithoutRunningActionTimesOutTheProjection() {
        var snapshot = run("one", "tenant-a", "RUNNING");
        assertTrue(state.recover(snapshot, TemporalExecutor.WorkflowState.NOT_FOUND, CUTOFF, NOW.plusSeconds(10)));
        assertEquals("TIMED_OUT", row("one").getStatus());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unresolvedActionReceiptsAreRetainedAndCancellationCannotResumeExecution(boolean cancelling) {
        var snapshot = run("one", "tenant-a", cancelling ? "CANCELLING" : "RUNNING");
        action("one");
        assertTrue(state.recover(snapshot, TemporalExecutor.WorkflowState.CLOSED, CUTOFF, NOW.plusSeconds(10)));
        assertEquals(cancelling ? "CANCELLED" : "ACTION_UNKNOWN", row("one").getStatus());
        assertTrue(row("one").getErrorMessage().contains(cancelling ? "unconfirmed" : "remains RUNNING"));
        assertEquals("RUNNING", jdbc.queryForObject("select status from t_soar_action_attempt where id='attempt-one'", String.class));
    }

    @Test void aDurableStartStillAwaitingDispatchCannotBeTimedOutByAnEarlierDescribe() {
        var snapshot = run("one", "tenant-a", "DISPATCHING");
        jdbc.update("insert into t_soar_dispatch_outbox (id,tenant_id,run_id,status,attempts,next_attempt_at,created_at,updated_at) "
                + "values ('dispatch','tenant-a','one','PENDING',0,current_timestamp,current_timestamp,current_timestamp)");
        assertFalse(state.recover(snapshot, TemporalExecutor.WorkflowState.NOT_FOUND, CUTOFF, NOW.plusSeconds(10)));
        assertEquals("DISPATCHING", row("one").getStatus());
        jdbc.update("update t_soar_dispatch_outbox set status='DISPATCHED' where id='dispatch'");
        assertTrue(state.recover(snapshot, TemporalExecutor.WorkflowState.CLOSED, CUTOFF, NOW.plusSeconds(11)));
    }

    @Test void aProbeDoesNotHoldDatabaseTransactionAndCannotOverwriteNewerWorkflowProgress() {
        run("one", "tenant-a", "RUNNING");
        var temporal = temporal();
        when(temporal.describeWorkflow("workflow-one")).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var completed = row("one");
            completed.setStatus("SUCCEEDED");
            completed.setUpdatedAt(Instant.now());
            runs.saveAndFlush(completed);
            return TemporalExecutor.WorkflowState.CLOSED;
        });
        TenantContext.runAsSystem(new SoarRunRecoveryWorker(runs, temporal, state, 600)::tick);
        assertEquals("SUCCEEDED", row("one").getStatus());
        assertNull(row("one").getErrorCode());
    }

    @Test void oneFailedAttemptQueryRollsBackOnlyThatRunAndTheBatchContinues() {
        run("bad", "tenant-a", "RUNNING");
        run("good", "tenant-a", "RUNNING");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(attempts).existsRunningByTenantIdAndRunId("tenant-a", "bad");
        var temporal = temporal();
        when(temporal.describeWorkflow(anyString())).thenReturn(TemporalExecutor.WorkflowState.CLOSED);
        TenantContext.runAsSystem(new SoarRunRecoveryWorker(runs, temporal, state, 600)::tick);
        assertEquals("RUNNING", row("bad").getStatus());
        assertEquals(NOW, row("bad").getUpdatedAt());
        assertEquals("FAILED", row("good").getStatus());
    }

    @Test void cancellationAcceptanceCannotOverwriteAnActivityCompletion() {
        run("one", "tenant-a", "CANCELLING");
        var temporal = temporal();
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var completed = row("one");
            completed.setStatus("CANCELLED"); completed.setUpdatedAt(Instant.now());
            runs.saveAndFlush(completed);
            return null;
        }).when(temporal).cancelWorkflow("workflow-one");
        TenantContext.runAsSystem(new SoarCancellationWorker(runs, temporal)::tick);
        assertEquals("CANCELLED", row("one").getStatus());
        assertEquals(1L, row("one").getRowVersion());
    }

    @Test void maintenanceSchedulingDoesNotAlterBusinessVersionOrEraseProgressAndSurvivesEntitySave() {
        var snapshot = run("one", "tenant-a", "CANCELLING");
        assertEquals(1, runs.claimCancellation("tenant-a", "one", 0, "workflow-one", NOW, NOW.plusSeconds(30)));
        assertEquals(1, runs.claimRecoveryCheck("tenant-a", "one", 0, CUTOFF, NOW, NOW.plusSeconds(60)));
        assertEquals(0L, row("one").getRowVersion());
        assertEquals(NOW, row("one").getUpdatedAt());
        snapshot.setErrorMessage("operator reason");
        runs.saveAndFlush(snapshot);
        assertEquals(NOW.plusSeconds(30), row("one").getCancelNextAttemptAt());
        assertEquals(NOW.plusSeconds(60), row("one").getRecoveryNextCheckAt());
        assertEquals(1L, row("one").getRowVersion());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void competingReplicasAcquireOneMaintenanceAttempt(boolean cancellation) throws Exception {
        run("one", "tenant-a", "CANCELLING");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                return TenantContext.callWith("tenant-a", () -> claim("one", "tenant-a", cancellation));
            })).toList();
            start.countDown();
            assertEquals(1, tasks.get(0).get(5, TimeUnit.SECONDS) + tasks.get(1).get(5, TimeUnit.SECONDS));
        }
        assertEquals(0L, row("one").getRowVersion());
        assertEquals(0, TenantContext.callWith("tenant-b", () -> claim("one", "tenant-b", cancellation)));
        var changed = row("one"); changed.setStatus("CANCELLED"); runs.saveAndFlush(changed);
        assertEquals(0, runs.claimCancellation("tenant-a", "one", 0, "workflow-one", NOW.plusSeconds(61), NOW.plusSeconds(90)));
        assertEquals(0, runs.claimRecoveryCheck("tenant-a", "one", 0, CUTOFF, NOW.plusSeconds(61), NOW.plusSeconds(90)));
    }

    @Test void pollingIsBoundedAndRepeatedUnresolvedRowsCannotStarveUnseenRuns() {
        for (int i = 0; i < 101; i++) run("run-" + i, "tenant-a", "CANCELLING");
        var first = runs.findCancellationCandidates(NOW, PageRequest.of(0, 100));
        assertEquals(100, first.size());
        first.forEach(candidate -> assertEquals(1, runs.claimCancellation("tenant-a", candidate.getId(),
                candidate.getRowVersion(), candidate.getTemporalWorkflowId(), NOW, NOW.plusSeconds(30))));
        assertEquals(1, runs.findCancellationCandidates(NOW, PageRequest.of(0, 100)).size());
        var recovery = runs.findRecoveryCandidates(ACTIVE, CUTOFF, NOW, PageRequest.of(0, 100));
        assertEquals(100, recovery.size());
        recovery.forEach(candidate -> assertEquals(1, runs.claimRecoveryCheck("tenant-a", candidate.getId(),
                candidate.getRowVersion(), CUTOFF, NOW, NOW.plusSeconds(60))));
        assertEquals(1, runs.findRecoveryCandidates(ACTIVE, CUTOFF, NOW, PageRequest.of(0, 100)).size());
    }

    @Test void approvalWaitingBeforeWorkflowAdmissionIsNotAnOrphan() {
        var waiting = run("one", "tenant-a", "WAITING_APPROVAL");
        waiting.setTemporalWorkflowId(null); runs.saveAndFlush(waiting);
        assertTrue(runs.findRecoveryCandidates(ACTIVE, CUTOFF, NOW, PageRequest.of(0, 100)).isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lockedRunDoesNotBlockMaintenanceForAnotherTenant(boolean cancellation) throws Exception {
        run("one", "tenant-a", "CANCELLING");
        TenantContext.runWith("tenant-b", () -> run("two", "tenant-b", "CANCELLING"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("select id from t_soar_run where id='one' for update", String.class);
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var progress = executor.submit(() -> {
                    try (var ignored = TenantContext.openSystem()) {
                        assertEquals(0, claim("one", "tenant-a", cancellation));
                        return claim("two", "tenant-b", cancellation);
                    }
                });
                assertEquals(1, progress.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    int claim(String id, String tenant, boolean cancellation) {
        return cancellation ? runs.claimCancellation(tenant, id, 0, "workflow-" + id, NOW, NOW.plusSeconds(30))
                : runs.claimRecoveryCheck(tenant, id, 0, CUTOFF, NOW, NOW.plusSeconds(60));
    }

    SoarRunEntity run(String id, String tenant, String status) {
        jdbc.update("insert into t_soar_run (id,tenant_id,request_id,execution_series_id,playbook_id,playbook_version_id,"
                + "playbook_version_no,definition_hash,trigger_type,status,requested_by,created_at,updated_at,temporal_workflow_id) "
                + "values (?,?,?,?,?,?,1,'hash','TEST',?,'test',?,?,?)",
                id, tenant, "request-" + id, "series-" + id, "pb-" + tenant, "version-" + tenant, status,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), "workflow-" + id);
        return runs.findByTenantIdAndId(tenant, id).orElseThrow();
    }

    void action(String run) {
        jdbc.update("insert into t_soar_node_run (id,tenant_id,run_id,node_id,iteration_path,node_type,status,updated_at) "
                + "values (?,'tenant-a',?,'action','','ACTION','RUNNING',current_timestamp)", "node-" + run, run);
        jdbc.update("insert into t_soar_action_attempt (id,tenant_id,node_run_id,attempt_no,status,created_at) "
                + "values (?,'tenant-a',?,1,'RUNNING',current_timestamp)", "attempt-" + run, "node-" + run);
    }

    SoarRunEntity row(String id) { return runs.findByTenantIdAndId("tenant-a", id).orElseThrow(); }
    TemporalExecutor temporal() {
        var temporal = mock(TemporalExecutor.class);
        when(temporal.isAvailable()).thenReturn(true);
        return temporal;
    }
}
