package com.socp.alert.persistence.repository;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmDeliveryClaimPersistenceTest {
    protected static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");
    @Autowired protected AlarmDeliveryRepository repository;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;

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
}
