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
