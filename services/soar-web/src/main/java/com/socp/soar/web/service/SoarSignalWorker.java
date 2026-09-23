package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.config.SoarRuntimeProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable dispatcher for approval/manual-task Temporal signals. */
@Component
public class SoarSignalWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarSignalWorker.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final int BATCH_SIZE = 100;
    private final SoarSignalOutboxRepository signals;
    private final SoarRunRepository runs;
    private final TemporalExecutor temporal;
    private final ObjectMapper mapper;
    private SoarRuntimeProperties runtimeProperties;
    private final String workerId = "soar-signal-" + UUID.randomUUID().toString().substring(0, 12);

    public SoarSignalWorker(SoarSignalOutboxRepository signals, SoarRunRepository runs,
                              TemporalExecutor temporal, ObjectMapper mapper) {
        this.signals = signals; this.runs = runs; this.temporal = temporal; this.mapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeProperties(SoarRuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(fixedDelayString = "${socp.soar.signal-poll-ms:1000}",
            initialDelayString = "${socp.soar.signal-initial-delay-ms:3000}")
    @TenantSystemJob
    public void tick() {
        Instant now = Instant.now();
        signals.recoverStaleClaims(now.minusSeconds(120), now, MAX_ATTEMPTS, BATCH_SIZE);
        signals.markExhausted(now, MAX_ATTEMPTS, BATCH_SIZE);
        if (runtimeProperties != null && !runtimeProperties.isExecutionEnabled()) return;
        if (!temporal.isAvailable()) return;
        List<SoarSignalOutboxEntity> pending = signals
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now);
        for (SoarSignalOutboxEntity signal : pending) {
            try { deliver(signal); }
            catch (RuntimeException failure) {
                // A persistence failure leaves the claim for bounded recovery;
                // it must not block every other tenant in this batch.
                log.warn("SOAR signal delivery failed id={}: {}", signal.getId(), redactFreeText(failure.getMessage(), 2048));
            }
        }
    }

    private void deliver(SoarSignalOutboxEntity signal) {
        Instant claimedAt = Instant.now();
        long version = java.util.Objects.requireNonNull(signal.getRowVersion(), "persisted signal version");
        if (signals.claim(signal.getTenantId(), signal.getId(), workerId, claimedAt, version, MAX_ATTEMPTS) != 1) return;
        signal.setRowVersion(version + 1);
        signal.setAttempts(signal.getAttempts() + 1);
        signal.setStatus("SENDING");
        TenantContext.runAsSystem(() -> {
            try {
                var run = runs.findByTenantIdAndId(signal.getTenantId(), signal.getRunId()).orElse(null);
                if (run != null && (terminalRun(run) || "CANCELLING".equals(run.getStatus())
                        || ("ACTION_UNKNOWN".equals(run.getStatus())
                        && !"UNKNOWN_RESOLUTION".equals(signal.getSignalType())))) {
                    // A late operator decision must never reopen a completed
                    // workflow.  Persist the skip so this row cannot be
                    // retried forever or accidentally signal a new execution.
                    signal.setStatus("CANCELLED");
                    signal.setLastError("signal skipped for run status " + run.getStatus());
                    signal.setClaimedAt(Instant.now());
                    signal.setUpdatedAt(Instant.now());
                    complete(signal);
                    return;
                }
                if (run == null) throw new SoarSignalPayload.Invalid("signal owner run is missing");
                SoarSignalPayload payload = SoarSignalPayload.parse(mapper, signal.getSignalType(),
                        signal.getSignalKey(), signal.getPayloadJson());
                if (run.getTemporalWorkflowId() == null || run.getTemporalWorkflowId().isBlank()) {
                    throw new IllegalStateException("signal owner has no attached workflow yet");
                }
                if ("APPROVAL".equals(payload.type())) {
                    if (payload.key().isBlank()) {
                        // Compatibility with signals written by pre-gate-key
                        // workers. New rows always carry approvalKey.
                        temporal.decide(run.getTemporalWorkflowId(), payload.approve());
                    } else {
                        temporal.decideGate(run.getTemporalWorkflowId(),
                                payload.approve(), payload.key(), payload.expired());
                    }
                } else if ("MANUAL_TASK".equals(payload.type())) {
                    if (payload.key().isBlank()) {
                        temporal.completeManualTask(run.getTemporalWorkflowId(), payload.inputJson());
                    } else {
                        temporal.completeManualTaskForNode(run.getTemporalWorkflowId(), payload.key(), payload.inputJson());
                    }
                } else if ("UNKNOWN_RESOLUTION".equals(payload.type())) {
                    temporal.resolveUnknown(run.getTemporalWorkflowId(),
                            payload.key(), payload.resolution(), payload.evidence(), payload.reason());
                }
                signal.setStatus("SENT"); signal.setLastError(null); complete(signal);
            } catch (RuntimeException failure) {
                int attempts = signal.getAttempts();
                signal.setLastError(redactFreeText(failure.getMessage(), 2048)); signal.setUpdatedAt(Instant.now());
                if (failure instanceof SoarSignalPayload.Invalid || attempts >= MAX_ATTEMPTS) signal.setStatus("DEAD");
                else { signal.setStatus("PENDING"); signal.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, attempts)))); }
                complete(signal);
            }
        });
    }

    private void complete(SoarSignalOutboxEntity signal) {
        int changed = signals.completeClaim(signal.getTenantId(), signal.getId(), signal.getRowVersion(), workerId,
                signal.getStatus(), signal.getNextAttemptAt(), signal.getLastError(), Instant.now());
        if (changed != 1) log.debug("Ignoring stale SOAR signal result id={}", signal.getId());
    }

    private static boolean terminalRun(com.socp.soar.web.persistence.entity.SoarRunEntity run) {
        if (run == null || run.getStatus() == null) return false;
        return java.util.Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "TIMED_OUT",
                "CANCELLED", "SUPPRESSED", "DEAD").contains(run.getStatus());
    }

    private static String redactFreeText(String value, int max) {
        if (value == null) return "signal delivery failed";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
