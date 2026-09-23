package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionAlertOutboxPersistenceTest {
    protected static final String TENANT = "alert-outbox-test";
    @Autowired protected DetectionAlertOutboxRepository repository;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void openTenant() { TenantContext.set(TENANT); }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("delete from t_detection_alert_outbox where tenant_id = ?", TENANT);
        TenantContext.clear();
    }

    @Test
    void lateFailureCannotUndoNewerPublication() {
        var old = processing(1, false);
        recover();
        var current = claim(load(old));
        Instant now = Instant.now();
        assertEquals(1, repository.markPublished(current.getAlertId(), current.getClaimToken(), now, now));

        assertEquals(0, fail(old, "DEAD"));

        var stored = load(old);
        assertEquals("PUBLISHED", stored.getStatus());
        assertEquals(2, stored.getAttempts());
        assertNotNull(stored.getPublishedAt());
        assertNull(stored.getClaimToken());
        assertNull(stored.getLastError());
    }

    @Test
    void lateAcknowledgementCannotCompleteAnotherOwnersClaim() {
        var old = processing(1, false);
        recover();
        var current = claim(load(old));

        assertEquals(0, repository.markPublished(old.getAlertId(), old.getClaimToken(), Instant.now(), Instant.now()));

        assertEquals("PROCESSING", load(old).getStatus());
        assertEquals(current.getClaimToken(), load(old).getClaimToken());
        assertNull(load(old).getDeliveredAt());
    }

    @Test
    void manualRequeueDoesNotReuseOwnershipEvenWhenAttemptNumberRepeats() {
        var old = processing(1, false);
        Instant now = Instant.now();
        assertEquals(1, repository.recoverStaleBatch(now.minusSeconds(120), now, 1, 100));
        assertEquals("DEAD", load(old).getStatus());
        assertEquals(1, repository.requeueDead(old.getAlertId(), TENANT, now));
        var current = claim(load(old));
        assertEquals(old.getAttempts(), current.getAttempts());
        assertNotEquals(old.getClaimToken(), current.getClaimToken());

        assertEquals(0, fail(old, "DEAD"));
        assertEquals(0, repository.markPublished(old.getAlertId(), old.getClaimToken(), now, now));
        assertEquals("PROCESSING", load(old).getStatus());
        assertEquals(current.getClaimToken(), load(old).getClaimToken());
        assertEquals(1, repository.markPublished(current.getAlertId(), current.getClaimToken(), now, now));
    }

    @Test
    void recoveryPreservesTheAcknowledgedStageAndExpiresLegacyClaims() {
        var pending = processing(1, false);
        var delivered = processing(1, true);
        var legacy = processing(1, false);
        jdbc.update("update t_detection_alert_outbox set claim_token = null where alert_id = ?", legacy.getAlertId());
        var active = processing(1, false);
        jdbc.update("update t_detection_alert_outbox set updated_at = ? where alert_id = ?",
                java.sql.Timestamp.from(Instant.now()), active.getAlertId());

        recover();

        assertEquals("PENDING", load(pending).getStatus());
        assertEquals("DELIVERED", load(delivered).getStatus());
        assertNotNull(load(delivered).getDeliveredAt());
        assertEquals("PENDING", load(legacy).getStatus());
        assertNull(load(legacy).getClaimToken());
        assertEquals("PROCESSING", load(active).getStatus());
        assertEquals(active.getClaimToken(), load(active).getClaimToken());
    }

    @Test
    void crashAtRetryLimitBecomesDeadAndRequeuePreservesTheSecondStage() {
        var pending = processing(2, false);
        var delivered = processing(2, true);

        recover();

        assertEquals("DEAD", load(pending).getStatus());
        assertEquals("DEAD", load(delivered).getStatus());
        assertEquals(1, repository.requeueDead(delivered.getAlertId(), TENANT, Instant.now()));
        assertEquals("DELIVERED", load(delivered).getStatus());
        assertEquals(0, load(delivered).getAttempts());
        assertNull(load(delivered).getClaimToken());
    }

    @Test
    void failedSecondStagePersistsAcknowledgedHttpDeliveryAndBackoff() {
        var row = processing(1, false);
        Instant now = Instant.now();
        assertEquals(1, repository.markFailed(row.getAlertId(), row.getClaimToken(), "DELIVERED", now,
                now.plusSeconds(60), "broker unavailable", now));

        var stored = load(row);
        assertEquals("DELIVERED", stored.getStatus());
        assertNotNull(stored.getDeliveredAt());
        assertNull(stored.getClaimToken());
        assertEquals(0, repository.claim(row.getAlertId(), "DELIVERED", 1, token(), now, 2));
        assertEquals(1, repository.claim(row.getAlertId(), "DELIVERED", 1, token(), now.plusSeconds(61), 2));
    }

    @Test
    void staleScanCannotUndercountAttempts() {
        var old = processing(1, false);
        recover();

        assertEquals(0, repository.claim(old.getAlertId(), "PENDING", 0, token(), Instant.now(), 2));
        assertEquals(1, repository.claim(old.getAlertId(), "PENDING", 1, token(), Instant.now(), 2));
        assertEquals(2, load(old).getAttempts());
    }

    @Test
    void recoveryDrainsAtMostOneHundredRowsPerBatch() {
        for (int i = 0; i < 101; i++) processing(1, false);

        recover();

        assertEquals(100, repository.countByStatus("PENDING"));
        assertEquals(1, repository.countByStatus("PROCESSING"));
        recover();
        assertEquals(101, repository.countByStatus("PENDING"));
    }

    @Test
    void exhaustedRowsRetireInBoundedBatchesWithoutChangingActiveClaims() {
        for (int i = 0; i < 101; i++) {
            var row = processing(2, i % 2 == 0);
            jdbc.update("update t_detection_alert_outbox set status = ? where alert_id = ?",
                    row.alertDelivered() ? "DELIVERED" : "PENDING", row.getAlertId());
        }
        var active = processing(2, false);
        assertEquals(100, repository.markExhaustedBatch(2, Instant.now(), 100));
        assertEquals(100, repository.countByStatus("DEAD"));
        assertEquals("PROCESSING", load(active).getStatus());
        assertEquals(1, repository.markExhaustedBatch(2, Instant.now(), 100));
        assertEquals(101, repository.countByStatus("DEAD"));
    }

    protected DetectionAlertOutboxEntity processing(int attempts, boolean delivered) {
        Instant old = Instant.now().minusSeconds(300);
        var row = new DetectionAlertOutboxEntity(UUID.randomUUID().toString(), TENANT, "{}", old);
        row.setStatus("PROCESSING");
        row.setAttempts(attempts);
        row.setClaimToken(token());
        if (delivered) row.setDeliveredAt(old);
        return repository.saveAndFlush(row);
    }

    protected DetectionAlertOutboxEntity load(DetectionAlertOutboxEntity row) {
        return repository.findByAlertIdAndTenantId(row.getAlertId(), TENANT).orElseThrow();
    }

    protected DetectionAlertOutboxEntity claim(DetectionAlertOutboxEntity row) {
        // Database timestamp precision can round recovery's clock value slightly into
        // the future. Exercise the persisted deadline rather than racing the wall clock.
        Instant due = row.getNextAttemptAt();
        assertEquals(0, repository.claim(row.getAlertId(), row.getStatus(), row.getAttempts(), token(), due.minusSeconds(1), 2));
        assertEquals(1, repository.claim(row.getAlertId(), row.getStatus(), row.getAttempts(), token(), due, 2));
        return load(row);
    }

    protected void recover() {
        repository.recoverStaleBatch(Instant.now().minusSeconds(120), Instant.now(), 2, 100);
    }

    private int fail(DetectionAlertOutboxEntity row, String status) {
        return repository.markFailed(row.getAlertId(), row.getClaimToken(), status, row.getDeliveredAt(),
                Instant.now(), "late failure", Instant.now());
    }

    protected static String token() { return UUID.randomUUID().toString(); }
}
