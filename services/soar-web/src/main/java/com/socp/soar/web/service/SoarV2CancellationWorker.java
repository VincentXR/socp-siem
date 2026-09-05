package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Delivers cancellation signals asynchronously and keeps CANCELLING observable on failure. */
@Component
public class SoarV2CancellationWorker {
    private final SoarRunRepository runs;
    private final TemporalExecutor temporal;

    public SoarV2CancellationWorker(SoarRunRepository runs, TemporalExecutor temporal) {
        this.runs = runs;
        this.temporal = temporal;
    }

    @Scheduled(fixedDelayString = "${socp.soar.v2.cancel-poll-ms:1000}",
            initialDelayString = "${socp.soar.v2.cancel-initial-delay-ms:3000}")
    @TenantSystemJob
    public void tick() {
        if (!temporal.isAvailable()) return;
        for (SoarRunEntity run : runs.findTop100ByStatusOrderByUpdatedAtAsc("CANCELLING")) {
            if (run.getTemporalWorkflowId() == null || run.getTemporalWorkflowId().isBlank()) continue;
            try {
                temporal.cancelV2(run.getTemporalWorkflowId());
                TenantContext.runAsSystem(() -> {
                    run.setUpdatedAt(Instant.now());
                    runs.save(run);
                });
            } catch (RuntimeException ignored) {
                // Keep the row CANCELLING so a later tick retries the signal.
            }
        }
    }
}
