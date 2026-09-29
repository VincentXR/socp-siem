package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionRouteOutboxPublisherPersistenceTest {
    @Autowired protected DetectionRouteOutboxRepository repository;
    @Autowired protected JdbcTemplate jdbc;
    protected DetectionRouteOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        TenantContext.set("route-test");
        publisher = new DetectionRouteOutboxPublisher(repository, "localhost:9092", true, 2, "7d", 100, 1);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from t_detection_route_outbox where tenant_id = ?", "route-test");
        publisher.close();
        TenantContext.clear();
    }

    @Test
    void crashDuringLastAttemptBecomesDeadInsteadOfUnclaimablePending() {
        DetectionRouteOutboxEntity row = processing(2);

        publisher.recoverStale();

        DetectionRouteOutboxEntity recovered = load(row);
        assertEquals("DEAD", recovered.getStatus());
        assertEquals(2, recovered.getAttempts());
    }

    @Test
    void lateFailureCannotUndoNewerBrokerAcknowledgement() {
        DetectionRouteOutboxEntity stale = processing(1);
        publisher.recoverStale();
        DetectionRouteOutboxEntity recovered = load(stale);
        assertEquals("PENDING", recovered.getStatus());
        // SQL timestamps round to microseconds. Use the persisted due instant so
        // a sub-microsecond clock tick cannot make this fencing fixture not yet due.
        assertEquals(1, repository.claim(stale.getDeliveryId(), recovered.getNextAttemptAt(), 1, 2));
        DetectionRouteOutboxEntity current = load(stale);
        publisher.markPublished(current, 3, 42);

        publisher.markFailed(stale, new IllegalStateException("late failure"));

        DetectionRouteOutboxEntity stored = load(stale);
        assertEquals("PUBLISHED", stored.getStatus());
        assertEquals(2, stored.getAttempts());
        assertEquals(3, stored.getDeliveryPartition());
        assertEquals(42L, stored.getDeliveryOffset());
    }

    @Test
    void lateAcknowledgementCannotFinalizeAnotherPublishersClaim() {
        DetectionRouteOutboxEntity stale = processing(1);
        publisher.recoverStale();
        DetectionRouteOutboxEntity recovered = load(stale);
        assertEquals("PENDING", recovered.getStatus());
        // SQL timestamps round to microseconds. Use the persisted due instant so
        // a sub-microsecond clock tick cannot make this fencing fixture not yet due.
        assertEquals(1, repository.claim(stale.getDeliveryId(), recovered.getNextAttemptAt(), 1, 2));

        publisher.markPublished(stale, 1, 10);

        assertEquals("PROCESSING", load(stale).getStatus());
        assertEquals(2, load(stale).getAttempts());
    }

    @Test
    void stalePendingSnapshotCannotClaimAfterAnotherAttempt() {
        DetectionRouteOutboxEntity row = processing(1);
        publisher.markFailed(row, new IllegalStateException("unavailable"));
        Instant due = Instant.now().plusSeconds(1);

        assertEquals(0, repository.claim(row.getDeliveryId(), due, 0, 2));
        assertEquals(1, repository.claim(row.getDeliveryId(), due, 1, 2));
        assertEquals(2, load(row).getAttempts());
    }

    @Test
    void oldExhaustedPendingRowsBecomeDeadAndActiveClaimsRemainUntouched() {
        DetectionRouteOutboxEntity exhausted = processing(2);
        exhausted.setStatus("PENDING");
        repository.saveAndFlush(exhausted);
        DetectionRouteOutboxEntity active = processing(1);
        active.setUpdatedAt(Instant.now());
        repository.saveAndFlush(active);

        publisher.recoverStale();

        assertEquals("DEAD", load(exhausted).getStatus());
        assertEquals("PROCESSING", load(active).getStatus());
    }

    @Test
    void unlimitedRetryPolicyRecoversWithoutExhaustingDelivery() {
        publisher.close();
        publisher = new DetectionRouteOutboxPublisher(repository, "localhost:9092", true, 0, "7d", 100, 1);
        DetectionRouteOutboxEntity row = processing(100);

        publisher.recoverStale();

        assertEquals("PENDING", load(row).getStatus());
        assertEquals(100, load(row).getAttempts());
    }

    @Test
    void olderUnresolvedRouteFencesTheSameKeyButNotOtherKeys() {
        DetectionRouteOutboxEntity first = pending("same-key");
        DetectionRouteOutboxEntity second = pending("same-key");
        DetectionRouteOutboxEntity other = pending("other-key");

        var heads = repository.findDueKeyHeads(Instant.now().plusSeconds(1)).stream()
                .filter(row -> "route-test".equals(row.getTenantId()))
                .toList();
        assertEquals(2, heads.size());
        assertTrue(heads.stream().anyMatch(row -> row.getDeliveryId().equals(first.getDeliveryId())));
        assertTrue(heads.stream().anyMatch(row -> row.getDeliveryId().equals(other.getDeliveryId())));
        assertEquals(0, repository.claim(second.getDeliveryId(), Instant.now().plusSeconds(1), 0, 2));

        assertEquals(1, repository.claim(first.getDeliveryId(), Instant.now().plusSeconds(1), 0, 2));
        DetectionRouteOutboxEntity claimed = load(first);
        publisher.markFailed(claimed, new IllegalStateException("retry"));
        assertEquals(0, repository.claim(second.getDeliveryId(), Instant.now().plusSeconds(1), 0, 2));
        DetectionRouteOutboxEntity retry = load(first);
        assertEquals(1, repository.claim(first.getDeliveryId(), retry.getNextAttemptAt(), 1, 2));
        claimed = load(first);
        publisher.markFailed(claimed, new IllegalStateException("exhausted"));
        assertEquals("DEAD", load(first).getStatus());
        assertEquals(1, repository.claim(second.getDeliveryId(), Instant.now().plusSeconds(60), 0, 2));
    }

    @Test
    void anInFlightKeyFencesAnOlderSequenceThatBecomesVisibleLater() {
        DetectionRouteOutboxEntity inFlight = pending("commit-race");
        long inFlightSequence = jdbc.queryForObject(
                "select sequence_no from t_detection_route_outbox where delivery_id = ?",
                Long.class, inFlight.getDeliveryId());
        Instant due = Instant.now().plusSeconds(1);
        assertEquals(1, repository.claim(inFlight.getDeliveryId(), due, 0, 2));

        DetectionRouteOutboxEntity newlyVisibleOlder = pending("commit-race");
        jdbc.update("update t_detection_route_outbox set sequence_no = ? where delivery_id = ?",
                inFlightSequence - 1, newlyVisibleOlder.getDeliveryId());

        assertEquals(0, repository.claim(newlyVisibleOlder.getDeliveryId(), due, 0, 2));
        assertTrue(repository.findDueKeyHeads(due).stream()
                .noneMatch(row -> newlyVisibleOlder.getDeliveryId().equals(row.getDeliveryId())));
        publisher.markPublished(load(inFlight), 0, 1);
        assertEquals(1, repository.claim(newlyVisibleOlder.getDeliveryId(), due, 0, 2));
    }

    @Test
    void theDatabaseLeaseAllowsOnlyOneProcessingRowPerTenantAndKey() {
        DetectionRouteOutboxEntity first = pending("leased");
        DetectionRouteOutboxEntity second = pending("leased");
        Instant due = Instant.now().plusSeconds(1);
        assertEquals(1, repository.claim(first.getDeliveryId(), due, 0, 2));
        String lease = jdbc.queryForObject(
                "select processing_key from t_detection_route_outbox where delivery_id = ?",
                String.class, first.getDeliveryId());
        assertEquals("route-test:leased", lease);

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update t_detection_route_outbox "
                                + "set status = 'PROCESSING', processing_key = ? where delivery_id = ?",
                        lease, second.getDeliveryId()));
        publisher.markPublished(load(first), 0, 1);
        assertNull(jdbc.queryForObject(
                "select processing_key from t_detection_route_outbox where delivery_id = ?",
                String.class, first.getDeliveryId()));
    }

    private DetectionRouteOutboxEntity pending(String routingKey) {
        Instant now = Instant.now();
        return repository.saveAndFlush(new DetectionRouteOutboxEntity(
                UUID.randomUUID().toString(), "route-test", UUID.randomUUID().toString(), "v2", "plan-1",
                "STATELESS", "event", "event-1", routingKey, "socp-events", 0, 1,
                "socp-detection-routed-v2", "{}", now));
    }

    protected DetectionRouteOutboxEntity processing(int attempts) {
        Instant old = Instant.now().minusSeconds(180);
        DetectionRouteOutboxEntity row = new DetectionRouteOutboxEntity(
                UUID.randomUUID().toString(), "route-test", "event-1", "v2", "plan-1",
                "STATELESS", "event", "event-1", "route-key", "socp-events", 0, 1,
                "socp-detection-routed-v2", "{}", old);
        row.setStatus("PROCESSING");
        row.setAttempts(attempts);
        return repository.saveAndFlush(row);
    }

    protected DetectionRouteOutboxEntity load(DetectionRouteOutboxEntity row) {
        return repository.findByTenantIdAndDeliveryId("route-test", row.getDeliveryId()).orElseThrow();
    }
}
