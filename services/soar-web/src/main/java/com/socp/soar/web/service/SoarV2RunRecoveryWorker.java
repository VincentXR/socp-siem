package com.socp.soar.web.service;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Repairs projections left in an active state when a worker or database
 * connection dies after dispatch. Temporal remains the source of execution
 * truth; this worker only expires runs whose projection has been untouched for
 * a bounded interval, making the condition visible and safely retryable.
 */
@Component
public class SoarV2RunRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarV2RunRecoveryWorker.class);
    private static final Set<String> ACTIVE = Set.of("DISPATCHING", "RUNNING", "CANCELLING");
    private final SoarRunRepository runs;
    private final TemporalExecutor temporal;
    private final long staleSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarV2RunRecoveryWorker(SoarRunRepository runs, TemporalExecutor temporal,
                                   @Value("${socp.soar.v2.stuck-run-timeout-seconds:7200}") long staleSeconds) {
        this.runs = runs;
        this.temporal = temporal;
        this.staleSeconds = Math.max(300, Math.min(7 * 24 * 3600L, staleSeconds));
    }

    /** Compatibility constructor for repository-focused tests. */
    public SoarV2RunRecoveryWorker(SoarRunRepository runs) {
        this(runs, null, 7200);
    }

    @Scheduled(fixedDelayString = "${socp.soar.v2.recovery-poll-ms:60000}",
            initialDelayString = "${socp.soar.v2.recovery-initial-delay-ms:120000}")
    @TenantSystemJob
    @Transactional
    public void tick() {
        Instant cutoff = Instant.now().minusSeconds(staleSeconds);
        List<SoarRunEntity> stale = runs
                .findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(ACTIVE, cutoff);
        for (SoarRunEntity run : stale) {
            // A recovery pass must be idempotent: another worker may have
            // completed the projection after the query snapshot was taken.
            if (!ACTIVE.contains(run.getStatus()) || run.getUpdatedAt() == null
                    || run.getUpdatedAt().isAfter(cutoff)) continue;
            boolean hasWorkflow = run.getTemporalWorkflowId() != null
                    && !run.getTemporalWorkflowId().isBlank();
            if (hasWorkflow && temporal != null) {
                TemporalExecutor.V2WorkflowState state = temporal.describeV2(run.getTemporalWorkflowId());
                // An open workflow may still be making progress; an unknown
                // describe result means Temporal is unavailable. In both
                // cases fail closed and let the next poll/normal projection
                // update decide, rather than creating a duplicate retry.
                if (state == TemporalExecutor.V2WorkflowState.OPEN
                        || state == TemporalExecutor.V2WorkflowState.UNKNOWN) continue;
                // A closed workflow with a stale projection may have already
                // committed a remote side effect. Surface it as UNKNOWN so an
                // operator must provide evidence before retry/rerun.
                run.setStatus("ACTION_UNKNOWN");
                run.setErrorCode("SOAR_PROJECTION_STALE");
                run.setErrorMessage("Temporal workflow closed before the run projection was updated");
            } else {
                run.setStatus("TIMED_OUT");
                run.setErrorCode("SOAR_PROJECTION_STALE");
                run.setErrorMessage("run projection was not updated within the recovery lease");
            }
            run.setCompletedAt(Instant.now());
            run.setUpdatedAt(Instant.now());
            runs.save(run);
            log.warn("Marked stale SOAR run {} as {}", run.getId(), run.getStatus());
        }
    }
}
