package com.socp.search.config.persistence.repository;

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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same repository contracts run against H2 and real PostgreSQL. */
@DataJpaTest(showSql = false)
@org.springframework.context.annotation.Import(com.socp.search.config.service.IngestionOutboxBatchService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IngestionOutboxClaimPersistenceTest {
    protected static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");
    @Autowired protected IngestionOutboxRepository repository;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected com.socp.search.config.service.IngestionOutboxBatchService batches;

    @BeforeEach void prepare() {
        TenantContext.set("tenant-a");
        jdbc.update("delete from t_ingestion_outbox");
    }
    @AfterEach void clear() {
        jdbc.update("delete from t_ingestion_outbox");
        TenantContext.clear();
    }

    @Test void independentClaimsCommitTogetherAndOnlyAcknowledgedRowsCanAdvance() {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 32; i++) ids.add(insert(0));
        var rows = repository.findByTenantId("tenant-a");
        var claimed = batches.claimIndependent(rows, "batch-owner", 12, BASE);
        assertEquals(32, claimed.size());
        assertEquals(32, repository.countByStatus("PROCESSING"));
        assertEquals(0, batches.claimIndependent(rows, "stale-scan", 12, BASE).size());
        var acknowledged = claimed.subList(0, 16);
        assertEquals(16, batches.markAcknowledged(acknowledged, "batch-owner", BASE));
        assertEquals(16, repository.countByStatus("PUBLISHED"));
        assertEquals(16, repository.countByStatus("PROCESSING"));
        assertEquals(16, repository.recoverStaleBatch(BASE.plusSeconds(1), BASE.plusSeconds(2), 32));
        var retries = repository.findByTenantId("tenant-a").stream()
                .filter(row -> row.getStatus().equals("PENDING")).toList();
        assertEquals(16, batches.claimIndependent(retries, "replacement-owner", 12, BASE.plusSeconds(3)).size());
        assertEquals(0, batches.markAcknowledged(claimed, "batch-owner", BASE.plusSeconds(4)));
        assertEquals(16, batches.markAcknowledged(retries, "replacement-owner", BASE.plusSeconds(4)));
        assertEquals(32, repository.countByStatus("PUBLISHED"));
    }

    @Test void batchClaimsRespectAnEarlierPendingRecordAndRejectMixedKeysOrTenants() {
        insert(0, "ordered-key");
        String blocked = insert(0, "ordered-key");
        String independent = insert(0, "independent-key");
        var blockedRow = repository.findByIdAndTenantId(blocked, "tenant-a").orElseThrow();
        var independentRow = repository.findByIdAndTenantId(independent, "tenant-a").orElseThrow();
        assertEquals(List.of(independent), batches.claimIndependent(List.of(blockedRow, independentRow),
                "owner", 12, BASE).stream().map(com.socp.search.config.domain.IngestionOutboxEvent::getId).toList());
        assertEquals("PENDING", repository.findByIdAndTenantId(blocked, "tenant-a").orElseThrow().getStatus());
        assertThrows(IllegalArgumentException.class, () -> batches.claimIndependent(
                repository.findByTenantId("tenant-a"), "owner", 12, BASE));
        try (var scope = TenantContext.open("tenant-b")) {
            assertThrows(IllegalArgumentException.class,
                    () -> batches.markAcknowledged(List.of(independentRow), "owner", BASE));
        }
        assertEquals("PROCESSING", repository.findByIdAndTenantId(independent, "tenant-a").orElseThrow().getStatus());
    }

    @Test void failureInTheLastClaimRollsBackEarlierClaimsAndAttempts() {
        for (int i = 0; i < 3; i++) insert(0);
        var rows = repository.findByTenantId("tenant-a");
        String last = rows.stream().map(com.socp.search.config.domain.IngestionOutboxEvent::getId).max(String::compareTo).orElseThrow();
        jdbc.execute("alter table t_ingestion_outbox add constraint test_reject_last_claim "
                + "check (id <> '" + last + "' or status <> 'PROCESSING')");
        try {
            assertThrows(RuntimeException.class, () -> batches.claimIndependent(rows, "rolled-back", 12, BASE));
            assertEquals(3, repository.countByStatus("PENDING"));
            for (var row : repository.findByTenantId("tenant-a")) {
                assertEquals(0, row.getAttempts());
                assertNull(row.getClaimToken());
            }
        } finally { jdbc.execute("alter table t_ingestion_outbox drop constraint test_reject_last_claim"); }
    }

    @Test void batchPredicatesFenceTheStoredTenantAndStatusEvenWhenCandidateMetadataIsStale() {
        String own = insert(0);
        String foreign = insert(0);
        String pending = insert(0);
        var candidates = List.of(repository.findByIdAndTenantId(own, "tenant-a").orElseThrow(),
                repository.findByIdAndTenantId(foreign, "tenant-a").orElseThrow());
        // Candidate metadata cannot authorize a different tenant's stored row.
        jdbc.update("update t_ingestion_outbox set tenant_id = 'tenant-b' where id = ?", foreign);
        assertEquals(List.of(own), batches.claimIndependent(candidates, "owner", 12, BASE).stream()
                .map(com.socp.search.config.domain.IngestionOutboxEvent::getId).toList());
        assertEquals(0, jdbc.queryForObject("select attempts from t_ingestion_outbox where id = ?",
                Integer.class, foreign));
        jdbc.update("update t_ingestion_outbox set status = 'PROCESSING', claim_token = 'owner' where id = ?", foreign);
        jdbc.update("update t_ingestion_outbox set claim_token = 'owner' where id = ?", pending);
        assertEquals(0, repository.markPublishedBatch("tenant-b", List.of(own), BASE, "owner"));
        assertEquals(0, repository.markPublishedBatch("tenant-a", List.of(own, foreign), BASE, "stale-owner"));
        assertEquals(1, repository.markPublishedBatch("tenant-a", List.of(own, foreign, pending), BASE, "owner"));
        assertEquals("PROCESSING", jdbc.queryForObject("select status from t_ingestion_outbox where id = ?",
                String.class, foreign));
        assertEquals("PENDING", repository.findByIdAndTenantId(pending, "tenant-a").orElseThrow().getStatus());
        assertEquals(0, repository.markPublishedBatch("tenant-a", List.of(own), BASE, "owner"));
    }

    @Test void batchAndSingletonClaimsPreserveLegacyProcessingAndDelayedPredecessorFences() {
        String legacy = insert(0, "legacy-key");
        jdbc.update("update t_ingestion_outbox set status = 'PROCESSING' where id = ?", legacy);
        String behindLegacy = insert(0, "legacy-key");
        String delayed = insert(0, "delayed-key");
        jdbc.update("update t_ingestion_outbox set next_attempt_at = ? where id = ?",
                Timestamp.from(BASE.plusSeconds(60)), delayed);
        String behindDelayed = insert(0, "delayed-key");
        var candidates = List.of(repository.findByIdAndTenantId(behindLegacy, "tenant-a").orElseThrow(),
                repository.findByIdAndTenantId(behindDelayed, "tenant-a").orElseThrow());
        assertTrue(batches.claimIndependent(candidates, "blocked-owner", 12, BASE).isEmpty());
        assertEquals(0, repository.claim(behindLegacy, BASE, 12, 0, "blocked-single"));
        assertEquals(0, repository.claim(behindDelayed, BASE, 12, 0, "blocked-single"));
        jdbc.update("update t_ingestion_outbox set status = 'PUBLISHED' where id = ?", legacy);
        jdbc.update("update t_ingestion_outbox set status = 'DEAD' where id = ?", delayed);
        assertEquals(2, batches.claimIndependent(candidates, "unblocked-owner", 12, BASE).size());
    }

    @Test void competingIndependentBatchesNeverReturnTheSameDurableClaim() throws Exception {
        for (int i = 0; i < 8; i++) insert(0);
        var rows = repository.findByTenantId("tenant-a");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> {
                start.await();
                return TenantContext.callWith("tenant-a", () -> batches.claimIndependent(rows, "first-owner", 12, BASE));
            });
            var second = workers.submit(() -> {
                start.await();
                return TenantContext.callWith("tenant-a", () -> batches.claimIndependent(rows, "second-owner", 12, BASE));
            });
            start.countDown();
            var owned = new java.util.ArrayList<>(first.get(10, TimeUnit.SECONDS));
            owned.addAll(second.get(10, TimeUnit.SECONDS));
            assertEquals(8, owned.size());
            assertEquals(8, owned.stream().map(com.socp.search.config.domain.IngestionOutboxEvent::getId).distinct().count());
            assertEquals(8, repository.countByStatus("PROCESSING"));
        }
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

    @Test void unsentReleaseReturnsOnlyItsOwnReservedAttemptAtTheRetryLimit() {
        String id = insert(11);
        jdbc.update("update t_ingestion_outbox set last_error = 'previous failure' where id = ?", id);
        assertEquals(1, repository.claim(id, BASE, 12, 11, "unsent-owner"));
        assertEquals(0, repository.releaseUnsentClaim(id, "tenant-b", BASE, "unsent-owner"));
        assertEquals(0, repository.releaseUnsentClaim(id, "tenant-a", BASE, "wrong-owner"));
        var active = repository.findByIdAndTenantId(id, "tenant-a").orElseThrow();
        assertEquals("PROCESSING", active.getStatus());
        assertEquals(12, active.getAttempts());

        assertEquals(1, repository.releaseUnsentClaim(id, "tenant-a", BASE.plusSeconds(1), "unsent-owner"));
        var released = repository.findByIdAndTenantId(id, "tenant-a").orElseThrow();
        assertEquals("PENDING", released.getStatus());
        assertEquals(11, released.getAttempts());
        assertEquals("previous failure", released.getLastError());
        assertNull(released.getClaimToken());
        assertNull(released.getClaimedAt());
        assertNull(jdbc.queryForObject("select processing_key from t_ingestion_outbox where id = ?", String.class, id));
        assertEquals(0, repository.releaseUnsentClaim(id, "tenant-a", BASE.plusSeconds(1), "unsent-owner"));
        assertEquals(0, repository.markExhaustedBatch(12, "exhausted", BASE.plusSeconds(1), 100));

        assertEquals(1, repository.claim(id, BASE.plusSeconds(1), 12, 11, "replacement-owner"));
        assertEquals(0, repository.releaseUnsentClaim(id, "tenant-a", BASE.plusSeconds(2), "unsent-owner"));
        var replacement = repository.findByIdAndTenantId(id, "tenant-a").orElseThrow();
        assertEquals(12, replacement.getAttempts());
        assertEquals("replacement-owner", replacement.getClaimToken());
        assertEquals(1, repository.markDead(id, "actual final send failed", BASE.plusSeconds(2), "replacement-owner"));
        assertEquals(0, repository.releaseUnsentClaim(id, "tenant-a", BASE.plusSeconds(3), "replacement-owner"));
        assertEquals(12, repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getAttempts());
    }

    @Test void unsentReleaseNeverMakesLegacyZeroAttemptClaimsNegative() {
        String id = insert(0);
        assertEquals(1, repository.claim(id, BASE, 12, 0, "legacy-owner"));
        jdbc.update("update t_ingestion_outbox set attempts = 0 where id = ?", id);
        assertEquals(1, repository.releaseUnsentClaim(id, "tenant-a", BASE, "legacy-owner"));
        assertEquals(0, repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getAttempts());
        assertEquals("PENDING", repository.findByIdAndTenantId(id, "tenant-a").orElseThrow().getStatus());
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

    @Test void selectorKeepsExactHeadOrderTenantIsolationAndDelayedInteriorRank() {
        selectorRow("legacy-owner", "tenant-a", "shared-key", 1, BASE.minusSeconds(90), "PROCESSING");
        selectorRow("behind-legacy", "tenant-a", "shared-key", 2, BASE.minusSeconds(80), "PENDING");
        selectorRow("delayed-head", "tenant-a", "delayed-key", 3, BASE.plusSeconds(60), "PENDING");
        selectorRow("behind-delayed", "tenant-a", "delayed-key", 4, BASE.minusSeconds(80), "PENDING");
        selectorRow("foreign-shared", "tenant-b", "shared-key", 5, BASE.minusSeconds(30), "PENDING");
        selectorRow("foreign-delayed", "tenant-b", "delayed-key", 6, BASE.minusSeconds(25), "PENDING");
        selectorRow("cold-earlier", "tenant-a", "cold-earlier", 10, BASE.minusSeconds(30), "PENDING");
        selectorRow("cold-middle", "tenant-a", "cold-middle", 20, BASE.minusSeconds(10), "PENDING");
        selectorRow("cold-last", "tenant-a", "cold-last", 30, BASE, "PENDING");
        selectorRow("foreign-hot", "tenant-b", "hot-key", 50, BASE.minusSeconds(15), "PENDING");
        selectorRow("hot-history", "tenant-a", "hot-key", 90, BASE.minusSeconds(100), "PUBLISHED");
        for (int i = 0; i < 20; i++) {
            Instant due = i == 0 ? BASE.minusSeconds(20) : i == 1 ? BASE.plusSeconds(60) : BASE.minusSeconds(100);
            selectorRow("hot-" + i, "tenant-a", "hot-key", 100 + i, due, "PENDING");
        }
        var expected = new java.util.ArrayList<>(List.of("foreign-shared", "cold-earlier", "foreign-delayed", "hot-0"));
        for (int i = 2; i < 16; i++) expected.add("hot-" + i);
        expected.addAll(List.of("foreign-hot", "cold-middle", "cold-last"));

        TenantContext.runAsSystem(() -> assertEquals(expected, repository.findDueOrderedBatch(BASE).stream()
                .map(com.socp.search.config.domain.IngestionOutboxEvent::getId).toList()));
        // The future rank-two row still fences later claims even though the
        // selector may prefetch their identities for the ordered lane.
        assertEquals(1, repository.claim("hot-0", BASE, 12, 0, "head-owner"));
        assertEquals(1, repository.markPublished("hot-0", BASE, "head-owner"));
        assertEquals(0, repository.claim("hot-2", BASE, 12, 0, "cannot-overtake"));
        assertNull(jdbc.queryForObject("select processing_key from t_ingestion_outbox where id='legacy-owner'", String.class));
    }

    @Test void selectorCapsTiedSequencesBeforeFilteringFutureMembers() {
        selectorRow("middle-00", "tenant-a", "middle-key", 100, BASE, "PENDING");
        selectorRow("middle-01", "tenant-a", "middle-key", 102, BASE.minusSeconds(30), "PENDING");
        selectorRow("other-00", "tenant-a", "other-key", 100, BASE, "PENDING");
        selectorRow("other-01", "tenant-a", "other-key", 101, BASE.minusSeconds(30), "PENDING");
        // GENERATED BY DEFAULT permits explicit equal sequences. They must not
        // create duplicate heads or turn the sixteenth sequence into >16 rows.
        for (int i = 19; i >= 0; i--) {
            Instant due = i == 5 ? BASE.plusSeconds(60) : i == 19 ? BASE.minusSeconds(30) : BASE;
            selectorRow(String.format("tie-%02d", i), "tenant-a", "tied-key", 100, due, "PENDING");
        }
        var expected = new java.util.ArrayList<>(List.of("middle-00", "other-00"));
        for (int i = 0; i < 16; i++) if (i != 5) expected.add(String.format("tie-%02d", i));
        // Equal head due/sequence must still compare member sequence before
        // head ID: sequence 101 follows all 100s and precedes sequence 102.
        expected.addAll(List.of("other-01", "middle-01"));

        var selected = repository.findDueOrderedBatch(BASE);

        assertEquals(expected, selected.stream().map(com.socp.search.config.domain.IngestionOutboxEvent::getId).toList());
        assertEquals(selected.size(), selected.stream().map(com.socp.search.config.domain.IngestionOutboxEvent::getId).distinct().count());
    }

    @Test void selectorAppliesTheGlobalLimitAfterExpandingTheEarliestHeads() {
        for (int i = 0; i < 20; i++) {
            selectorRow("first-hot-" + i, "tenant-a", "first-hot", 10 + i, BASE.minusSeconds(1), "PENDING");
        }
        for (int i = 0; i < 210; i++) {
            selectorRow("independent-" + i, "tenant-a", "key-" + i, 1000 + i, BASE, "PENDING");
        }
        var expected = new java.util.ArrayList<String>();
        for (int i = 0; i < 16; i++) expected.add("first-hot-" + i);
        for (int i = 0; i < 184; i++) expected.add("independent-" + i);

        assertEquals(expected, repository.findDueOrderedBatch(BASE).stream()
                .map(com.socp.search.config.domain.IngestionOutboxEvent::getId).toList());
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
        assertEquals(0, repository.releaseUnsentClaim(id, "tenant-a", BASE.plusSeconds(5), "old-owner"));
    }

    protected String insert(int attempts) {
        return insert(attempts, null);
    }

    private void selectorRow(String id, String tenant, String key, long sequence, Instant due, String status) {
        jdbc.update("insert into t_ingestion_outbox "
                + "(id, tenant_id, event_id, routing_key, payload, status, attempts, next_attempt_at, created_at, updated_at, sequence_no) "
                + "values (?, ?, ?, ?, '{}', ?, 0, ?, ?, ?, ?)",
                id, tenant, id, key, status, Timestamp.from(due), Timestamp.from(BASE), Timestamp.from(BASE), sequence);
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
