package com.socp.soar.web.service;

import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Operator commands for dead dispatch and signal outbox rows. The public
 * facade retains transaction and audit boundaries while this collaborator
 * owns the replay and terminal-state fences.
 */
final class SoarOutboxCommandService {

    private final SoarService service;
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarRunRepository runs;
    private final SoarSignalOutboxRepository signals;

    SoarOutboxCommandService(SoarService service) {
        this.service = service;
        this.dispatches = service.dispatches;
        this.runs = service.runs;
        this.signals = service.signals;
    }

    Map<String, Object> requeueDead(String id, String reason) {
        String why = SoarService.redactFreeText(reason == null ? "" : reason, 1024);
        String tenant = service.tenant();
        Optional<SoarDispatchOutboxEntity> dispatch = dispatches.findByTenantIdAndId(tenant, id);
        if (dispatch != null && dispatch.isPresent()) {
            SoarDispatchOutboxEntity row = dispatch.get();
            if (!"DEAD".equals(row.getStatus())) {
                throw SoarService.error(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD", "outbox is not dead");
            }
            Instant now = Instant.now();
            row.setStatus("PENDING");
            row.setAttempts(0);
            row.setLastError("requeued: " + why);
            row.setNextAttemptAt(now);
            row.setUpdatedAt(now);
            dispatches.save(row);
            runs.findByTenantIdAndId(tenant, row.getRunId()).ifPresent(run -> {
                // DEAD is the one recoverable run projection: requeueing its
                // dispatch intentionally returns it to QUEUED. A stale dead
                // outbox attached to any other terminal run must not resurrect
                // that run merely because an operator retried the row.
                if (dispatchRunCanBeRequeued(run)) {
                    run.setStatus("QUEUED");
                    run.setUpdatedAt(now);
                    runs.save(run);
                }
            });
            return Map.of("id", id, "kind", "DISPATCH", "status", "PENDING");
        }

        // Dead-letter operations expose dispatch and signal rows through one
        // operator endpoint. Resolve the same public id against both stores.
        if (signals != null) {
            Optional<SoarSignalOutboxEntity> signal = signals.findByTenantIdAndId(tenant, id);
            if (signal != null && signal.isPresent()) {
                SoarSignalOutboxEntity row = signal.get();
                if (!"DEAD".equals(row.getStatus())) {
                    throw SoarService.error(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD", "outbox is not dead");
                }
                // A signal is only recoverable while its owning run is still
                // resumable. The worker fence remains a second line of defence.
                Optional<SoarRunEntity> owner = runs.findByTenantIdAndIdForUpdate(tenant, row.getRunId());
                if (owner == null || owner.isEmpty()) {
                    owner = runs.findByTenantIdAndId(tenant, row.getRunId());
                }
                if (owner != null && owner.isPresent()
                        && (SoarService.terminalRunProjection(owner.get())
                        || SoarRunStatus.CANCELLING.name().equals(owner.get().getStatus()))) {
                    throw SoarService.error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                            "the signal owner run is already terminal or cancelling");
                }
                Instant now = Instant.now();
                row.setStatus("PENDING");
                row.setAttempts(0);
                row.setLastError("requeued: " + why);
                row.setNextAttemptAt(now);
                row.setUpdatedAt(now);
                signals.save(row);
                return Map.of("id", id, "kind", "SIGNAL", "status", "PENDING",
                        "signalType", nullSafe(row.getSignalType()),
                        "signalKey", nullSafe(row.getSignalKey()));
            }
        }
        throw SoarService.error(HttpStatus.NOT_FOUND, "SOAR_OUTBOX_NOT_FOUND", "outbox not found");
    }

    Map<String, Object> discardDead(String id, String reason) {
        String why = SoarService.redactFreeText(SoarService.required(reason, "reason", 2048), 2048);
        String tenant = service.tenant();
        Optional<SoarDispatchOutboxEntity> dispatch = dispatches.findByTenantIdAndId(tenant, id);
        if (dispatch != null && dispatch.isPresent()) {
            SoarDispatchOutboxEntity row = dispatch.get();
            if (!"DEAD".equals(row.getStatus())) {
                throw SoarService.error(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD", "outbox is not dead");
            }
            Instant now = Instant.now();
            row.setStatus("DISCARDED");
            row.setLastError("discarded: " + why);
            row.setUpdatedAt(now);
            dispatches.save(row);
            Optional<SoarRunEntity> lockedRun = runs.findByTenantIdAndIdForUpdate(tenant, row.getRunId());
            if (lockedRun == null) {
                lockedRun = runs.findByTenantIdAndId(tenant, row.getRunId());
            }
            lockedRun.ifPresent(run -> suppressRun(run, now, "DISPATCH_DISCARDED", why));
            return Map.of("id", id, "kind", "DISPATCH", "status", "DISCARDED");
        }

        if (signals != null) {
            Optional<SoarSignalOutboxEntity> signal = signals.findByTenantIdAndId(tenant, id);
            if (signal != null && signal.isPresent()) {
                SoarSignalOutboxEntity row = signal.get();
                if (!"DEAD".equals(row.getStatus())) {
                    throw SoarService.error(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD", "outbox is not dead");
                }
                Instant now = Instant.now();
                row.setStatus("DISCARDED");
                row.setLastError("discarded: " + why);
                row.setUpdatedAt(now);
                signals.save(row);
                // A dead human/unknown signal must not leave a run waiting
                // forever. Discard is an explicit operator terminal choice.
                Optional<SoarRunEntity> lockedRun = runs.findByTenantIdAndIdForUpdate(tenant, row.getRunId());
                if (lockedRun == null) {
                    lockedRun = runs.findByTenantIdAndId(tenant, row.getRunId());
                }
                lockedRun.ifPresent(run -> {
                    if (suppressRun(run, now, "SIGNAL_DISCARDED", why)) {
                        service.appendEvent(run.getId(), "SIGNAL_DISCARDED", SoarService.actor(),
                                "Dead signal discarded by operator", Map.of("signalId", id,
                                        "signalType", nullSafe(row.getSignalType())));
                    }
                });
                return Map.of("id", id, "kind", "SIGNAL", "status", "DISCARDED",
                        "signalType", nullSafe(row.getSignalType()),
                        "signalKey", nullSafe(row.getSignalKey()));
            }
        }
        throw SoarService.error(HttpStatus.NOT_FOUND, "SOAR_OUTBOX_NOT_FOUND", "outbox not found");
    }

    private boolean suppressRun(SoarRunEntity run, Instant now, String errorCode, String reason) {
        if (SoarService.terminalRunProjection(run)) {
            return false;
        }
        run.setStatus("SUPPRESSED");
        run.setErrorCode(errorCode);
        run.setErrorMessage(reason);
        run.setCompletedAt(now);
        run.setUpdatedAt(now);
        runs.save(run);
        return true;
    }

    private static boolean dispatchRunCanBeRequeued(SoarRunEntity run) {
        return run != null && run.getStatus() != null
                && Set.of(SoarRunStatus.DEAD.name(), SoarRunStatus.QUEUED.name(),
                SoarRunStatus.DISPATCHING.name()).contains(run.getStatus());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
