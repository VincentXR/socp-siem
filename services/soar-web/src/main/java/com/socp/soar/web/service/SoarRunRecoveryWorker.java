package com.socp.soar.web.service;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Observe Temporal outside transactions, then reconcile only the unchanged run version. */
@Component
public class SoarRunRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarRunRecoveryWorker.class);
    private final SoarRunRepository runs;
    private final TemporalExecutor temporal;
    private final SoarRunRecoveryState state;
    private final long staleSeconds;

    public SoarRunRecoveryWorker(SoarRunRepository runs, TemporalExecutor temporal, SoarRunRecoveryState state,
                                  @Value("${socp.soar.stuck-run-timeout-seconds:7200}") long staleSeconds) {
        this.runs = runs;
        this.temporal = temporal;
        this.state = state;
        this.staleSeconds = Math.max(300, Math.min(7 * 24 * 3600L, staleSeconds));
    }

    @Scheduled(fixedDelayString = "${socp.soar.recovery-poll-ms:60000}",
            initialDelayString = "${socp.soar.recovery-initial-delay-ms:120000}")
    @TenantSystemJob
    public void tick() {
        if (!temporal.isAvailable()) return;
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(staleSeconds);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (var run : runs.findRecoveryCandidates(SoarRunRecoveryState.ACTIVE, cutoff, now, PageRequest.of(0, 100))) {
            if (System.nanoTime() >= deadline) break;
            try {
                if (run.getRowVersion() == null || runs.claimRecoveryCheck(run.getTenantId(), run.getId(),
                        run.getRowVersion(), cutoff, Instant.now(), Instant.now().plusSeconds(60)) != 1) continue;
                String workflowId = run.getTemporalWorkflowId();
                // Dispatch identities are deterministic even if an old projection
                // lost its attachment. Missing local metadata is not proof of absence.
                if (workflowId == null || workflowId.isBlank()) {
                    workflowId = "soar-" + run.getTenantId() + "-" + run.getId();
                }
                var observation = temporal.describeWorkflow(workflowId);
                if (state.recover(run, observation, cutoff, Instant.now())) {
                    log.info("Recovered stale SOAR projection run={}", run.getId());
                }
            } catch (RuntimeException failure) {
                // The database transaction rolls back this record only.
                log.warn("Unable to reconcile SOAR run {}; retaining its current projection", run.getId());
            }
        }
    }
}
