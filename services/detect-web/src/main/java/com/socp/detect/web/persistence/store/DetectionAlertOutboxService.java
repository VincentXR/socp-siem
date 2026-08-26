package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import com.socp.detect.web.engine.DetectionAlertOutboxPublisher;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/** Enqueues a fully materialized alert before the detection event is acknowledged. */
@Service
public class DetectionAlertOutboxService {

    private final DetectionAlertOutboxRepository repository;
    private final DetectionAlertOutboxPublisher publisher;

    @Autowired
    public DetectionAlertOutboxService(DetectionAlertOutboxRepository repository,
                                       DetectionAlertOutboxPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    public DetectionAlertOutboxService(DetectionAlertOutboxRepository repository) {
        this(repository, null);
    }

    /**
     * Persist an alert payload before returning to the rule-engine worker.
     * Duplicate alert IDs are expected during replay and are treated as
     * successful idempotent enqueue operations.
     */
    @Transactional
    public void enqueue(String alertId, String tenantId, String payload) {
        if (alertId == null || alertId.isBlank() || payload == null || payload.isBlank()
                || !TenantContext.isValid(tenantId)) {
            throw new IllegalArgumentException("alertId, tenantId and payload are required");
        }
        if (repository.existsById(alertId)) return;
        Instant now = Instant.now();
        try {
            repository.saveAndFlush(new DetectionAlertOutboxEntity(
                    alertId, tenantId, payload, now));
            scheduleOutboxTrigger();
        } catch (DataIntegrityViolationException duplicate) {
            // The primary key is the final arbiter when two workers replay the
            // same deterministic alert concurrently.
        }
    }

    private void scheduleOutboxTrigger() {
        if (publisher == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.triggerAsync();
                }
            });
        } else {
            publisher.triggerAsync();
        }
    }
}
