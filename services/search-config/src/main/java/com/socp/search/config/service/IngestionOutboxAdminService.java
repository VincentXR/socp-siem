package com.socp.search.config.service;

import com.socp.platform.data.outbox.DeadOutboxRecord;
import com.socp.platform.data.outbox.OutboxAdminResult;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Tenant-scoped, auditable operational closure for ingestion outbox failures. */
@Service
public class IngestionOutboxAdminService {

    private final IngestionOutboxRepository repository;
    private final MeterRegistry meterRegistry;

    public IngestionOutboxAdminService(IngestionOutboxRepository repository,
                                       MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(readOnly = true)
    public List<DeadOutboxRecord> dead() {
        String tenant = TenantContext.require();
        return repository.findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(tenant, "DEAD")
                .stream().map(this::view).toList();
    }

    @Transactional
    public OutboxAdminResult requeue(String id) {
        String tenant = TenantContext.require();
        repository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Ingestion outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (repository.requeueDead(id, tenant, now) != 1) {
            throw ApiException.badRequest("Only DEAD ingestion outbox rows can be requeued");
        }
        lifecycle("requeued");
        return new OutboxAdminResult(id, "ingestion", "PENDING", now);
    }

    @Transactional
    public OutboxAdminResult discard(String id, String reason) {
        String tenant = TenantContext.require();
        IngestionOutboxEvent event = repository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Ingestion outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (repository.discardDead(id, tenant,
                discardReason(reason, event.getLastError()), now) != 1) {
            throw ApiException.badRequest("Only DEAD ingestion outbox rows can be discarded");
        }
        lifecycle("discarded");
        return new OutboxAdminResult(id, "ingestion", "DISCARDED", now);
    }

    private DeadOutboxRecord view(IngestionOutboxEvent event) {
        return new DeadOutboxRecord(event.getId(), "ingestion", event.getEventId(),
                event.getAttempts(), event.getCreatedAt(), event.getUpdatedAt(), event.getLastError());
    }

    private void lifecycle(String outcome) {
        meterRegistry.counter("socp.ingestion.outbox.lifecycle", "outcome", outcome).increment();
    }

    private static String discardReason(String reason, String previousFailure) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A discard reason is required");
        }
        String value = "operator discard: " + reason.trim();
        if (previousFailure != null && !previousFailure.isBlank()) {
            value += " | previous failure: " + previousFailure.trim();
        }
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
