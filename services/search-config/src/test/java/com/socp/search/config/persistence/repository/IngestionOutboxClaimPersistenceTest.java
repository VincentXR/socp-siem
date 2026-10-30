package com.socp.search.config.persistence.repository;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.IngestionOutboxEvent;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same repository contracts run against H2 and real PostgreSQL. */
@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@org.springframework.context.annotation.Import(com.socp.search.config.infrastructure.kafka.IngestionOutboxBatchStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IngestionOutboxClaimPersistenceTest {
    protected static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");
    @Autowired protected IngestionOutboxRepository repository;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired protected com.socp.search.config.infrastructure.kafka.IngestionOutboxBatchStore batchStore;

    @BeforeEach void prepare() {
        TenantContext.set("tenant-a");
        jdbc.update("delete from t_ingestion_outbox");
    }
    @AfterEach void clear() {
        jdbc.update("delete from t_ingestion_outbox");
        TenantContext.clear();
    }

    private static <T> T system(java.util.function.Supplier<T> action) {
        try (var ignored = TenantContext.openSystem()) { return action.get(); }
    }

    private void insertKeyPairs(int keys) {
        var rows = new java.util.ArrayList<IngestionOutboxEvent>();
        for (int n = 0; n < keys; n++) {
            for (int order = 0; order < 2; order++) {
                rows.add(IngestionOutboxEvent.pending("key-" + n + "-" + order,
                        "tenant-a|host|key-" + n, "{}", null));
            }
        }
        repository.saveAllAndFlush(rows);
    }

    @Test void batchClaimAndAcknowledgementUseConstantStatementsWithoutOvertakingKeys() {
        insertKeyPairs(32);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var claim = system(() -> batchStore.claim(32, 12));
        assertEquals(32, claim.rows().size());
        assertTrue(claim.rows().stream().allMatch(row -> row.getEventId().endsWith("-0")));
        assertTrue(statistics.getPrepareStatementCount() <= 4, "claim must not write each row separately");
        assertEquals(0, system(() -> batchStore.claim(32, 12)).rows().size(), "processing heads block successors");
        statistics.clear();
        var ids = claim.rows().stream().map(IngestionOutboxEvent::getId).collect(java.util.stream.Collectors.toSet());
        assertEquals(32, system(() -> batchStore.settle(claim, ids, 12)).published());
        assertTrue(statistics.getPrepareStatementCount() <= 2, "confirmation must be a bulk update");
        var next = system(() -> batchStore.claim(32, 12));
        assertEquals(32, next.rows().size());
        assertTrue(next.rows().stream().allMatch(row -> row.getEventId().endsWith("-1")));
    }

    @Test void batchStoreDoesNotElevateTenantCallersToSystemScope() {
        assertThrows(IllegalStateException.class, () -> batchStore.claim(32, 12));
        assertThrows(IllegalStateException.class, () -> batchStore.settle(
                new com.socp.search.config.infrastructure.kafka.IngestionOutboxBatchStore.Claim("token", List.of()),
                java.util.Set.of(), 12));
        assertEquals("tenant-a", TenantContext.require());
        assertTrue(!TenantContext.isSystemScope());
    }

    @Test void concurrentBatchOwnersClaimDisjointHeadsAndNeverSuccessors() throws Exception {
        insertKeyPairs(64);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return system(() -> batchStore.claim(32, 12)); });
            var b = executor.submit(() -> { start.await(); return system(() -> batchStore.claim(32, 12)); });
            start.countDown();
            var left = a.get(10, TimeUnit.SECONDS); var right = b.get(10, TimeUnit.SECONDS);
            // SKIP LOCKED is allowed to return a short page under contention.
            assertTrue(left.rows().size() <= 32 && right.rows().size() <= 32);
            assertTrue(left.rows().size() + right.rows().size() > 0);
            var ids = new java.util.HashSet<String>();
            for (var row : java.util.stream.Stream.concat(left.rows().stream(), right.rows().stream()).toList()) {
                assertTrue(row.getEventId().endsWith("-0")); assertTrue(ids.add(row.getId()));
            }
            for (int page = 0; page < 2; page++) {
                for (var row : system(() -> batchStore.claim(32, 12)).rows()) {
                    assertTrue(row.getEventId().endsWith("-0")); assertTrue(ids.add(row.getId()));
                }
            }
            assertEquals(64, ids.size());
        }
    }

    @Test void failedHeadKeepsItsSuccessorBlockedAndLateBatchAckCannotStealNewClaim() {
        insertKeyPairs(2);
        var old = system(() -> batchStore.claim(2, 12));
        var success = old.rows().getFirst(); var failed = old.rows().getLast();
        var settled = system(() -> batchStore.settle(old, java.util.Set.of(success.getId()), 12));
        assertEquals(1, settled.published()); assertEquals(1, settled.retry());
        var next = system(() -> batchStore.claim(2, 12));
        assertEquals(1, next.rows().size());
        assertEquals(success.getRoutingKey(), next.rows().getFirst().getRoutingKey());
        jdbc.update("update t_ingestion_outbox set next_attempt_at = ? where id = ?", Timestamp.from(BASE), failed.getId());
        var replacement = system(() -> batchStore.claim(2, 12));
        assertEquals(1, replacement.rows().size()); assertEquals(failed.getId(), replacement.rows().getFirst().getId());
        assertEquals(0, system(() -> batchStore.settle(old, java.util.Set.of(failed.getId()), 12)).published());
        assertEquals("PROCESSING", repository.findByIdAndTenantId(failed.getId(), "tenant-a").orElseThrow().getStatus());
        assertEquals(1, system(() -> batchStore.settle(replacement, java.util.Set.of(failed.getId()), 12)).published());
    }

    @Test void newPublicationBatchAvoidsPerRowSelectsAndPreservesSameKeyOrder() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        var rows = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> IngestionOutboxEvent.pending("batch-" + i,
                        "tenant-a|host|batch", "{}", null)).toList();
        statistics.clear();
        repository.saveAllAndFlush(rows);
        long statements = statistics.getPrepareStatementCount();
        assertTrue(statements <= 4, "batch must not SELECT/prepare per new row: " + statements);
        assertEquals(rows.stream().map(IngestionOutboxEvent::getEventId).toList(),
                jdbc.queryForList("select event_id from t_ingestion_outbox where tenant_id = 'tenant-a' order by sequence_no", String.class));
        assertEquals(200, jdbc.queryForObject("select count(*) from t_ingestion_outbox where tenant_id = 'tenant-a'", Integer.class));
        var loaded = repository.findByIdAndTenantId(rows.getFirst().getId(), "tenant-a").orElseThrow();
        loaded.setLastError("fixture diagnostic");
        repository.saveAndFlush(loaded);
        assertEquals(200, jdbc.queryForObject("select count(*) from t_ingestion_outbox where tenant_id = 'tenant-a'", Integer.class), "loaded entities must remain updates");
        assertEquals("fixture diagnostic", repository.findByIdAndTenantId(loaded.getId(), "tenant-a").orElseThrow().getLastError());
    }

    @Test void failedPublicationBatchRollsBackItsEarlierInserts() {
        var rows = new java.util.ArrayList<IngestionOutboxEvent>();
        for (int i = 0; i < 69; i++) {
            rows.add(IngestionOutboxEvent.pending("valid-" + i, "tenant-a|host|batch", "{}", null));
        }
        // The duplicate is in the second JDBC batch: earlier SQL has executed,
        // but every insert must roll back with the enclosing transaction.
        rows.add(IngestionOutboxEvent.pending("valid-0", "tenant-a|host|batch", "{}", null));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> repository.saveAllAndFlush(rows));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_ingestion_outbox where tenant_id = 'tenant-a'", Integer.class), "failed batch must not publish a durable prefix");
    }

    @Test void reclaimedRowRejectsEveryWriteFromAnExpiredOwner() {
        String id = insert(0);
        assertEquals(0, repository.claim(id, BASE, 12, 1, "stale-snapshot"));
        assertEquals(1, repository.claim(id, BASE, 12, 0, "old-owner"));
        assertEquals(0, repository.claim(id, BASE, 12, 0, "competing-owner"));
        assertEquals(1, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 100));
        assertEquals(0, repository.claim(id, BASE.plusSeconds(3), 12, 0, "stale-snapshot"));
        assertEquals(1, repository.claim(id, BASE.plusSeconds(3), 12, 1, "new-owner"));
        rejectOldOwner(id);
        assertEquals("new-owner", repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getClaimToken());
        assertEquals(2, repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getAttempts());
        assertEquals(1, repository.markPublished(id, BASE.plusSeconds(4), "new-owner"));
        assertEquals("PUBLISHED", repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getStatus());
        assertNull(repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getClaimToken());
    }

    @Test void operatorRequeueCannotReviveTheOldClaimWhenAttemptsReset() {
        String id = insert(0);
        assertEquals(1, repository.claim(id, BASE, 1, 0, "old-owner"));
        assertEquals(1, repository.markDead(id, "exhausted", BASE, "old-owner"));
        assertEquals(0, repository.requeueDead(id, "tenant-b", BASE));
        assertEquals(1, repository.requeueDead(id, "tenant-a", BASE));
        assertEquals(1, repository.claim(id, BASE, 1, 0, "new-owner"));
        rejectOldOwner(id);
        assertEquals(1, repository.markPublished(id, BASE, "new-owner"));
    }

    @Test void discardClearsOwnershipAndCannotBeRequeuedAsDead() {
        String id = insert(0);
        assertEquals(1, repository.claim(id, BASE, 1, 0, "old-owner"));
        assertEquals(1, repository.markDead(id, "exhausted", BASE, "old-owner"));
        assertEquals(0, repository.discardDead(id, "tenant-b", "reviewed", BASE));
        assertEquals(1, repository.discardDead(id, "tenant-a", "reviewed", BASE));
        assertEquals(0, repository.requeueDead(id, "tenant-a", BASE));
        rejectOldOwner(id);
        assertEquals("DISCARDED", repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getStatus());
        assertNull(repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getClaimToken());
    }

    @Test void recoveryIsBoundedAndLegacyClaimsWithNullTokensRemainRecoverable() {
        for (int i = 0; i < 8; i++) {
            String id = insert(0);
            assertEquals(1, repository.claim(id, BASE, 12, 0, "legacy-owner"));
            jdbc.update("update t_ingestion_outbox set claim_token = null where id = ?", id);
            assertEquals(0, repository.markPublished(id, BASE, "legacy-owner"));
        }
        assertEquals(3, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 3));
        assertEquals(5, jdbc.queryForObject("select count(*) from t_ingestion_outbox where status = 'PROCESSING'", Integer.class));
        assertEquals(5, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 100));
        assertEquals(0, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 100));
    }

    @Test void exhaustionIsBoundedAndDoesNotTakeAnActiveClaim() {
        for (int i = 0; i < 8; i++) insert(12);
        String active = insert(11);
        assertEquals(1, repository.claim(active, BASE, 12, 11, "active-owner"));
        assertEquals(3, repository.markExhaustedBatch(12, "exhausted", BASE, 3));
        assertEquals(5, repository.markExhaustedBatch(12, "exhausted", BASE, 100));
        assertEquals(0, repository.markExhaustedBatch(12, "exhausted", BASE, 100));
        assertEquals("PROCESSING", repository.findByIdAndTenantId(active, "tenant-a").orElseThrow().getStatus());
        assertEquals(1, repository.markPublished(active, BASE, "active-owner"));
    }

    @Test void onlyOneConcurrentClaimWins() throws Exception {
        String id = insert(0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> TenantContext.callWith("tenant-a", () -> repository.claim(id, BASE, 12, 0, "first")));
            var second = executor.submit(() -> TenantContext.callWith("tenant-a", () -> repository.claim(id, BASE, 12, 0, "second")));
            assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void olderUnresolvedRowFencesSameKeyAcrossQueriesClaimsAndRetries() {
        String first = insert(0, "tenant-a|host|same");
        String second = insert(0, "tenant-a|host|same");
        String other = insert(0, "tenant-a|host|other");

        var heads = repository.findDueOrderedBatch(BASE);
        assertEquals(3, heads.size());
        assertTrue(heads.stream().anyMatch(row -> first.equals(row.getId())));
        assertTrue(heads.stream().anyMatch(row -> second.equals(row.getId())));
        assertTrue(heads.stream().anyMatch(row -> other.equals(row.getId())));
        assertTrue(heads.stream().map(row -> row.getId()).toList().indexOf(first)
                < heads.stream().map(row -> row.getId()).toList().indexOf(second));
        assertEquals(0, repository.claim(second, BASE, 12, 0, "later-owner"));

        assertEquals(1, repository.claim(first, BASE, 12, 0, "first-owner"));
        assertEquals(1, repository.scheduleRetry(first, BASE.plusSeconds(60), "retry", BASE, "first-owner"));
        assertEquals(List.of(other), repository.findDueOrderedBatch(BASE).stream().map(row -> row.getId()).toList());
        assertEquals(0, repository.claim(second, BASE, 12, 0, "later-owner"));
        assertEquals(1, repository.claim(first, BASE.plusSeconds(60), 12, 1, "retry-owner"));
        assertEquals(1, repository.markDead(first, "confirmed failure", BASE.plusSeconds(60), "retry-owner"));
        assertEquals(1, repository.claim(second, BASE.plusSeconds(60), 12, 0, "later-owner"));
    }

    @Test void anInFlightKeyFencesAnOlderSequenceThatBecomesVisibleLater() {
        String inFlight = insert(0, "tenant-a|host|commit-race");
        long inFlightSequence = jdbc.queryForObject(
                "select sequence_no from t_ingestion_outbox where id = ?", Long.class, inFlight);
        assertEquals(1, repository.claim(inFlight, BASE, 12, 0, "visible-first"));

        String newlyVisibleOlder = insert(0, "tenant-a|host|commit-race");
        jdbc.update("update t_ingestion_outbox set sequence_no = ? where id = ?",
                inFlightSequence - 1, newlyVisibleOlder);

        assertEquals(0, repository.claim(newlyVisibleOlder, BASE, 12, 0, "must-wait"));
        assertTrue(repository.findDueOrderedBatch(BASE).stream()
                .noneMatch(row -> newlyVisibleOlder.equals(row.getId())));
        assertEquals(1, repository.markPublished(inFlight, BASE, "visible-first"));
        assertEquals(1, repository.claim(newlyVisibleOlder, BASE, 12, 0, "after-owner"));
    }

    @Test void theDatabaseLeaseAllowsOnlyOneProcessingRowPerTenantAndKey() {
        String first = insert(0, "tenant-a|host|leased");
        String second = insert(0, "tenant-a|host|leased");
        assertEquals(1, repository.claim(first, BASE, 12, 0, "owner"));
        String lease = jdbc.queryForObject(
                "select processing_key from t_ingestion_outbox where id = ?", String.class, first);
        assertEquals("tenant-a:tenant-a|host|leased", lease);

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update t_ingestion_outbox "
                                + "set status = 'PROCESSING', processing_key = ? where id = ?",
                        lease, second));
        assertEquals(1, repository.markPublished(first, BASE, "owner"));
        assertNull(jdbc.queryForObject(
                "select processing_key from t_ingestion_outbox where id = ?", String.class, first));
    }

    @Test void maintenanceSkipsLockedRowsWithoutExceedingTheBatchLimit() throws Exception {
        String locked = insert(12);
        String free = insert(12);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from t_ingestion_outbox where id = ? for update")) {
                statement.setString(1, locked);
                try (var ignored = statement.executeQuery()) {
                    assertEquals(1, repository.markExhaustedBatch(12, "exhausted", BASE, 1));
                    assertEquals("DEAD", repository.findByIdAndTenantId(free, "tenant-a").orElseThrow().getStatus());
                }
            } finally { connection.rollback(); }
        }
        jdbc.update("delete from t_ingestion_outbox");
        locked = insert(0);
        free = insert(0);
        assertEquals(1, repository.claim(locked, BASE, 12, 0, "locked"));
        assertEquals(1, repository.claim(free, BASE, 12, 0, "free"));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from t_ingestion_outbox where id = ? for update")) {
                statement.setString(1, locked);
                try (var ignored = statement.executeQuery()) {
                    assertEquals(1, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 1));
                    assertEquals("PENDING", repository.findByIdAndTenantId(free, "tenant-a").orElseThrow().getStatus());
                }
            } finally { connection.rollback(); }
        }
    }

    private void rejectOldOwner(String id) {
        assertEquals(0, repository.markPublished(id, BASE.plusSeconds(5), "old-owner"));
        assertEquals(0, repository.scheduleRetry(id, BASE.plusSeconds(6), "late retry", BASE.plusSeconds(5), "old-owner"));
        assertEquals(0, repository.markDead(id, "late failure", BASE.plusSeconds(5), "old-owner"));
    }

    protected String insert(int attempts) {
        return insert(attempts, null);
    }

    protected String insert(int attempts, String routingKey) {
        String id = UUID.randomUUID().toString();
        String effectiveRoutingKey = routingKey == null ? "tenant-a|host|" + id : routingKey;
        jdbc.update("insert into t_ingestion_outbox (id, tenant_id, event_id, routing_key, payload, status, attempts, next_attempt_at, created_at, updated_at) "
                + "values (?, 'tenant-a', ?, ?, '{}', 'PENDING', ?, ?, ?, ?)",
                id, id, effectiveRoutingKey, attempts, Timestamp.from(BASE), Timestamp.from(BASE), Timestamp.from(BASE));
        return id;
    }
}
