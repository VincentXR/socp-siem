package com.socp.soar.web.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.service.SoarDispatchState;
import com.socp.soar.web.service.SoarDispatchWorker;
import com.socp.soar.web.service.TemporalExecutor;
import io.temporal.api.common.v1.WorkflowExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@Import(SoarDispatchState.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(30)
class SoarDispatchClaimPersistenceTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @Autowired SoarDispatchOutboxRepository dispatches;
    @Autowired SoarRunRepository runs;
    @Autowired PlaybookVersionRepository versions;
    @Autowired SoarDispatchState state;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

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
        jdbc.update("delete from t_soar_dispatch_outbox");
        jdbc.update("delete from t_soar_run");
        jdbc.update("delete from t_soar_playbook_version");
        jdbc.update("delete from t_soar_playbook");
    }

    @Test void twoReplicasCanClaimOnlyOneVersionAndOneAttempt() throws Exception {
        var candidate = dispatch("one", "tenant-a");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                return TenantContext.callWith("tenant-a", () -> state.claim(candidate, "worker-" + index, NOW));
            })).toList();
            start.countDown();
            var first = tasks.get(0).get(5, TimeUnit.SECONDS);
            var second = tasks.get(1).get(5, TimeUnit.SECONDS);
            assertEquals(1, (first.isPresent() ? 1 : 0) + (second.isPresent() ? 1 : 0));
        }
        assertEquals("DISPATCHING", runStatus("one"));
        assertEquals("soar-tenant-a-run-one", runs.findByTenantIdAndId("tenant-a", "run-one").orElseThrow().getTemporalWorkflowId());
        assertEquals(1, row("one").getAttempts());
        assertEquals(1L, row("one").getRowVersion());
        assertTrue(state.claim(candidate, "old-snapshot", NOW).isEmpty());
    }

    @Test void recoveredLeaseRejectsOldSuccessAndFailureWithoutChangingTheNewRunProjection() {
        var first = state.claim(dispatch("one", "tenant-a"), "same-worker", NOW).orElseThrow();
        assertTrue(state.recover(row("one"), NOW.plusSeconds(1), NOW));
        assertEquals("QUEUED", runStatus("one"));
        var second = state.claim(row("one"), "same-worker", NOW).orElseThrow();
        assertFalse(state.complete(first, "old-remote-run", NOW));
        assertFalse(state.fail(first, "late error", true, NOW));
        assertFalse(state.beforeStart(first, NOW));
        assertEquals("DISPATCHING", runStatus("one"));
        assertEquals("DISPATCHING", row("one").getStatus());
        assertEquals(2, row("one").getAttempts());
        assertTrue(state.complete(second, "new-remote-run", NOW));
        assertEquals("DISPATCHED", row("one").getStatus());
        assertEquals("new-remote-run", runs.findByTenantIdAndId("tenant-a", "run-one").orElseThrow().getTemporalRunId());
    }

    @ParameterizedTest @ValueSource(ints = {0, 9})
    void remoteFailurePersistsRetryOrExhaustionWithTheRunInOneTransaction(int priorAttempts) {
        dispatch("one", "tenant-a");
        jdbc.update("update t_soar_dispatch_outbox set attempts=?,row_version=row_version+1 where id='one'", priorAttempts);
        var claim = state.claim(row("one"), "owner", NOW).orElseThrow();
        assertTrue(state.fail(claim, "remote timeout", false, NOW));
        assertEquals(priorAttempts == 9 ? "DEAD" : "PENDING", row("one").getStatus());
        assertEquals(priorAttempts == 9 ? "DEAD" : "QUEUED", runStatus("one"));
        assertEquals(priorAttempts + 1, row("one").getAttempts());
        assertNull(row("one").getClaimedBy());
        if (priorAttempts == 0) assertTrue(row("one").getNextAttemptAt().isAfter(NOW));
    }

    @Test void claimOwnershipIncludesTenantAndWorker() {
        var claim = state.claim(dispatch("one", "tenant-a"), "owner", NOW).orElseThrow();
        var otherWorker = new SoarDispatchState.Claim(claim.tenant(), claim.id(), claim.runId(), claim.version(), "other", claim.run());
        assertFalse(state.fail(otherWorker, "bad", true, NOW));
        var otherTenant = new SoarDispatchState.Claim("tenant-b", claim.id(), claim.runId(), claim.version(), "owner", claim.run());
        assertFalse(TenantContext.callWith("tenant-b", () -> state.complete(otherTenant, "bad", NOW)));
        assertEquals("DISPATCHING", row("one").getStatus());
    }

    @Test void runAndOutboxCompletionRollbackTogetherOnConstraintFailure() {
        var claim = state.claim(dispatch("one", "tenant-a"), "owner", NOW).orElseThrow();
        assertThrows(RuntimeException.class, () -> state.complete(claim, "x".repeat(1024), NOW));
        assertEquals("DISPATCHING", row("one").getStatus());
        assertEquals(claim.version(), row("one").getRowVersion());
        assertNull(runs.findByTenantIdAndId("tenant-a", "run-one").orElseThrow().getTemporalRunId());
        assertTrue(state.complete(claim, "valid-id", NOW));
    }

    @ParameterizedTest @ValueSource(strings = {"RUNNING", "WAITING_APPROVAL", "WAITING_INPUT", "CANCELLING",
            "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "ACTION_UNKNOWN", "CANCELLED", "SUPPRESSED"})
    void failureCannotResetAnExecutionOrAnOperatorDecision(String status) {
        var claim = state.claim(dispatch("one", "tenant-a"), "owner", NOW).orElseThrow();
        jdbc.update("update t_soar_run set status=?,row_version=row_version+1 where id='run-one'", status);
        assertTrue(state.fail(claim, "remote timeout", true, NOW));
        assertEquals(status, runStatus("one"));
        assertEquals("DEAD", row("one").getStatus());
    }

    @Test void cancellationBeforeStartPreventsExecutionAndAfterStartPreservesTheDecision() {
        var claim = state.claim(dispatch("one", "tenant-a"), "owner", NOW).orElseThrow();
        jdbc.update("update t_soar_run set status='CANCELLING',row_version=row_version+1 where id='run-one'");
        assertFalse(state.beforeStart(claim, NOW));
        assertEquals("CANCELLED", row("one").getStatus());
        assertEquals("CANCELLING", runStatus("one"));
        var other = state.claim(dispatch("two", "tenant-a"), "owner", NOW).orElseThrow();
        jdbc.update("update t_soar_run set status='CANCELLING',row_version=row_version+1 where id='run-two'");
        assertTrue(state.complete(other, "accepted", NOW));
        assertEquals("CANCELLING", runStatus("two"));
        assertEquals("DISPATCHED", row("two").getStatus());
    }

    @Test void nonDispatchableRunIsNeverResurrected() {
        var candidate = dispatch("one", "tenant-a");
        jdbc.update("update t_soar_run set status='HOLD',row_version=row_version+1 where id='run-one'");
        assertTrue(state.claim(candidate, "owner", NOW).isEmpty());
        assertEquals("HOLD", runStatus("one"));
        assertEquals("CANCELLED", row("one").getStatus());
        assertEquals(0, row("one").getAttempts());
    }

    @Test void repeatedCrashesConsumeBudgetAndManualRequeueCannotReuseOldToken() {
        var candidate = dispatch("one", "tenant-a");
        SoarDispatchState.Claim last = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            last = state.claim(candidate, "owner", NOW).orElseThrow();
            assertEquals(attempt, row("one").getAttempts());
            assertTrue(state.recover(row("one"), NOW.plusSeconds(1), NOW));
            candidate = row("one");
        }
        assertEquals("DEAD", row("one").getStatus());
        assertEquals("DEAD", runStatus("one"));
        assertTrue(state.claim(row("one"), "owner", NOW).isEmpty());
        var requeued = row("one");
        requeued.setStatus("PENDING"); requeued.setAttempts(0);
        dispatches.saveAndFlush(requeued);
        jdbc.update("update t_soar_run set status='QUEUED',row_version=row_version+1 where id='run-one'");
        var replacement = state.claim(row("one"), "owner", NOW).orElseThrow();
        assertFalse(state.complete(last, "stale", NOW));
        assertTrue(state.complete(replacement, "new", NOW));
    }

    @Test void legacyExhaustionAndRecoveryScanAreBounded() {
        for (int i = 0; i < 4; i++) {
            var claim = state.claim(dispatch("row-" + i, "tenant-a"), "owner", NOW).orElseThrow();
            assertNotNull(claim);
        }
        var batch = dispatches.findRecoveryCandidates(NOW.plusSeconds(1), 10, 2);
        assertEquals(2, batch.size());
        batch.forEach(candidate -> assertTrue(state.recover(candidate, NOW.plusSeconds(1), NOW)));
        assertEquals(2L, dispatches.countByStatus("DISPATCHING"));
        jdbc.update("update t_soar_dispatch_outbox set attempts=10,row_version=row_version+1 where status='PENDING'");
        var exhausted = dispatches.findRecoveryCandidates(NOW.minusSeconds(1), 10, 1);
        assertEquals(1, exhausted.size());
        assertTrue(state.recover(exhausted.getFirst(), NOW.minusSeconds(1), NOW));
        assertEquals("DEAD", row(exhausted.getFirst().getId()).getStatus());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void locksOnOneTenantDoNotBlockAnotherTenant(boolean lockOutbox) throws Exception {
        var a = dispatch("one", "tenant-a");
        var b = TenantContext.callWith("tenant-b", () -> dispatch("two", "tenant-b"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                if (lockOutbox) {
                    jdbc.queryForObject("select id from t_soar_dispatch_outbox where id='one' for update", String.class);
                } else {
                    jdbc.queryForObject("select id from t_soar_run where id='run-one' for update", String.class);
                }
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var progress = executor.submit(() -> {
                    try (var ignored = TenantContext.openSystem()) {
                        assertTrue(state.claim(a, "owner", NOW).isEmpty());
                        return state.claim(b, "owner", NOW);
                    }
                });
                assertTrue(progress.get(5, TimeUnit.SECONDS).isPresent());
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void remoteCallbackAfterRecoveryCannotOverwriteReplacementLease(boolean failure) {
        dispatch("one", "tenant-a");
        var temporal = mock(TemporalExecutor.class);
        when(temporal.isAvailable()).thenReturn(true);
        when(temporal.startWorkflow(any(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            assertTrue(state.recover(row("one"), now.plusSeconds(1), now));
            assertTrue(state.claim(row("one"), "replacement", now).isPresent());
            if (failure) throw new IllegalStateException("old callback");
            return WorkflowExecution.newBuilder().setRunId("old-remote").build();
        });
        TenantContext.runAsSystem(new SoarDispatchWorker(dispatches, runs, versions, state, temporal, new ObjectMapper())::tick);
        assertEquals("DISPATCHING", row("one").getStatus());
        assertEquals("replacement", row("one").getClaimedBy());
        assertEquals(3L, row("one").getRowVersion());
        assertEquals("DISPATCHING", runStatus("one"));
        assertNull(runs.findByTenantIdAndId("tenant-a", "run-one").orElseThrow().getTemporalRunId());
    }

    SoarDispatchOutboxEntity dispatch(String id, String tenant) {
        jdbc.update("insert into t_soar_run (id,tenant_id,request_id,execution_series_id,playbook_id,playbook_version_id,"
                + "playbook_version_no,definition_hash,trigger_type,status,requested_by,created_at,updated_at,input_json) "
                + "values (?,?,?,?,?,?,1,'hash','TEST','QUEUED','test',current_timestamp,current_timestamp,'{}')",
                "run-" + id, tenant, "request-" + id, "series-" + id, "pb-" + tenant, "version-" + tenant);
        var row = new SoarDispatchOutboxEntity();
        row.setId(id); row.setTenantId(tenant); row.setRunId("run-" + id);
        row.setStatus("PENDING"); row.setNextAttemptAt(NOW); row.setCreatedAt(NOW); row.setUpdatedAt(NOW);
        return dispatches.saveAndFlush(row);
    }

    SoarDispatchOutboxEntity row(String id) { return dispatches.findByTenantIdAndId("tenant-a", id).orElseThrow(); }
    String runStatus(String id) { return runs.findByTenantIdAndId("tenant-a", "run-" + id).orElseThrow().getStatus(); }
}
