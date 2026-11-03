package com.socp.alert.persistence.repository;

import com.socp.platform.tenant.context.TenantContext;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The same repository contracts run against H2 and real PostgreSQL. */
@DataJpaTest(showSql = false)
@org.springframework.context.annotation.Import(com.socp.alert.service.AlarmDeliveryBatchService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmDeliveryClaimPersistenceTest {
    protected static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");
    @Autowired protected AlarmDeliveryRepository repository;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected com.socp.alert.service.AlarmDeliveryBatchService batches;
    @Autowired protected org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired protected jakarta.persistence.EntityManagerFactory entityManagers;

    @BeforeEach void prepare() {
        TenantContext.set("tenant-a");
        jdbc.update("delete from alarm_delivery");
    }
    @AfterEach void clear() {
        jdbc.update("delete from alarm_delivery");
        TenantContext.clear();
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
        assertEquals(1, repository.markDelivered(id, BASE.plusSeconds(4), "new-owner"));
        assertEquals("DELIVERED", repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getStatus());
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
        assertEquals(1, repository.markDelivered(id, BASE, "new-owner"));
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
            jdbc.update("update alarm_delivery set claim_token = null where id = ?", id);
            assertEquals(0, repository.markDelivered(id, BASE, "legacy-owner"));
        }
        assertEquals(3, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 3));
        assertEquals(5, jdbc.queryForObject("select count(*) from alarm_delivery where status = 'PROCESSING'", Integer.class));
        assertEquals(5, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 100));
        assertEquals(0, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 100));
    }

    @Test void dueDeliveryPriorityFollowsEligibilityAndSkipsFutureRetries() {
        String older = insert(0);
        String earlierDue = insert(0);
        String future = insert(0);
        jdbc.update("update alarm_delivery set created_at=?, next_attempt_at=? where id=?",
                Timestamp.from(BASE.minusSeconds(100)), Timestamp.from(BASE.minusSeconds(1)), older);
        jdbc.update("update alarm_delivery set next_attempt_at=? where id=?",
                Timestamp.from(BASE.minusSeconds(10)), earlierDue);
        jdbc.update("update alarm_delivery set created_at=?, next_attempt_at=? where id=?",
                Timestamp.from(BASE.minusSeconds(200)), Timestamp.from(BASE.plusSeconds(1)), future);
        var selected = repository.findDueBatch("PENDING", BASE);
        assertEquals(java.util.List.of(earlierDue, older), selected.stream().map(row -> row.getId()).toList());
        assertEquals(3, repository.countByStatus("PENDING"));
    }

    @Test void exhaustionIsBoundedAndDoesNotTakeAnActiveClaim() {
        for (int i = 0; i < 8; i++) insert(12);
        String active = insert(11);
        assertEquals(1, repository.claim(active, BASE, 12, 11, "active-owner"));
        assertEquals(3, repository.markExhaustedBatch(12, "exhausted", BASE, 3));
        assertEquals(5, repository.markExhaustedBatch(12, "exhausted", BASE, 100));
        assertEquals(0, repository.markExhaustedBatch(12, "exhausted", BASE, 100));
        assertEquals("PROCESSING", repository.findByIdAndTenantId(active, "tenant-a").orElseThrow().getStatus());
        assertEquals(1, repository.markDelivered(active, BASE, "active-owner"));
    }

    @Test void onlyOneConcurrentClaimWins() throws Exception {
        String id = insert(0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> TenantContext.callWith("tenant-a", () -> repository.claim(id, BASE, 12, 0, "first")));
            var second = executor.submit(() -> TenantContext.callWith("tenant-a", () -> repository.claim(id, BASE, 12, 0, "second")));
            assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void maintenanceSkipsLockedRowsWithoutExceedingTheBatchLimit() throws Exception {
        String locked = insert(12);
        String free = insert(12);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from alarm_delivery where id = ? for update")) {
                statement.setString(1, locked);
                try (var ignored = statement.executeQuery()) {
                    assertEquals(1, repository.markExhaustedBatch(12, "exhausted", BASE, 1));
                    assertEquals("DEAD", repository.findByIdAndTenantId(free, "tenant-a").orElseThrow().getStatus());
                }
            } finally { connection.rollback(); }
        }
        jdbc.update("delete from alarm_delivery");
        locked = insert(0);
        free = insert(0);
        assertEquals(1, repository.claim(locked, BASE, 12, 0, "locked"));
        assertEquals(1, repository.claim(free, BASE, 12, 0, "free"));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from alarm_delivery where id = ? for update")) {
                statement.setString(1, locked);
                try (var ignored = statement.executeQuery()) {
                    assertEquals(1, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 1));
                    assertEquals("PENDING", repository.findByIdAndTenantId(free, "tenant-a").orElseThrow().getStatus());
                }
            } finally { connection.rollback(); }
        }
    }

    private void rejectOldOwner(String id) {
        assertEquals(0, repository.markDelivered(id, BASE.plusSeconds(5), "old-owner"));
        assertEquals(0, repository.scheduleRetry(id, BASE.plusSeconds(6), "late retry", BASE.plusSeconds(5), "old-owner"));
        assertEquals(0, repository.markDead(id, "late failure", BASE.plusSeconds(5), "old-owner"));
    }

    protected String insert(int attempts) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into alarm_delivery (id, tenant_id, alarm_id, destination, payload, status, attempts, next_attempt_at, created_at, updated_at) "
                + "values (?, 'tenant-a', ?, 'NOTIFY', '{}', 'PENDING', ?, ?, ?, ?)",
                id, id, attempts, Timestamp.from(BASE), Timestamp.from(BASE), Timestamp.from(BASE));
        return id;
    }

    @Test void cohortsPreserveMixedOutcomesAndRejectLateOwnerReceipts() {
        var rows = java.util.stream.IntStream.range(0, 4).mapToObj(i -> insert(i == 3 ? 11 : 0))
                .map(id -> repository.findByIdAndTenantId(id, "tenant-a").orElseThrow()).toList();
        var claims = batches.claim(rows, 12);
        assertEquals(4, claims.size());
        assertEquals(4, repository.countByStatus("PROCESSING"));
        var outcomes = java.util.stream.IntStream.range(0, 4).mapToObj(i ->
                new com.socp.alert.service.AlarmDeliveryBatchService.Outcome(claims.get(i),
                        i == 0, i != 2, "retry reason")).toList();
        var receipt = batches.acknowledge(outcomes, 12);
        assertEquals(1, receipt.delivered());
        // Classify by the claimed attempt, independently of UUID lock order.
        int expectedDead = (int) outcomes.stream().filter(o -> !o.delivered()
                && (!o.retryable() || o.claim().delivery().getAttempts() == 11)).count();
        assertEquals(expectedDead, receipt.dead());
        assertEquals(3 - expectedDead, receipt.retry());
        assertEquals(0, receipt.stale());
        assertEquals(4, batches.acknowledge(outcomes, 12).stale());
    }

    @Test void competingCohortsClaimEachIdentityOnceAndCannotCrossTenants() throws Exception {
        var rows = java.util.stream.IntStream.range(0, 8).mapToObj(i -> insert(0))
                .map(id -> repository.findByIdAndTenantId(id, "tenant-a").orElseThrow()).toList();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> TenantContext.callWith("tenant-a", () -> batches.claim(rows, 12)));
            var second = workers.submit(() -> TenantContext.callWith("tenant-a", () -> batches.claim(rows, 12)));
            var claims = new java.util.ArrayList<>(first.get(5, TimeUnit.SECONDS));
            claims.addAll(second.get(5, TimeUnit.SECONDS));
            assertEquals(8, claims.size());
            assertEquals(8, claims.stream().map(c -> c.delivery().getId()).distinct().count());
            try (var ignored = TenantContext.open("tenant-b")) {
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> batches.claim(rows, 12));
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> batches.acknowledge(
                        claims.stream().map(c -> new com.socp.alert.service.AlarmDeliveryBatchService.Outcome(c, true, false, null)).toList(), 12));
            }
        }
    }

    @Test void interruptedClaimAndReceiptTransactionsRollBackEveryRow() {
        var rows = java.util.stream.IntStream.range(0, 3).mapToObj(i -> insert(0))
                .map(id -> repository.findByIdAndTenantId(id, "tenant-a").orElseThrow()).toList();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            assertEquals(3, batches.claim(rows, 12).size());
            throw new IllegalStateException("abort claim commit");
        }));
        assertEquals(3, repository.countByStatus("PENDING"));
        var claims = batches.claim(rows, 12);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            batches.acknowledge(claims.stream().map(c -> new com.socp.alert.service.AlarmDeliveryBatchService.Outcome(
                    c, true, false, null)).toList(), 12);
            throw new IllegalStateException("abort receipt commit");
        }));
        assertEquals(3, repository.countByStatus("PROCESSING"));
        assertEquals(0, repository.countByStatus("DELIVERED"));
        assertEquals(3, repository.recoverStaleBatch(Instant.now().plusSeconds(1), Instant.now(), 100));
        var renewed = repository.findDueBatch("PENDING", Instant.now().plusSeconds(1));
        var newClaims = batches.claim(renewed, 12);
        assertEquals(3, newClaims.size());
        assertEquals(3, batches.acknowledge(claims.stream().map(c ->
                new com.socp.alert.service.AlarmDeliveryBatchService.Outcome(c, true, false, null)).toList(), 12).stale());
        assertEquals(3, batches.acknowledge(newClaims.stream().map(c ->
                new com.socp.alert.service.AlarmDeliveryBatchService.Outcome(c, true, false, null)).toList(), 12).delivered());
    }

    @Test void eightClaimsAndReceiptsUseTwoPhysicalTransactions() {
        var rows = java.util.stream.IntStream.range(0, 8).mapToObj(i -> insert(0))
                .map(id -> repository.findByIdAndTenantId(id, "tenant-a").orElseThrow()).toList();
        var statistics = entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true); statistics.clear();
        try {
            var claims = batches.claim(rows, 12);
            assertEquals(8, claims.size());
            assertEquals(1, statistics.getSuccessfulTransactionCount());
            assertEquals(8, batches.acknowledge(claims.stream().map(c ->
                    new com.socp.alert.service.AlarmDeliveryBatchService.Outcome(c, true, false, null)).toList(), 12).delivered());
            assertEquals(2, statistics.getSuccessfulTransactionCount());
        } finally { statistics.setStatisticsEnabled(enabled); }
    }

    @Test void forgedCandidateTenantCannotClaimOrAcknowledgeAnotherTenantsRow() {
        String id = insert(0);
        jdbc.update("update alarm_delivery set tenant_id='tenant-b' where id=?", id);
        com.socp.alert.domain.AlarmDelivery row;
        java.util.List<com.socp.alert.service.AlarmDeliveryBatchService.Claim> owned;
        try (var ignored = TenantContext.open("tenant-b")) {
            row = repository.findByIdAndTenantId(id, "tenant-b").orElseThrow();
            owned = batches.claim(java.util.List.of(row), 12);
        }
        row.setTenantId("tenant-a");
        assertEquals(0, batches.claim(java.util.List.of(row), 12).size());
        assertEquals(1, batches.acknowledge(owned.stream().map(c ->
                new com.socp.alert.service.AlarmDeliveryBatchService.Outcome(c, true, false, null)).toList(), 12).stale());
        try (var ignored = TenantContext.open("tenant-b")) {
            assertEquals("PROCESSING", repository.findByIdAndTenantId(id, "tenant-b").orElseThrow().getStatus());
        }
    }
}
