package com.socp.soar.web.service;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
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
public class SoarRunRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarRunRecoveryWorker.class);
    private static final Set<String> ACTIVE = Set.of("DISPATCHING", "RUNNING", "CANCELLING");
    private final SoarRunRepository runs;
    private final SoarActionAttemptRepository attempts;
    private final TemporalExecutor temporal;
    private final long staleSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarRunRecoveryWorker(SoarRunRepository runs, SoarActionAttemptRepository attempts,
                                 TemporalExecutor temporal,
                                   @Value("${socp.soar.stuck-run-timeout-seconds:7200}") long staleSeconds) {
        this.runs = runs;
        this.attempts = attempts;
        this.temporal = temporal;
        this.staleSeconds = Math.max(300, Math.min(7 * 24 * 3600L, staleSeconds));
    }

    /** Compatibility constructor for repository-focused tests and old wiring. */
    public SoarRunRecoveryWorker(SoarRunRepository runs, TemporalExecutor temporal,
                                 long staleSeconds) {
        this(runs, null, temporal, staleSeconds);
    }

    /** Compatibility constructor for repository-focused tests. */
    public SoarRunRecoveryWorker(SoarRunRepository runs) {
        this(runs, null, null, 7200);
    }

    @Scheduled(fixedDelayString = "${socp.soar.recovery-poll-ms:60000}",
            initialDelayString = "${socp.soar.recovery-initial-delay-ms:120000}")
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
            // CANCELLING is an operator decision, not a generic stale
            // execution.  If the workflow has already disappeared (or the
            // dispatch never acquired a workflow id), complete that one-way
            // transition as CANCELLED instead of turning it into TIMED_OUT or
            // ACTION_UNKNOWN and asking the operator to resolve cancellation.
            if ("CANCELLING".equals(run.getStatus())) {
                run.setStatus("CANCELLED");
                run.setErrorCode("SOAR_RUN_CANCELLED");
                if (run.getErrorMessage() == null || run.getErrorMessage().isBlank()) {
                    run.setErrorMessage("cancellation completed during stale-run recovery");
                }
                run.setCompletedAt(Instant.now());
                run.setUpdatedAt(Instant.now());
                runs.save(run);
                log.info("Completed cancellation for stale SOAR run {}", run.getId());
                continue;
            }
            boolean hasWorkflow = run.getTemporalWorkflowId() != null
                    && !run.getTemporalWorkflowId().isBlank();
            if (hasWorkflow && temporal != null) {
                TemporalExecutor.WorkflowState state = temporal.describeWorkflow(run.getTemporalWorkflowId());
                // An open workflow may still be making progress; an unknown
                // describe result means Temporal is unavailable. In both
                // cases fail closed and let the next poll/normal projection
                // update decide, rather than creating a duplicate retry.
                if (state == TemporalExecutor.WorkflowState.OPEN
                        || state == TemporalExecutor.WorkflowState.UNKNOWN) continue;
                // Only an attempt that was durably RUNNING when the workflow
                // closed indicates that an external side effect may have been
                // committed. A projection-only failure is a normal FAILED
                // outcome and must not ask an operator to resolve an action
                // that was never admitted for execution.
                Boolean actionInFlight = actionInFlight(run);
                if (actionInFlight == null) continue;
                if (actionInFlight) {
                    run.setStatus("ACTION_UNKNOWN");
                    run.setErrorCode("SOAR_ACTION_RESULT_UNKNOWN");
                    run.setErrorMessage("Temporal workflow closed while an action attempt remained RUNNING");
                } else {
                    run.setStatus("FAILED");
                    run.setErrorCode("SOAR_PROJECTION_STALE");
                    run.setErrorMessage("Temporal workflow closed before the run projection was updated; no action was in flight");
                }
            } else {
                Boolean actionInFlight = actionInFlight(run);
                if (actionInFlight == null) continue;
                if (actionInFlight) {
                    run.setStatus("ACTION_UNKNOWN");
                    run.setErrorCode("SOAR_ACTION_RESULT_UNKNOWN");
                    run.setErrorMessage("run lost its Temporal workflow while an action attempt remained RUNNING");
                } else {
                    run.setStatus("TIMED_OUT");
                    run.setErrorCode("SOAR_PROJECTION_STALE");
                    run.setErrorMessage("run projection was not updated within the recovery lease");
                }
            }
            run.setCompletedAt(Instant.now());
            run.setUpdatedAt(Instant.now());
            runs.save(run);
            log.warn("Marked stale SOAR run {} as {}", run.getId(), run.getStatus());
        }
    }

    /**
     * Returns {@code null} when the durable query cannot be trusted. Recovery
     * leaves the run active in that case; guessing ACTION_UNKNOWN would be an
     * unsafe operator instruction and guessing FAILED could permit a duplicate
     * irreversible action.
     */
    private Boolean actionInFlight(SoarRunEntity run) {
        if (attempts == null) return false;
        try {
            return attempts.existsRunningByTenantIdAndRunId(run.getTenantId(), run.getId());
        } catch (RuntimeException failure) {
            log.warn("Unable to classify stale SOAR run {} action attempts; leaving projection active",
                    run.getId());
            return null;
        }
    }
}
