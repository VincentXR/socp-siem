package com.socp.soar.web.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.service.SoarSignalWorker;
import com.socp.soar.web.service.TemporalExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(30)
class SoarSignalClaimPersistenceTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @Autowired protected SoarSignalOutboxRepository signals;
    @Autowired protected SoarRunRepository runs;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;

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
            jdbc.update("insert into t_soar_run (id,tenant_id,request_id,execution_series_id,playbook_id,playbook_version_id,"
                    + "playbook_version_no,definition_hash,trigger_type,status,requested_by,created_at,updated_at,temporal_workflow_id) "
                    + "values (?,?,?,?,?,?,1,'hash','TEST','RUNNING','test',current_timestamp,current_timestamp,?)",
                    "run-" + tenant, tenant, "request-" + tenant, "series-" + tenant, "pb-" + tenant,
                    "version-" + tenant, "workflow-" + tenant);
        }
    }

    @AfterEach void cleanup() { TenantContext.clear(); clear(); }

    private void clear() {
        jdbc.update("delete from t_soar_signal_outbox");
        jdbc.update("delete from t_soar_run");
        jdbc.update("delete from t_soar_playbook_version");
        jdbc.update("delete from t_soar_playbook");
    }

    @Test void competingReplicasClaimOneVersionAndChargeOneAttempt() throws Exception {
        signal("one", "tenant-a");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                return TenantContext.callWith("tenant-a", () -> signals.claim("tenant-a", "one", "worker", NOW, 0, 10));
            })).toList();
            start.countDown();
            assertEquals(1, tasks.get(0).get(5, TimeUnit.SECONDS) + tasks.get(1).get(5, TimeUnit.SECONDS));
        }
        var row = row("one");
        assertEquals("SENDING", row.getStatus());
        assertEquals(1, row.getAttempts());
        assertEquals(1L, row.getRowVersion());
        assertEquals(0, TenantContext.callWith("tenant-b", () -> signals.completeClaim("tenant-b", "one", 1,
                "worker", "SENT", NOW, null, NOW)));
        assertEquals(1, finish("one", 1, "SENT"));
        assertEquals("SENT", row("one").getStatus());
    }

    @Test void recoveredAndReclaimedAttemptRejectsLateSuccessAndFailureEvenOnTheSameWorker() {
        signal("one", "tenant-a");
        assertEquals(1, signals.claim("tenant-a", "one", "worker", NOW, 0, 10));
        assertEquals(1, signals.recoverStaleClaims(NOW.plusSeconds(1), NOW, 10, 100));
        assertEquals(0, signals.claim("tenant-a", "one", "worker", NOW, 0, 10));
        assertEquals(1, signals.claim("tenant-a", "one", "worker", NOW, 2, 10));
        assertEquals(0, finish("one", 1, "SENT"));
        assertEquals(0, finish("one", 1, "DEAD"));
        assertEquals("SENDING", row("one").getStatus());
        assertEquals(2, row("one").getAttempts());
        assertEquals(1, finish("one", 3, "SENT"));
    }

    @Test void replacedDecisionInvalidatesAnInflightPayloadWithoutLosingTheReplacement() {
        signal("one", "tenant-a");
        assertEquals(1, signals.claim("tenant-a", "one", "worker", NOW, 0, 10));
        var replacement = row("one");
        replacement.setStatus("PENDING");
        replacement.setPayloadJson("{\"approve\":false,\"approvalKey\":\"one\"}");
        replacement = signals.saveAndFlush(replacement);
        assertEquals(2L, replacement.getRowVersion());
        assertEquals(0, finish("one", 1, "SENT"));
        assertEquals(1, signals.claim("tenant-a", "one", "worker", NOW, 2, 10));
        assertEquals(1, finish("one", 3, "SENT"));
        assertTrue(row("one").getPayloadJson().contains("false"));
    }

    @Test void claimRecoveryAndLegacyExhaustionAreBounded() {
        for (int i = 0; i < 4; i++) {
            signal("signal-" + i, "tenant-a");
            assertEquals(1, signals.claim("tenant-a", "signal-" + i, "worker", NOW, 0, 10));
        }
        assertEquals(2, signals.recoverStaleClaims(NOW.plusSeconds(1), NOW, 10, 2));
        assertEquals(2, signals.countByTenantIdAndStatus("tenant-a", "SENDING"));
        assertEquals(2, signals.recoverStaleClaims(NOW.plusSeconds(1), NOW, 1, 2));
        assertEquals(2, signals.countByTenantIdAndStatus("tenant-a", "DEAD"));
        assertEquals(1, signals.markExhausted(NOW, 1, 1));
        assertEquals(1, signals.markExhausted(NOW, 1, 1));
        assertEquals(4, signals.countByTenantIdAndStatus("tenant-a", "DEAD"));
    }

    @Test void exhaustedLeaseCanBeRequeuedWithoutAcceptingItsOldAcknowledgement() {
        signal("one", "tenant-a");
        assertEquals(1, signals.claim("tenant-a", "one", "worker", NOW, 0, 1));
        assertEquals(1, signals.recoverStaleClaims(NOW.plusSeconds(1), NOW, 1, 100));
        var requeued = row("one");
        requeued.setStatus("PENDING");
        requeued.setAttempts(0);
        requeued = signals.saveAndFlush(requeued);
        assertEquals(0, finish("one", 1, "SENT"));
        assertEquals(1, signals.claim("tenant-a", "one", "worker", NOW, requeued.getRowVersion(), 10));
        assertEquals(1, finish("one", requeued.getRowVersion() + 1, "SENT"));
        assertEquals(1, row("one").getAttempts());
    }

    @Test void recoverySkipsALockedClaimWithoutBlockingOtherTenants() throws Exception {
        signal("a", "tenant-a");
        TenantContext.runWith("tenant-b", () -> signal("b", "tenant-b"));
        assertEquals(1, signals.claim("tenant-a", "a", "worker", NOW, 0, 10));
        assertEquals(1, TenantContext.callWith("tenant-b", () -> signals.claim("tenant-b", "b", "worker", NOW, 0, 10)));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var lock = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("select id from t_soar_signal_outbox where id = 'a' for update", String.class);
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var recovered = executor.submit(() -> {
                    try (var ignored = TenantContext.openSystem()) {
                        return signals.recoverStaleClaims(NOW.plusSeconds(1), NOW, 10, 100);
                    }
                });
                assertEquals(1, recovered.get(5, TimeUnit.SECONDS));
                assertEquals("SENDING", row("a").getStatus());
            } finally { release.countDown(); }
            lock.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void realWorkerLeavesCorruptSignalDeadAndOnlyAcknowledgesDeliveredValidSignal() {
        var invalid = signal("invalid", "tenant-a");
        invalid.setPayloadJson("{broken");
        signals.saveAndFlush(invalid);
        signal("valid", "tenant-a");
        TemporalExecutor temporal = mock(TemporalExecutor.class);
        when(temporal.isAvailable()).thenReturn(true);
        var worker = new SoarSignalWorker(signals, runs, temporal, new ObjectMapper());
        TenantContext.runAsSystem(worker::tick);
        assertEquals("DEAD", row("invalid").getStatus());
        assertEquals("SENT", row("valid").getStatus());
        verify(temporal).decideGate("workflow-tenant-a", true, "valid", false);
        verify(temporal, never()).decide(anyString(), anyBoolean());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void realWorkerCannotCompleteAnotherAttemptsLeaseAfterTemporalReturns(boolean remoteFailure) {
        signal("one", "tenant-a");
        TemporalExecutor temporal = mock(TemporalExecutor.class);
        when(temporal.isAvailable()).thenReturn(true);
        doAnswer(call -> {
            // Both fixture operations share one exact database-representable
            // time; H2 rounds native TIMESTAMP(6) binds to microseconds.
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            assertEquals(1, signals.recoverStaleClaims(now.plusSeconds(1), now, 10, 100));
            assertEquals(1, signals.claim("tenant-a", "one", "replacement", now, 2, 10));
            if (remoteFailure) throw new IllegalStateException("late remote failure");
            return null;
        }).when(temporal).decideGate("workflow-tenant-a", true, "one", false);
        TenantContext.runAsSystem(new SoarSignalWorker(signals, runs, temporal, new ObjectMapper())::tick);
        var current = row("one");
        assertEquals("SENDING", current.getStatus());
        assertEquals("replacement", current.getClaimedBy());
        assertEquals(3L, current.getRowVersion());
        assertEquals(2, current.getAttempts());
        assertNotEquals("late remote failure", current.getLastError());
    }

    private SoarSignalOutboxEntity signal(String id, String tenant) {
        var signal = new SoarSignalOutboxEntity();
        signal.setId(id); signal.setTenantId(tenant); signal.setRunId("run-" + tenant);
        signal.setSignalType("APPROVAL"); signal.setSignalKey(id);
        signal.setPayloadJson("{\"approve\":true,\"approvalKey\":\"" + id + "\"}");
        signal.setStatus("PENDING"); signal.setNextAttemptAt(NOW); signal.setCreatedAt(NOW); signal.setUpdatedAt(NOW);
        return signals.saveAndFlush(signal);
    }

    private SoarSignalOutboxEntity row(String id) { return signals.findByTenantIdAndId("tenant-a", id).orElseThrow(); }
    private int finish(String id, long version, String status) {
        return signals.completeClaim("tenant-a", id, version, "worker", status, NOW, null, NOW);
    }
}
