package com.socp.soar.web.service;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Retry cancellation without representing signal acceptance as execution progress. */
@Component
public class SoarCancellationWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarCancellationWorker.class);
    private final SoarRunRepository runs;
    private final TemporalExecutor temporal;

    public SoarCancellationWorker(SoarRunRepository runs, TemporalExecutor temporal) {
        this.runs = runs;
        this.temporal = temporal;
    }

    @Scheduled(fixedDelayString = "${socp.soar.cancel-poll-ms:1000}",
            initialDelayString = "${socp.soar.cancel-initial-delay-ms:3000}")
    @TenantSystemJob
    public void tick() {
        if (!temporal.isAvailable()) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (var run : runs.findCancellationCandidates(Instant.now(), PageRequest.of(0, 100))) {
            if (System.nanoTime() >= deadline) break;
            try {
                String workflowId = run.getTemporalWorkflowId();
                if (workflowId == null || workflowId.isBlank() || run.getRowVersion() == null) continue;
                Instant now = Instant.now();
                if (runs.claimCancellation(run.getTenantId(), run.getId(), run.getRowVersion(),
                        workflowId, now, now.plusSeconds(30)) != 1) continue;
                temporal.cancelWorkflow(workflowId);
                // No run save: late signal acceptance cannot change an activity's
                // terminal result or postpone stale-projection reconciliation.
            } catch (RuntimeException failure) {
                log.debug("Cancellation remains pending for SOAR run {}", run.getId());
            }
        }
    }
}
