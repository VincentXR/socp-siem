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
@Import(DetectionEventJournal.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionJournalReplayTransactionTest {
    @Autowired DetectionEventJournal journal;
    @Autowired DetectionEventRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
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
            jdbc.update("delete from t_detection_event where tenant_id = ?", tenant);
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
