package com.socp.detect.web.routing;

import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository;
import com.socp.detect.web.persistence.repository.DetectionRouteSourceRepository;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Bounded retention for terminal routing transport evidence. */
@Component
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
public class DetectionRouteMaintenance {

    private final DetectionRouteSourceRepository sources;
    private final DetectionRouteOutboxRepository outbox;
    private final Duration routedRetention;
    private final Duration deadRetention;
    private final int batchSize;
    private final int maxBatches;

    public DetectionRouteMaintenance(
            DetectionRouteSourceRepository sources,
            DetectionRouteOutboxRepository outbox,
            @Value("${socp.detect.routing.source-receipt-retention:30d}") String routedRetention,
            @Value("${socp.detect.routing.dead-retention:90d}") String deadRetention,
            @Value("${socp.detect.routing.cleanup-batch-size:1000}") int batchSize,
            @Value("${socp.detect.routing.cleanup-max-batches:10}") int maxBatches) {
        this.sources = sources;
        this.outbox = outbox;
        this.routedRetention = parseDuration(routedRetention, Duration.ofDays(30));
        this.deadRetention = parseDuration(deadRetention, Duration.ofDays(90));
        this.batchSize = Math.max(1, Math.min(10_000, batchSize));
        this.maxBatches = Math.max(1, Math.min(100, maxBatches));
    }

    @Scheduled(
            fixedDelayString = "${socp.detect.routing.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.detect.routing.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    public void cleanup() {
        Instant now = Instant.now();
        deleteBatches(() -> sources.deleteRoutedBatchBefore(
                now.minus(routedRetention), batchSize));
        deleteBatches(() -> sources.deleteDeadBatchBefore(
                now.minus(deadRetention), batchSize));
        deleteBatches(() -> outbox.deleteDeadBatchBefore(
                now.minus(deadRetention), batchSize));
    }

    private void deleteBatches(java.util.function.IntSupplier delete) {
        for (int batch = 0; batch < maxBatches; batch++) {
            int count = delete.getAsInt();
            if (count < batchSize) break;
        }
    }

    private static Duration parseDuration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            Duration parsed;
            if (value.startsWith("p")) {
                parsed = Duration.parse(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } else if (value.endsWith("d")) {
                parsed = Duration.ofDays(Long.parseLong(value.substring(0, value.length() - 1)));
            } else if (value.endsWith("h")) {
                parsed = Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
            } else if (value.endsWith("m")) {
                parsed = Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            } else if (value.endsWith("s")) {
                parsed = Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            } else {
                parsed = Duration.ofSeconds(Long.parseLong(value));
            }
            return parsed.isNegative() || parsed.isZero() ? fallback : parsed;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }
}
