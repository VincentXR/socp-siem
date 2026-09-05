package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable dispatcher for approval/manual-task Temporal signals. */
@Component
public class SoarV2SignalWorker {
    private static final int MAX_ATTEMPTS = 10;
    private final SoarSignalOutboxRepository signals;
    private final SoarRunRepository runs;
    private final TemporalExecutor temporal;
    private final ObjectMapper mapper;
    private final String workerId = "soar-signal-" + UUID.randomUUID().toString().substring(0, 12);

    public SoarV2SignalWorker(SoarSignalOutboxRepository signals, SoarRunRepository runs,
                              TemporalExecutor temporal, ObjectMapper mapper) {
        this.signals = signals; this.runs = runs; this.temporal = temporal; this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${socp.soar.v2.signal-poll-ms:1000}",
            initialDelayString = "${socp.soar.v2.signal-initial-delay-ms:3000}")
    @TenantSystemJob
    public void tick() {
        Instant now = Instant.now();
        signals.recoverStaleClaims(now.minusSeconds(120), now);
        if (!temporal.isAvailable()) return;
        List<SoarSignalOutboxEntity> pending = signals
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now);
        for (SoarSignalOutboxEntity signal : pending) deliver(signal);
    }

    private void deliver(SoarSignalOutboxEntity signal) {
        Instant claimedAt = Instant.now();
        if (signals.claim(signal.getTenantId(), signal.getId(), workerId, claimedAt) != 1) return;
        signal.setStatus("SENDING");
        TenantContext.runAsSystem(() -> {
            try {
                var run = runs.findByTenantIdAndId(signal.getTenantId(), signal.getRunId()).orElse(null);
                if (run == null || run.getTemporalWorkflowId() == null || run.getTemporalWorkflowId().isBlank()) {
                    // Approval before dispatch is represented by the dispatch outbox;
                    // no Temporal signal is needed yet.
                    signal.setStatus("SENT"); signal.setUpdatedAt(Instant.now()); signals.save(signal); return;
                }
                Map<String, Object> payload = read(signal.getPayloadJson());
                if ("APPROVAL".equals(signal.getSignalType())) {
                    Object rawApprovalKey = payload.get("approvalKey");
                    String approvalKey = rawApprovalKey == null ? "" : String.valueOf(rawApprovalKey).trim();
                    boolean expired = Boolean.TRUE.equals(payload.get("expired"));
                    if (approvalKey.isBlank()) {
                        // Compatibility with signals written by pre-gate-key
                        // workers. New rows always carry approvalKey.
                        temporal.decideV2(run.getTemporalWorkflowId(), Boolean.TRUE.equals(payload.get("approve")));
                    } else {
                        temporal.decideGateV2(run.getTemporalWorkflowId(),
                                Boolean.TRUE.equals(payload.get("approve")), approvalKey, expired);
                    }
                } else if ("MANUAL_TASK".equals(signal.getSignalType())) {
                    Object rawNodeId = payload.get("nodeId");
                    String nodeId = rawNodeId == null ? "" : String.valueOf(rawNodeId).trim();
                    if (nodeId.isBlank()) {
                        temporal.completeManualTask(run.getTemporalWorkflowId(), json(payload.getOrDefault("input", Map.of())));
                    } else {
                        temporal.completeManualTaskForNode(run.getTemporalWorkflowId(), nodeId,
                                json(payload.getOrDefault("input", Map.of())));
                    }
                } else if ("UNKNOWN_RESOLUTION".equals(signal.getSignalType())) {
                    temporal.resolveUnknown(run.getTemporalWorkflowId(),
                            String.valueOf(payload.getOrDefault("nodeId", "")),
                            String.valueOf(payload.getOrDefault("resolution", "")),
                            String.valueOf(payload.getOrDefault("evidence", "")),
                            String.valueOf(payload.getOrDefault("reason", "")));
                }
                signal.setStatus("SENT"); signal.setClaimedAt(Instant.now()); signal.setUpdatedAt(Instant.now()); signals.save(signal);
            } catch (RuntimeException failure) {
                int attempts = signal.getAttempts() + 1; signal.setAttempts(attempts);
                signal.setLastError(redactFreeText(failure.getMessage(), 2048)); signal.setUpdatedAt(Instant.now());
                if (attempts >= MAX_ATTEMPTS) signal.setStatus("DEAD");
                else { signal.setStatus("PENDING"); signal.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, attempts)))); }
                signals.save(signal);
            }
        });
    }

    private Map<String, Object> read(String json) {
        try { Map<String, Object> value = mapper.readValue(json == null ? "{}" : json, Map.class); return value == null ? Map.of() : value; }
        catch (Exception ignored) { return Map.of(); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ignored) { return "{}"; }
    }

    private static String redactFreeText(String value, int max) {
        if (value == null) return "signal delivery failed";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
