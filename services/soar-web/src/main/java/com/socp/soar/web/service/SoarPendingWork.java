package com.socp.soar.web.service;

import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;

import java.time.Instant;
import java.util.Set;

/** Terminal human-work projection, executed inside the owning run's locked transaction. */
public final class SoarPendingWork {
    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED",
            "CANCELLED", "SUPPRESSED", "TIMED_OUT", "DEAD");

    private SoarPendingWork() { }

    public static int closeForTerminalRun(SoarRunEntity run, Instant now,
                                         SoarManualTaskRepository tasks, SoarApprovalRepository approvals,
                                         SoarNodeRunRepository nodes) {
        if (run == null || run.getStatus() == null || !TERMINAL.contains(run.getStatus())) return 0;
        boolean expired = "TIMED_OUT".equals(run.getStatus()) || "EXECUTION_TIMEOUT".equals(run.getErrorCode());
        String workStatus = expired ? "EXPIRED" : "CANCELLED";
        int changed = tasks.closePendingForRun(run.getTenantId(), run.getId(), workStatus, now);
        changed += approvals.closePendingForRun(run.getTenantId(), run.getId(), workStatus, now);
        changed += nodes.closeHumanWaitsForRun(run.getTenantId(), run.getId(),
                expired ? "TIMED_OUT" : "CANCELLED", expired ? "EXECUTION_TIMEOUT" : "RUN_TERMINATED", now);
        return changed;
    }
}
