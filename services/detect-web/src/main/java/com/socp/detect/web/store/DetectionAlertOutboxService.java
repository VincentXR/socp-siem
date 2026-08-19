package com.socp.detect.web.store;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Enqueues a fully materialized alert before the detection event is acknowledged. */
@Service
public class DetectionAlertOutboxService {

    private final DetectionAlertOutboxRepository repository;

    public DetectionAlertOutboxService(DetectionAlertOutboxRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist an alert payload before returning to the rule-engine worker.
     * Duplicate alert IDs are expected during replay and are treated as
     * successful idempotent enqueue operations.
     */
    @Transactional
    public void enqueue(String alertId, String tenantId, String payload) {
        if (alertId == null || alertId.isBlank() || payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("alertId and payload are required");
        }
        if (repository.existsById(alertId)) return;
        Instant now = Instant.now();
        try {
            repository.saveAndFlush(new DetectionAlertOutboxEntity(
                    alertId, tenantId == null || tenantId.isBlank() ? "default" : tenantId,
                    payload, now));
        } catch (DataIntegrityViolationException duplicate) {
            // The primary key is the final arbiter when two workers replay the
            // same deterministic alert concurrently.
        }
    }
}
