package com.socp.hips.web.service;

import com.socp.hips.web.persistence.store.EndpointHistoryRetentionStore;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, observable history cleanup, independent of forwarding availability. */
@Service
public class EndpointHistoryRetentionWorker {
    private final EndpointHistoryRetentionStore store;
    private final boolean enabled;
    private final int days;
    private final int batchSize;
    private final int maxBatches;
    private final long maxRunMs;
    private final AtomicLong lagSeconds = new AtomicLong(-1);
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicLong lastDeleted = new AtomicLong();
    private final AtomicLong durationMs = new AtomicLong();

    public EndpointHistoryRetentionWorker(EndpointHistoryRetentionStore store,
            @Value("${socp.hips.history-retention.enabled:true}") boolean enabled,
            @Value("${socp.hips.history-retention.days:30}") int days,
            @Value("${socp.hips.history-retention.batch-size:1000}") int batchSize,
            @Value("${socp.hips.history-retention.max-batches:10}") int maxBatches,
            @Value("${socp.hips.history-retention.max-run-ms:10000}") long maxRunMs,
            ObjectProvider<MeterRegistry> meters) {
        if (days < 1 || days > 3650 || batchSize < 1 || batchSize > 1000 || maxBatches < 1 || maxBatches > 100
                || maxRunMs < 100 || maxRunMs > 60000) throw new IllegalArgumentException("Invalid endpoint history retention limits");
        this.store = store; this.enabled = enabled; this.days = days;
        this.batchSize = batchSize; this.maxBatches = maxBatches; this.maxRunMs = maxRunMs;
        meters.ifAvailable(registry -> {
            registry.gauge("socp.hips.history.retention.lag.seconds", lagSeconds, AtomicLong::get);
            registry.gauge("socp.hips.history.retention.last.success.epoch.seconds", lastSuccess, AtomicLong::get);
            registry.gauge("socp.hips.history.retention.last.deleted", lastDeleted, AtomicLong::get);
            registry.gauge("socp.hips.history.retention.duration.ms", durationMs, AtomicLong::get);
        });
    }

    @Scheduled(fixedDelayString = "${socp.hips.history-retention.delay-ms:60000}",
            initialDelayString = "${socp.hips.history-retention.initial-delay-ms:60000}")
    public void cleanup() {
        if (!enabled) return;
        long started = System.nanoTime();
        long removed = 0;
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        try (var ignored = TenantContext.openSystem()) {
            for (int batch = 0; batch < maxBatches; batch++) {
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= maxRunMs) break;
                int count = store.prune(cutoff, batchSize);
                removed += count;
                if (count < batchSize) break;
            }
            lagSeconds.set(store.oldestEligible(cutoff).map(oldest -> Math.max(0, Duration.between(oldest, cutoff).getSeconds())).orElse(0L));
            lastSuccess.set(Instant.now().getEpochSecond());
        } finally {
            lastDeleted.set(removed);
            durationMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
}
