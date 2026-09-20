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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded retention for the PostgreSQL fallback/idempotency event copy.
 *
 * <p>OpenSearch remains the search projection. The database copy is retained
 * long enough for ingest replay/idempotency and local fallback. Each delete is
 * a small SKIP LOCKED transaction, while one scheduled invocation may perform
 * several bounded rounds to catch up a historical backlog without waiting an
 * hour between every 10k rows.</p>
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class SearchEventRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(SearchEventRetentionWorker.class);

    private final SearchEventRepository repository;
    private final long retentionMs;
    private final int cleanupBatchSize;
    /** Per catch-up round, not per entire scheduled invocation. */
    private final int cleanupMaxBatches;
    private final long cleanupMaxRunMs;
    private final long cleanupCatchupPauseMs;
    private final MeterRegistry metrics;

    private final AtomicLong oldestEligibleAgeSeconds = new AtomicLong();
    private final AtomicLong retentionLagSeconds = new AtomicLong();
    private final AtomicLong lastCleanupDurationMs = new AtomicLong();
    private final AtomicLong lastDeleteRatePerSecond = new AtomicLong();

    @Autowired
    public SearchEventRetentionWorker(
            SearchEventRepository repository,
            @Value("${socp.search.event-retention.retention-ms:2592000000}") long retentionMs,
            @Value("${socp.search.event-retention.cleanup-batch-size:1000}") int cleanupBatchSize,
            @Value("${socp.search.event-retention.cleanup-max-batches:10}") int cleanupMaxBatches,
            @Value("${socp.search.event-retention.cleanup-max-run-ms:30000}") long cleanupMaxRunMs,
            @Value("${socp.search.event-retention.cleanup-catchup-pause-ms:100}") long cleanupCatchupPauseMs,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this(repository, retentionMs, cleanupBatchSize, cleanupMaxBatches,
                cleanupMaxRunMs, cleanupCatchupPauseMs, meterRegistry.getIfAvailable());
    }

    /** Source-compatible test constructor. */
    SearchEventRetentionWorker(SearchEventRepository repository, long retentionMs,
                               int cleanupBatchSize, int cleanupMaxBatches,
                               MeterRegistry metrics) {
        this(repository, retentionMs, cleanupBatchSize, cleanupMaxBatches,
                30_000L, 0L, metrics);
    }

    SearchEventRetentionWorker(SearchEventRepository repository, long retentionMs,
                               int cleanupBatchSize, int cleanupMaxBatches,
                               long cleanupMaxRunMs, long cleanupCatchupPauseMs,
                               MeterRegistry metrics) {
        this.repository = repository;
        this.retentionMs = Math.max(86_400_000L, retentionMs);
        this.cleanupBatchSize = Math.max(1, Math.min(10_000, cleanupBatchSize));
        this.cleanupMaxBatches = Math.max(1, Math.min(100, cleanupMaxBatches));
        this.cleanupMaxRunMs = Math.max(100L, Math.min(300_000L, cleanupMaxRunMs));
        this.cleanupCatchupPauseMs = Math.max(0L, Math.min(5_000L, cleanupCatchupPauseMs));
        this.metrics = metrics;
        if (metrics != null) {
            metrics.gauge("socp.search.event.retention.oldest.eligible.age.seconds",
                    oldestEligibleAgeSeconds, AtomicLong::get);
            metrics.gauge("socp.search.event.retention.lag.seconds",
                    retentionLagSeconds, AtomicLong::get);
            metrics.gauge("socp.search.event.retention.cleanup.duration.ms",
                    lastCleanupDurationMs, AtomicLong::get);
            metrics.gauge("socp.search.event.retention.delete.rate.rows_per_second",
                    lastDeleteRatePerSecond, AtomicLong::get);
        }
    }

    @Scheduled(
            fixedDelayString = "${socp.search.event-retention.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.search.event-retention.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanupExpiredEvents() {
        Instant now = Instant.now();
        Instant cutoff = now.minusMillis(retentionMs);
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(cleanupMaxRunMs);
        long removedTotal = 0L;
        Optional<Instant> oldestEligible = Optional.empty();

        try {
            boolean backlog;
            do {
                int roundRemoved = 0;
                for (int batch = 0; batch < cleanupMaxBatches; batch++) {
                    if (System.nanoTime() >= deadlineNanos) break;
                    int removed = repository.deleteRetainedBatchBefore(cutoff, cleanupBatchSize);
                    removedTotal += removed;
                    roundRemoved += removed;
                    if (removed < cleanupBatchSize) break;
                }

                // One ordered LIMIT 1 query is enough to distinguish caught-up
                // from lagging; avoid a high-frequency COUNT over the event table.
                oldestEligible = repository.findOldestDeletableCreatedAtBefore(cutoff);
                updateLagMetrics(now, cutoff, oldestEligible);
                backlog = oldestEligible.isPresent();

                if (!backlog || System.nanoTime() >= deadlineNanos) break;
                if (roundRemoved == 0) {
                    // Another instance may currently own the oldest SKIP LOCKED
                    // rows. Back off briefly instead of spinning against them.
                    if (!sleepCatchup()) break;
                } else if (cleanupCatchupPauseMs > 0 && !sleepCatchup()) {
                    break;
                }
            } while (System.nanoTime() < deadlineNanos);

            long durationMs = elapsedMillis(startedNanos);
            lastCleanupDurationMs.set(durationMs);
            lastDeleteRatePerSecond.set(durationMs == 0L
                    ? removedTotal
                    : Math.round(removedTotal * 1000.0d / durationMs));

            if (removedTotal > 0 || oldestEligible.isPresent()) {
                log.info("Search retention cleanup removed={} cutoff={} durationMs={} rateRowsPerSec={} "
                                + "oldestEligibleAgeSec={} lagSec={} backlogRemaining={}",
                        removedTotal, cutoff, durationMs, lastDeleteRatePerSecond.get(),
                        oldestEligibleAgeSeconds.get(), retentionLagSeconds.get(),
                        oldestEligible.isPresent());
            }
            if (metrics != null && removedTotal > 0) {
                metrics.counter("socp.search.event.retention", "outcome", "deleted")
                        .increment(removedTotal);
            }
        } catch (RuntimeException failure) {
            lastCleanupDurationMs.set(elapsedMillis(startedNanos));
            log.warn("Search event retention cleanup deferred: {}", failure.getMessage());
            if (metrics != null) {
                metrics.counter("socp.search.event.retention", "outcome", "failure").increment();
            }
        }
    }

    private void updateLagMetrics(Instant now, Instant cutoff, Optional<Instant> oldestEligible) {
        if (oldestEligible.isEmpty()) {
            oldestEligibleAgeSeconds.set(0L);
            retentionLagSeconds.set(0L);
            return;
        }
        Instant oldest = oldestEligible.get();
        oldestEligibleAgeSeconds.set(Math.max(0L, Duration.between(oldest, now).toSeconds()));
        retentionLagSeconds.set(Math.max(0L, Duration.between(oldest, cutoff).toSeconds()));
    }

    private boolean sleepCatchup() {
        if (cleanupCatchupPauseMs <= 0) return true;
        try {
            Thread.sleep(cleanupCatchupPauseMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }
}
