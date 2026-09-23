package com.socp.soar.web.service;

import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Recheck an external observation against the current, locked run version. */
@Service
public class SoarRunRecoveryState {
    static final Set<String> ACTIVE = Set.of("DISPATCHING", "RUNNING", "CANCELLING", "WAITING_APPROVAL", "WAITING_INPUT");
    private final SoarRunRepository runs;
    private final SoarActionAttemptRepository attempts;
    private final SoarDispatchOutboxRepository dispatches;

    public SoarRunRecoveryState(SoarRunRepository runs, SoarActionAttemptRepository attempts,
                                SoarDispatchOutboxRepository dispatches) {
        this.runs = runs;
        this.attempts = attempts;
        this.dispatches = dispatches;
    }

    @Transactional
    public boolean recover(SoarRunEntity snapshot, TemporalExecutor.WorkflowState observation,
                           Instant cutoff, Instant now) {
        if (observation != TemporalExecutor.WorkflowState.CLOSED
                && observation != TemporalExecutor.WorkflowState.NOT_FOUND) return false;
        var current = runs.findByTenantIdAndIdForUpdateSkipLocked(snapshot.getTenantId(), snapshot.getId());
        if (current.isEmpty()) return false;
        var run = current.get();
        if (snapshot.getRowVersion() == null || !Objects.equals(snapshot.getRowVersion(), run.getRowVersion())
                || !ACTIVE.contains(run.getStatus()) || run.getUpdatedAt() == null
                || !run.getUpdatedAt().isBefore(cutoff)) return false;
        // A pending/recovered start is owned by the durable dispatch protocol;
        // an observation made before that start cannot prove a terminal run.
        var dispatch = dispatches.findByTenantIdAndRunId(run.getTenantId(), run.getId());
        if (dispatch.isPresent() && Set.of("PENDING", "DISPATCHING").contains(dispatch.get().getStatus())) return false;
        boolean inFlight = attempts.existsRunningByTenantIdAndRunId(run.getTenantId(), run.getId());
        if ("CANCELLING".equals(run.getStatus())) {
            run.setStatus("CANCELLED");
            run.setErrorCode("SOAR_RUN_CANCELLED");
            if (inFlight) {
                run.setErrorMessage("Workflow is no longer open; an action receipt is still unconfirmed. "
                        + "Cancellation does not undo external side effects; inspect attempts before an explicit rerun.");
            } else if (run.getErrorMessage() == null || run.getErrorMessage().isBlank()) {
                run.setErrorMessage("Cancellation settled after Temporal confirmed the workflow closed or absent");
            }
        } else if (inFlight) {
            run.setStatus("ACTION_UNKNOWN");
            run.setErrorCode("SOAR_ACTION_RESULT_UNKNOWN");
            run.setErrorMessage("Temporal workflow is no longer open while an action attempt remains RUNNING");
        } else {
            run.setStatus(observation == TemporalExecutor.WorkflowState.NOT_FOUND ? "TIMED_OUT" : "FAILED");
            run.setErrorCode("SOAR_PROJECTION_STALE");
            run.setErrorMessage("Temporal confirmed the workflow closed or absent before the projection completed; "
                    + "no action was in flight");
        }
        run.setCompletedAt(now);
        run.setUpdatedAt(now);
        return true;
    }
}
