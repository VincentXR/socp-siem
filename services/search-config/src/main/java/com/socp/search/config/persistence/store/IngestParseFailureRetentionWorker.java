package com.socp.search.config.persistence.store;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.persistence.repository.IngestParseFailureRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Bounded retention for raw parse failures; runs with the explicit system scope. */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class IngestParseFailureRetentionWorker {
    private final IngestParseFailureRepository repository;
    private final long retentionMs;
    private final int batchSize;

    public IngestParseFailureRetentionWorker(IngestParseFailureRepository repository,
                                             IngestRuntimeProperties properties) {
        this.repository = repository;
        this.retentionMs = Math.max(86_400_000L, properties.getQuarantine().getRetentionMs());
        this.batchSize = Math.max(1, Math.min(10_000,
                properties.getQuarantine().getCleanupBatchSize()));
    }

    @Scheduled(
            fixedDelayString = "${socp.ingest.quarantine.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.ingest.quarantine.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanup() {
        repository.deleteRetainedBatchBefore(Instant.now().minusMillis(retentionMs), batchSize);
    }
}
