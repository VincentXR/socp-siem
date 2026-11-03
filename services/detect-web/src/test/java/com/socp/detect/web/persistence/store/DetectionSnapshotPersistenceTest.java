package com.socp.detect.web.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.state.DetectionStateSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.socp.detect.web.persistence.store.DetectionSnapshotPersistenceTest$SqlInspector"})
@Import(JpaDetectionStateSnapshotStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionSnapshotPersistenceTest {
    @Autowired protected JpaDetectionStateSnapshotStore store;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbc;

    public static final class SqlInspector implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<java.util.List<String>> statements = new ThreadLocal<>();
        static final ThreadLocal<Runnable> beforeWrite = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            if (statements.get() != null) statements.get().add(sql);
            if (sql.startsWith("update t_detection_state_snapshot ") && beforeWrite.get() != null) {
                beforeWrite.get().run();
            }
            return sql;
        }
    }

    @BeforeEach
    void tenant() { TenantContext.set("snapshot-test"); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); SqlInspector.statements.remove(); SqlInspector.beforeWrite.remove(); }

    @Test
    void staleTransactionCannotCommitAnyPartOfGeneration() throws Exception {
        Instant time = Instant.parse("2026-09-10T12:00:00Z");
        store.saveAll(generation("race", time, 10));
        var loaded = new CountDownLatch(1);
        var newerCommitted = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> {
                TenantContext.set("snapshot-test");
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        SqlInspector.beforeWrite.set(() -> {
                            SqlInspector.beforeWrite.remove();
                            loaded.countDown();
                            await(newerCommitted);
                        });
                        try { store.saveAll(generation("race", time.plusSeconds(1), 11)); }
                        finally { SqlInspector.beforeWrite.remove(); }
                    });
                } finally { TenantContext.clear(); }
            });
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
                store.saveAll(generation("race", time.plusSeconds(2), 20));
            } finally { newerCommitted.countDown(); }
            assertThatThrownBy(() -> stale.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        }
        for (String rule : List.of("race-a", "race-b")) {
            assertThat(store.latest("snapshot-test", rule, 0).orElseThrow().partitionOffsets()).containsEntry(0, 20L);
        }
    }

    @Test
    void rejectedBatchDoesNotLeakHibernateDirtyUpdates() {
        Instant time = Instant.parse("2026-09-10T13:00:00Z");
        store.save(snapshot("dirty-a", time, 8));
        store.save(snapshot("dirty-b", time, 12));
        store.saveAll(generation("dirty", time.plusSeconds(1), 10));
        assertThat(store.latest("snapshot-test", "dirty-a", 0).orElseThrow().partitionOffsets()).containsEntry(0, 8L);
        assertThat(store.latest("snapshot-test", "dirty-b", 0).orElseThrow().partitionOffsets()).containsEntry(0, 12L);
    }

    @Test
    void replacesLargePayloadWithoutSelectingPreviousStateAndKeepsTenantIsolation() {
        byte[] payload = new byte[2 * 1024 * 1024];
        new java.util.Random(42).nextBytes(payload);
        Instant time = Instant.parse("2026-09-10T15:00:00Z");
        store.save(new DetectionStateSnapshot("large", "v1", "snapshot-test", 0, 10,
                payload, time, Map.of(0, 10L)));
        try (var ignored = TenantContext.open("snapshot-other")) {
            store.save(new DetectionStateSnapshot("large", "v1", "snapshot-other", 0, 30,
                    new byte[]{9}, time, Map.of(0, 30L)));
        }
        SqlInspector.statements.set(new java.util.ArrayList<>());
        store.save(new DetectionStateSnapshot("large", "v2", "snapshot-test", 0, 11,
                payload, time.plusSeconds(1), Map.of(0, 11L)));
        var selects = SqlInspector.statements.get().stream().filter(sql ->
                sql.startsWith("select ") && sql.contains("t_detection_state_snapshot")).toList();
        assertThat(selects).isNotEmpty().allMatch(sql -> !sql.contains("serialized_state"));
        SqlInspector.statements.remove();
        var restored = store.latest("snapshot-test", "large", 0).orElseThrow();
        assertThat(restored.serializedState()).containsExactly(payload);
        assertThat(restored.partitionOffsets()).containsEntry(0, 11L);
        assertThat(jdbc.queryForObject("select row_version from t_detection_state_snapshot "
                + "where tenant_id='snapshot-test' and rule_id='large'", Long.class)).isEqualTo(1L);
        try (var ignored = TenantContext.open("snapshot-other")) {
            var other = store.latest("snapshot-other", "large", 0).orElseThrow();
            assertThat(other.serializedState()).containsExactly((byte) 9);
            assertThat(other.partitionOffsets()).containsEntry(0, 30L);
        }
    }

    @Test
    void lateVersionConflictRollsBackAnAlreadyWrittenSibling() throws Exception {
        Instant time = Instant.parse("2026-09-10T16:00:00Z");
        store.saveAll(generation("partial", time, 10));
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            SqlInspector.beforeWrite.set(() -> {
                if (writes.incrementAndGet() != 2) return;
                try {
                    executor.submit(() -> jdbc.update("update t_detection_state_snapshot set row_version=row_version+1 "
                            + "where tenant_id='snapshot-test' and rule_id='partial-b'")).get(10, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            try {
                assertThatThrownBy(() -> store.saveAll(generation("partial", time.plusSeconds(1), 11)))
                        .isInstanceOf(OptimisticLockingFailureException.class);
            } finally { SqlInspector.beforeWrite.remove(); }
        }
        assertThat(writes.get()).isEqualTo(2);
        for (String rule : List.of("partial-a", "partial-b")) {
            assertThat(store.latest("snapshot-test", rule, 0).orElseThrow().partitionOffsets()).containsEntry(0, 10L);
        }
    }

    private static List<DetectionStateSnapshot> generation(String prefix, Instant time, long offset) {
        return List.of(snapshot(prefix + "-a", time, offset), snapshot(prefix + "-b", time, offset));
    }

    private static DetectionStateSnapshot snapshot(String rule, Instant time, long offset) {
        return new DetectionStateSnapshot(rule, "v1", "snapshot-test", 0, offset,
                new byte[]{1}, time, Map.of(0, offset));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("checkpoint coordination timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
