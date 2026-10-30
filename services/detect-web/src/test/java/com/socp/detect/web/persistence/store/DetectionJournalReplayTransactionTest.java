package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real JPA pages inside the same kind of enclosing transaction as a rule reload. */
@DataJpaTest(showSql = false, properties = "socp.detect.state.replay-page-size=100")
@Import({DetectionEventJournal.class, DetectionJournalReplayTransactionTest.BatchConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionJournalReplayTransactionTest {
    @Autowired DetectionEventJournal journal;
    @Autowired DetectionEventRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.socp.detect.web.engine.AlertForwarder forwarder;
    @Autowired JpaDetectionStateOwnership ownership;
    @Autowired com.socp.detect.web.persistence.repository.DetectionStateOwnerRepository owners;

    @org.springframework.boot.test.context.TestConfiguration
    static class BatchConfiguration {
        @org.springframework.context.annotation.Bean
        JpaDetectionStateOwnership ownership(com.socp.detect.web.persistence.repository.DetectionStateOwnerRepository owners) {
            return new JpaDetectionStateOwnership(owners, "batch-test", Duration.ofSeconds(30));
        }
        @org.springframework.context.annotation.Bean
        com.socp.detect.web.engine.AlertForwarder forwarder(DetectionEventJournal journal,
                com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository outbox) {
            var risk = org.mockito.Mockito.mock(com.socp.detect.web.service.EntityRiskStore.class);
            org.mockito.Mockito.when(risk.recordForAlert(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new com.socp.rule.score.RiskScorer.Score(10, "LOW", Map.of()));
            return new com.socp.detect.web.engine.AlertForwarder(
                    org.mockito.Mockito.mock(RuleSpecStore.class), risk,
                    new DetectionAlertOutboxService(outbox), journal);
        }
    }

    private TransactionTemplate transaction;
    private String tenant;
    private Instant since;

    @BeforeEach void prepare() {
        tenant = "replay-" + UUID.randomUUID();
        TenantContext.set(tenant);
        transaction = new TransactionTemplate(transactions);
        since = Instant.now().minusSeconds(60);
        transaction.executeWithoutResult(ignored -> {
            for (int index = 0; index < 251; index++) {
                DetectionEventEntity row = row("completed-" + index, index);
                row.setStatus("COMPLETED");
                row.setCompletedAt(since.plusSeconds(1));
                repository.save(row);
            }
            repository.flush();
        });
    }

    @AfterEach void cleanup() {
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("delete from t_detection_alert_outbox where tenant_id = ?", tenant);
            jdbc.update("delete from t_detection_event where tenant_id = ?", tenant);
            jdbc.update("delete from t_detection_state_owner where input_topic = ?", "test-" + tenant);
        });
        TenantContext.clear();
    }

    @Test void completedReplayDoesNotRetainTheWindowInTheRuleReloadPersistenceContext() {
        assertBounded(consumer -> journal.replayRecentForTenant(tenant, Duration.ofHours(1), consumer), 251);
        assertBounded(consumer -> journal.replayRecentForPartitions(Set.of(0), Duration.ofHours(1), consumer), 251);
        assertBounded(consumer -> journal.replayRecent(Duration.ofHours(1), consumer), 251);
    }

    @Test void checkpointReplayRemainsBoundedAndKeepsTheKafkaOffsetBoundary() {
        assertBounded(consumer -> journal.replayCompletedAfter(tenant, since, Set.of(0), consumer), 251);
        assertBounded(consumer -> journal.replayCompletedAfter(tenant, since, Set.of(0), Map.of(0, 49L), consumer), 201);
    }

    @Test void tenantDiscoveryIsScopedAndCheckpointSqlIncludesUncheckpointedPartitions() {
        var tenants = new ArrayList<String>();
        assertTrue(journal.replayRecoveryTenants(Set.of(0), Duration.ofHours(1), tenants::add));
        assertEquals(List.of(tenant), tenants);
        tenants.clear();
        assertTrue(journal.replayRecoveryTenants(Set.of(7), Duration.ofHours(1), tenants::add));
        assertTrue(tenants.isEmpty());
        assertBounded(consumer -> journal.replayCompletedAfter(tenant, since, Set.of(), Map.of(0, 249L), consumer), 1);
        assertBounded(consumer -> journal.replayCompletedAfter(tenant, since, Set.of(0), Map.of(7, 249L), consumer), 251);
    }

    @Test void pendingReplayRetainsItsCapWithoutJoiningTheEnclosingTransaction() {
        transaction.executeWithoutResult(ignored -> {
            for (int index = 0; index < 251; index++) repository.save(row("pending-" + index, index + 1000));
            repository.flush();
        });
        transaction.executeWithoutResult(ignored -> {
            List<Integer> sizes = new ArrayList<>();
            journal.replayPendingPages(Set.of(0), Duration.ofHours(1), 150, batch -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                        "Replay callbacks must not retain journal entities in a reload transaction");
                assertTrue(entityManager.unwrap(Session.class).getStatistics().getEntityCount() <= 100,
                        "Pending replay may not retain entities from earlier pages");
                sizes.add(batch.size());
            });
            assertEquals(List.of(100, 50), sizes);
            assertEquals(0, entityManager.unwrap(Session.class).getStatistics().getEntityCount());
        });
    }

    @Test void batchClaimsRemainTenantScopedIdempotentAndAtomic() {
        var a = batchEntry("batch-new").result().event();
        var b = batchEntry("batch-next").result().event();
        var first = new DetectionStateStore.Input(a, "socp-events", 0, 1000L, "key");
        var second = new DetectionStateStore.Input(b, "socp-events", 0, 1001L, "key");
        assertEquals(List.of(DetectionEventClaim.NEW, DetectionEventClaim.PENDING), journal.preclaimBatch(List.of(first, first)));
        assertEquals(1, jdbc.queryForObject("select count(*) from t_detection_event where tenant_id=? and delivery_id=?",
                Integer.class, tenant, a.id()));
        journal.markCompleted(batchEntry("batch-new").result());
        assertEquals(List.of(DetectionEventClaim.COMPLETED, DetectionEventClaim.NEW), journal.preclaimBatch(List.of(first, second)));
        var other = new SecurityEvent(a.id(), a.timestamp(), a.source(), a.host(), a.raw(),
                Map.of("tenant_id", tenant + "-b"), a.severity());
        try {
            var otherInput = new DetectionStateStore.Input(other, "socp-events", 0, 1002L, "key");
            assertEquals(List.of(DetectionEventClaim.NEW), journal.preclaimBatch(List.of(otherInput)));
            assertEquals(List.of(DetectionEventClaim.PENDING), journal.preclaimBatch(List.of(otherInput)));
            var ownRows = repository.findByTenantIdAndDeliveryIdIn(tenant, List.of(a.id(), b.id(), "absent"));
            assertEquals(Map.of(a.id(), "COMPLETED", b.id(), "PENDING"), ownRows.stream().collect(
                    java.util.stream.Collectors.toMap(DetectionEventEntity::getDeliveryId, DetectionEventEntity::getStatus)));
            assertTrue(ownRows.stream().allMatch(row -> tenant.equals(row.getTenantId())));
            try (var ignored = TenantContext.open(other.tenantId())) {
                var otherRows = repository.findByTenantIdAndDeliveryIdIn(other.tenantId(), List.of(a.id(), b.id(), "absent"));
                assertEquals(1, otherRows.size());
                assertEquals(other.tenantId(), otherRows.getFirst().getTenantId());
                assertEquals(a.id(), otherRows.getFirst().getDeliveryId());
                assertEquals("PENDING", otherRows.getFirst().getStatus());
            }
        } finally { jdbc.update("delete from t_detection_event where tenant_id=?", other.tenantId()); }
        var rolledBack = batchEntry("batch-rollback").result().event();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            journal.preclaimBatch(List.of(new DetectionStateStore.Input(rolledBack, "socp-events", 0, 1003L, "key")));
            throw new IllegalStateException("rollback admission");
        }));
        assertTrue(repository.findByTenantIdAndDeliveryId(tenant, rolledBack.id()).isEmpty());
    }

    @Test void atomicBatchRollsBackEarlierCompletionsWhenALaterWriteFails() {
        transaction.executeWithoutResult(ignored -> {
            repository.save(row("batch-a", 1000));
            repository.save(row("batch-b", 1001));
            jdbc.execute("alter table t_detection_event add constraint batch_failure_probe "
                    + "check (delivery_id <> 'batch-b' or status <> 'COMPLETED')");
        });
        var event = batchEntry("batch-a").result().event();
        var alert = new com.socp.rule.model.Alert("rule", "Rule", com.socp.rule.model.Severity.LOW,
                "alert", "host", List.of(event));
        var alertResult = new com.socp.rule.engine.DetectionResult(event, null, Map.of(), List.of(),
                List.of(alert), List.of(alert), null, null);
        var batch = List.of(new com.socp.rule.engine.BatchEventAlertSink.Entry(alertResult, null), batchEntry("batch-b"));
        try {
            assertThrows(RuntimeException.class, () -> forwarder.forwardBatch(batch));
            assertEquals(0, jdbc.queryForObject("select count(*) from t_detection_alert_outbox where tenant_id=?", Integer.class, tenant));
            assertEquals("PENDING", repository.findByTenantIdAndDeliveryId(tenant, "batch-a").orElseThrow().getStatus());
            assertEquals("PENDING", repository.findByTenantIdAndDeliveryId(tenant, "batch-b").orElseThrow().getStatus());
        } finally {
            jdbc.execute("alter table t_detection_event drop constraint batch_failure_probe");
        }
        forwarder.forwardBatch(batch);
        assertEquals(1, jdbc.queryForObject("select count(*) from t_detection_alert_outbox where tenant_id=?", Integer.class, tenant));
        for (String id : List.of("batch-a", "batch-b")) {
            var row = repository.findByTenantIdAndDeliveryId(tenant, id).orElseThrow();
            assertEquals("COMPLETED", row.getStatus());
            assertTrue(row.getResultJson().contains(id));
        }
    }

    @Test void fenceCacheIsTransactionLocalAndSuspendedForRequiresNew() {
        var lease = ownership.acquire("test-" + tenant, 0, 0);
        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // A wrong epoch must still be checked when the outer transaction has cached another token.
        transaction.executeWithoutResult(ignored -> {
            ownership.assertCurrent(lease);
            nested.executeWithoutResult(inner -> assertThrows(
                    DetectionStateOwnership.StaleStateOwnerException.class,
                    () -> ownership.assertCurrent(new DetectionStateOwnership.Lease(
                            lease.inputTopic(), 1, 0, lease.ownerId(), lease.fencingEpoch(), lease.leaseUntil()))));
            ownership.assertCurrent(lease);
        });
        ownership.release(lease);
        assertThrows(DetectionStateOwnership.StaleStateOwnerException.class,
                () -> transaction.executeWithoutResult(ignored -> ownership.assertCurrent(lease)));
    }

    @Test void sixOwnerBatchUsesTwoStatementsAndRetainsLocksUntilTransactionCompletion() {
        var leases = leases(6);
        var statistics = entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            transaction.executeWithoutResult(ignored -> {
                ownership.assertCurrentBatch(leases);
                assertEquals(2, statistics.getPrepareStatementCount(), "one fresh lock query and one bulk renewal");
                ownership.assertCurrentBatch(leases.reversed());
                ownership.assertCurrent(leases.getFirst());
                assertEquals(2, statistics.getPrepareStatementCount(), "same-transaction tokens may reuse held locks");
            });
            transaction.executeWithoutResult(ignored -> ownership.assertCurrentBatch(leases));
            assertEquals(4, statistics.getPrepareStatementCount(), "new transaction must reacquire authoritative locks");
        } finally { statistics.setStatisticsEnabled(enabled); }
    }

    @Test void batchFenceRejectsStaleManagedOwnerStateAndRollsBackAllRenewals() {
        var leases = leases(3);
        var first = leases.getFirst();
        assertThrows(DetectionStateOwnership.StaleStateOwnerException.class, () -> transaction.executeWithoutResult(ignored -> {
            var managed = owners.findById(first.unitKey()).orElseThrow();
            jdbc.update("update t_detection_state_owner set fencing_epoch=fencing_epoch+1 where owner_key=?", first.unitKey());
            assertEquals(first.fencingEpoch(), managed.getFencingEpoch(), "managed entity is deliberately stale");
            ownership.assertCurrentBatch(leases);
        }));
        assertEquals(first.fencingEpoch(), owners.findById(first.unitKey()).orElseThrow().getFencingEpoch());

        var before = jdbc.queryForList("select owner_key, lease_until, updated_at from t_detection_state_owner where input_topic=? order by owner_key", first.inputTopic());
        transaction.executeWithoutResult(ignored -> repository.saveAndFlush(row("fence-rollback", 1000)));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(ignored -> {
            ownership.assertCurrentBatch(leases);
            journal.markCompleted(batchEntry("fence-rollback").result());
            throw new IllegalStateException("later durable write rejected");
        }));
        assertEquals(before, jdbc.queryForList("select owner_key, lease_until, updated_at from t_detection_state_owner where input_topic=? order by owner_key", first.inputTopic()));
        assertEquals("PENDING", repository.findByTenantIdAndDeliveryId(tenant, "fence-rollback").orElseThrow().getStatus());
        transaction.executeWithoutResult(ignored -> ownership.assertCurrentBatch(leases));
    }

    private List<DetectionStateOwnership.Lease> leases(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(partition -> ownership.acquire("test-" + tenant, partition, 0)).toList();
    }

    private com.socp.rule.engine.BatchEventAlertSink.Entry batchEntry(String id) {
        var event = new SecurityEvent(id, Instant.now(), "test", "host", "raw",
                Map.of("tenant_id", tenant), com.socp.rule.model.Severity.INFO);
        var result = new com.socp.rule.engine.DetectionResult(event, null, Map.of(), List.of(),
                List.of(), List.of(), null, null);
        return new com.socp.rule.engine.BatchEventAlertSink.Entry(result, null);
    }

    private void assertBounded(Consumer<Consumer<List<SecurityEvent>>> replay, int expected) {
        transaction.executeWithoutResult(ignored -> {
            // A fix must not clear/detach unrelated managed caller writes.
            DetectionEventEntity marker = repository.saveAndFlush(row("caller-" + UUID.randomUUID(), 5000));
            marker.setStatusReason("caller write must survive replay");
            List<String> ids = new ArrayList<>();
            replay.accept(batch -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                        "Replay must suspend the enclosing rule-reload transaction");
                assertTrue(batch.size() <= 100);
                assertTrue(entityManager.unwrap(Session.class).getStatistics().getEntityCount() <= 100,
                        "Completed replay may not retain entities from earlier pages");
                ids.addAll(batch.stream().map(SecurityEvent::id).toList());
            });
            assertEquals(expected, ids.size());
            assertEquals(expected, ids.stream().distinct().count());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(entityManager.contains(marker));
            assertEquals(1, entityManager.unwrap(Session.class).getStatistics().getEntityCount(),
                    "Only the caller's own entity may remain managed after replay");
            repository.flush();
            entityManager.refresh(marker);
            assertEquals("caller write must survive replay", marker.getStatusReason());
        });
    }

    private DetectionEventEntity row(String id, long offset) {
        return new DetectionEventEntity(tenant, id, id, "legacy-v1", "auth", "host", "raw", "{}", "INFO",
                since.plusSeconds(1), "socp-events", 0, offset, "socp-events", 0, offset,
                tenant + "|host|host");
    }
}
