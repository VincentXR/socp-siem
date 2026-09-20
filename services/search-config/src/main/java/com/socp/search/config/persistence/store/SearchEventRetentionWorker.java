package com.socp.search.config.persistence.store;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Bounded retention for the PostgreSQL fallback/idempotency event copy.
 *
 * <p>OpenSearch remains the search projection. The database copy is retained
 * long enough for ingest replay/idempotency and local fallback, but it must not
 * grow forever. Rows with unresolved ingestion outbox work are deliberately
 * excluded by the repository delete predicate.</p>
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class SearchEventRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(SearchEventRetentionWorker.class);

    private final SearchEventRepository repository;
    private final long retentionMs;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatches;
    private final MeterRegistry metrics;

    @Autowired
    public SearchEventRetentionWorker(
            SearchEventRepository repository,
            @Value("${socp.search.event-retention.retention-ms:2592000000}") long retentionMs,
            @Value("${socp.search.event-retention.cleanup-batch-size:1000}") int cleanupBatchSize,
            @Value("${socp.search.event-retention.cleanup-max-batches:10}") int cleanupMaxBatches,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this(repository, retentionMs, cleanupBatchSize, cleanupMaxBatches,
                meterRegistry.getIfAvailable());
    }

    SearchEventRetentionWorker(SearchEventRepository repository, long retentionMs,
                               int cleanupBatchSize, int cleanupMaxBatches,
                               MeterRegistry metrics) {
        this.repository = repository;
        this.retentionMs = Math.max(86_400_000L, retentionMs);
        this.cleanupBatchSize = Math.max(1, Math.min(10_000, cleanupBatchSize));
        this.cleanupMaxBatches = Math.max(1, Math.min(100, cleanupMaxBatches));
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${socp.search.event-retention.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.search.event-retention.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanupExpiredEvents() {
        Instant cutoff = Instant.now().minusMillis(retentionMs);
        int removedTotal = 0;
        try {
            for (int batch = 0; batch < cleanupMaxBatches; batch++) {
                int removed = repository.deleteRetainedBatchBefore(cutoff, cleanupBatchSize);
                removedTotal += removed;
                if (removed < cleanupBatchSize) break;
            }
            if (removedTotal > 0) {
                log.info("Removed retained search event rows count={} cutoff={} batchSize={} maxBatches={}",
                        removedTotal, cutoff, cleanupBatchSize, cleanupMaxBatches);
                if (metrics != null) {
                    metrics.counter("socp.search.event.retention", "outcome", "deleted")
                            .increment(removedTotal);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Search event retention cleanup deferred: {}", failure.getMessage());
            if (metrics != null) {
                metrics.counter("socp.search.event.retention", "outcome", "failure").increment();
            }
        }
    }
}
