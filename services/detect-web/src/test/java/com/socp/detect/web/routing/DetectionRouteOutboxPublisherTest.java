package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionRouteOutboxPublisherTest {
    @Autowired DetectionRouteOutboxRepository repository;

    @Test
    void oneRecoveryPassIsBoundedUnderABacklog() {
        var rows = java.util.stream.IntStream.range(0, 101).mapToObj(ignored -> row(1)).toList();
        repository.saveAllAndFlush(rows);
        publisher(0).recoverStale();
        long remaining = rows.stream().map(this::find).filter(row -> "PROCESSING".equals(row.getStatus())).count();
        assertEquals(1, remaining);
        publisher(0).recoverStale();
        assertTrue(rows.stream().map(this::find).allMatch(row -> "PENDING".equals(row.getStatus())));
    }

    @Test
    void recoveryMakesAnExhaustedAttemptTerminalAndCannotOverwriteANewAttempt() {
        var exhausted = row(3);
        repository.saveAndFlush(exhausted);
        publisher(3).recoverStale();
        assertEquals("DEAD", find(exhausted).getStatus());

        var retry = row(1);
        repository.saveAndFlush(retry);
        var publisher = publisher(0);
        publisher.recoverStale();
        assertEquals("PENDING", find(retry).getStatus());
        assertEquals(1, repository.claim(retry.getDeliveryId(), find(retry).getNextAttemptAt(), 1, Integer.MAX_VALUE));
        publisher.markPublished(retry, 0, 42);
        publisher.markFailed(retry, new IllegalStateException("stale"));
        var current = find(retry);
        assertEquals("PROCESSING", current.getStatus());
        assertEquals(2, current.getAttempts());
        assertEquals(0, repository.markFailed(current.getDeliveryId(), 1, "DEAD", Instant.now(), "stale", Instant.now()));
        publisher.markPublished(current, 0, 43);
        assertEquals("PUBLISHED", find(current).getStatus());
    }

    private DetectionRouteOutboxEntity find(DetectionRouteOutboxEntity row) {
        return repository.findByTenantIdAndDeliveryId(row.getTenantId(), row.getDeliveryId()).orElseThrow();
    }

    private DetectionRouteOutboxPublisher publisher(int limit) {
        return new DetectionRouteOutboxPublisher(repository, "localhost:9092", false, limit, "7d", 100, 1);
    }

    private static DetectionRouteOutboxEntity row(int attempts) {
        String id = UUID.randomUUID().toString();
        var row = new DetectionRouteOutboxEntity(id, "audit-publisher", id,
                "detection-routing-v2", "plan", "STATELESS", "_stateless", "_once", "key",
                "socp-events", 0, 1L, "socp-detection-routed-v2", "{}", Instant.now().minusSeconds(300));
        row.setAttempts(attempts); row.setStatus("PROCESSING");
        return row;
    }
}
