package com.socp.soar.web.service;

import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Short database transactions; no Temporal call may run while these locks are held. */
@Service
public class SoarDispatchState {
    public static final int MAX_ATTEMPTS = 10;
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarRunRepository runs;

    public SoarDispatchState(SoarDispatchOutboxRepository dispatches, SoarRunRepository runs) {
        this.dispatches = dispatches;
        this.runs = runs;
    }

    public record Claim(String tenant, String id, String runId, long version, String worker,
                        SoarRunEntity run) {
        public String workflowId() { return "soar-" + tenant + "-" + runId; }
    }

    @Transactional
    public Optional<Claim> claim(SoarDispatchOutboxEntity candidate, String worker, Instant now) {
        var pair = lock(candidate.getTenantId(), candidate.getId(), candidate.getRunId());
        if (pair == null || !Objects.equals(candidate.getRowVersion(), pair.outbox().getRowVersion())) {
            return Optional.empty();
        }
        var row = pair.outbox();
        var run = pair.run();
        if (!"PENDING".equals(row.getStatus()) || row.getNextAttemptAt().isAfter(now)
                || row.getAttempts() >= MAX_ATTEMPTS) return Optional.empty();
        // A recovered claim may still have a DISPATCHING projection. Reuse its
        // deterministic workflow identity instead of abandoning the durable retry.
        if (!dispatchable(run)) {
            finish(row, "CANCELLED", "dispatch skipped for run status " + run.getStatus(), now);
            return Optional.empty();
        }
        row.setStatus("DISPATCHING");
        row.setClaimedBy(worker);
        row.setClaimedAt(now);
        row.setAttempts(row.getAttempts() + 1);
        row.setUpdatedAt(now);
        run.setStatus("DISPATCHING");
        run.setTemporalWorkflowId("soar-" + row.getTenantId() + "-" + run.getId());
        run.setUpdatedAt(now);
        // Flush obtains the new @Version value used as the durable claim token.
        dispatches.saveAndFlush(row);
        return Optional.of(new Claim(row.getTenantId(), row.getId(), row.getRunId(),
                row.getRowVersion(), worker, run));
    }

    @Transactional
    public boolean beforeStart(Claim claim, Instant now) {
        var pair = owned(claim);
        if (pair == null) return false;
        if ("DISPATCHING".equals(pair.run().getStatus())) return true;
        finish(pair.outbox(), "CANCELLED", "dispatch skipped for run status " + pair.run().getStatus(), now);
        return false;
    }

    @Transactional
    public boolean complete(Claim claim, String temporalRunId, Instant now) {
        var pair = owned(claim);
        if (pair == null) return false;
        if (temporalRunId != null && !temporalRunId.isBlank()) {
            pair.run().setTemporalRunId(temporalRunId);
            pair.run().setUpdatedAt(now);
        }
        finish(pair.outbox(), "DISPATCHED", null, now);
        return true;
    }

    @Transactional
    public boolean fail(Claim claim, String error, boolean permanent, Instant now) {
        var pair = owned(claim);
        if (pair == null) return false;
        boolean dead = permanent || pair.outbox().getAttempts() >= MAX_ATTEMPTS;
        finish(pair.outbox(), dead ? "DEAD" : "PENDING", error, now);
        if (!dead) pair.outbox().setNextAttemptAt(now.plusSeconds(
                Math.min(300, 1L << Math.min(pair.outbox().getAttempts(), 8))));
        projectFailure(pair.run(), dead, now);
        return true;
    }

    @Transactional
    public boolean recover(SoarDispatchOutboxEntity candidate, Instant cutoff, Instant now) {
        var pair = lock(candidate.getTenantId(), candidate.getId(), candidate.getRunId());
        if (pair == null || !Objects.equals(candidate.getRowVersion(), pair.outbox().getRowVersion())) return false;
        var row = pair.outbox();
        boolean expired = "DISPATCHING".equals(row.getStatus()) && row.getClaimedAt() != null
                && row.getClaimedAt().isBefore(cutoff);
        boolean exhausted = "PENDING".equals(row.getStatus()) && row.getAttempts() >= MAX_ATTEMPTS;
        if (!expired && !exhausted) return false;
        boolean dead = row.getAttempts() >= MAX_ATTEMPTS;
        finish(row, dead ? "DEAD" : "PENDING", "dispatch claim expired or attempts exhausted", now);
        projectFailure(pair.run(), dead, now);
        return true;
    }

    private Pair owned(Claim claim) {
        var pair = lock(claim.tenant(), claim.id(), claim.runId());
        return pair != null && "DISPATCHING".equals(pair.outbox().getStatus())
                && Objects.equals(claim.version(), pair.outbox().getRowVersion())
                && Objects.equals(claim.worker(), pair.outbox().getClaimedBy()) ? pair : null;
    }

    private Pair lock(String tenant, String id, String runId) {
        // Keep one lock order; SKIP LOCKED also avoids waiting behind an
        // operator command or a workflow activity holding either record.
        var run = runs.findByTenantIdAndIdForUpdateSkipLocked(tenant, runId);
        if (run.isEmpty()) return null;
        var row = dispatches.findByTenantIdAndIdForUpdateSkipLocked(tenant, id);
        if (row.isEmpty() || !Objects.equals(runId, row.get().getRunId())) return null;
        return new Pair(row.get(), run.get());
    }

    private static void finish(SoarDispatchOutboxEntity row, String status, String error, Instant now) {
        row.setStatus(status);
        row.setClaimedBy(null);
        row.setClaimedAt(null);
        row.setLastError(error);
        row.setNextAttemptAt(now);
        row.setUpdatedAt(now);
    }

    private static boolean dispatchable(SoarRunEntity run) {
        return "QUEUED".equals(run.getStatus()) || "DISPATCHING".equals(run.getStatus());
    }

    private static void projectFailure(SoarRunEntity run, boolean dead, Instant now) {
        // Execution, approval, manual input, cancellation and terminal states
        // belong to their owners; a start acknowledgement cannot roll them back.
        if (!dispatchable(run)) return;
        run.setStatus(dead ? "DEAD" : "QUEUED");
        if (dead) {
            run.setErrorCode("DISPATCH_DEAD_LETTER");
            run.setErrorMessage("Temporal dispatch failed; inspect the durable outbox before requeueing");
        }
        run.setUpdatedAt(now);
    }

    private record Pair(SoarDispatchOutboxEntity outbox, SoarRunEntity run) { }
}
