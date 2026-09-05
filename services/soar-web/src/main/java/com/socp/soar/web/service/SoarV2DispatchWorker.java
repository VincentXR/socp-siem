package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.temporal.v2.SoarV2WorkflowRequest;
import io.temporal.api.common.v1.WorkflowExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbox dispatcher: no Temporal means durable QUEUED, never an in-process fallback. */
@Component
public class SoarV2DispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(SoarV2DispatchWorker.class);
    private static final int MAX_ATTEMPTS = 10;
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarRunRepository runs;
    private final PlaybookVersionRepository versions;
    private final TemporalExecutor temporal;
    private final ObjectMapper mapper;
    private final String workerId = "soar-v2-" + UUID.randomUUID().toString().substring(0, 12);

    public SoarV2DispatchWorker(SoarDispatchOutboxRepository dispatches, SoarRunRepository runs,
                                PlaybookVersionRepository versions, TemporalExecutor temporal,
                                ObjectMapper mapper) {
        this.dispatches = dispatches;
        this.runs = runs;
        this.versions = versions;
        this.temporal = temporal;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${socp.soar.v2.dispatch-poll-ms:1000}",
            initialDelayString = "${socp.soar.v2.dispatch-initial-delay-ms:3000}")
    @TenantSystemJob
    public void tick() {
        Instant now = Instant.now();
        // A worker can die after the atomic claim and before the Temporal
        // start call. Reopen old claims so another worker can safely retry.
        dispatches.recoverStaleClaims(now.minusSeconds(120), now);
        List<SoarDispatchOutboxEntity> pending = dispatches
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now);
        for (SoarDispatchOutboxEntity outbox : pending) {
            try {
                dispatch(outbox);
            } catch (RuntimeException failure) {
                fail(outbox, failure);
            }
        }
    }

    private void dispatch(SoarDispatchOutboxEntity outbox) {
        if (!temporal.isAvailable()) return;
        Instant claimedAt = Instant.now();
        if (dispatches.claim(outbox.getTenantId(), outbox.getId(), workerId, claimedAt) != 1) return;
        outbox.setStatus("DISPATCHING");
        String tenant = outbox.getTenantId();
        TenantContext.runAsSystem(() -> {
            SoarRunEntity run = runs.findByTenantIdAndId(tenant, outbox.getRunId())
                    .orElseThrow(() -> new IllegalStateException("run not found: " + outbox.getRunId()));
            // A cancellation/recovery worker may have terminalized the run
            // after the outbox poll but before this claim. Never resurrect a
            // terminal projection by starting a late Temporal workflow.
            if (!"QUEUED".equals(run.getStatus())) {
                outbox.setStatus("CANCELLED");
                outbox.setLastError("dispatch skipped for run status " + run.getStatus());
                outbox.setUpdatedAt(Instant.now());
                dispatches.save(outbox);
                return;
            }
            PlaybookVersionEntity version = versions.findByTenantIdAndId(tenant, run.getPlaybookVersionId())
                    .orElseThrow(() -> new IllegalStateException("version not found: " + run.getPlaybookVersionId()));
            String workflowId = "soar-v2-" + tenant + "-" + run.getId();
            run.setStatus("DISPATCHING");
            run.setTemporalWorkflowId(workflowId);
            run.setUpdatedAt(Instant.now());
            runs.save(run);
            try {
                String resume = resumeNode(run.getInputJson());
                WorkflowExecution execution = temporal.startV2(new SoarV2WorkflowRequest(
                        tenant, run.getId(), version.getId(), version.getDefinitionJson(), run.getInputJson(),
                        run.getExecutionSeriesId(), resume), workflowId);
                run.setTemporalRunId(execution.getRunId());
                run.setUpdatedAt(Instant.now());
                runs.save(run);
                outbox.setStatus("DISPATCHED");
                outbox.setClaimedBy(workerId);
                outbox.setClaimedAt(Instant.now());
                outbox.setUpdatedAt(Instant.now());
                dispatches.save(outbox);
            } catch (RuntimeException alreadyStarted) {
                // An HTTP timeout after Temporal accepted StartWorkflow is safe to
                // retry because the deterministic workflow id is the idempotency key.
                String failureText = (alreadyStarted.getClass().getSimpleName() + " "
                        + alreadyStarted.getMessage()).toLowerCase();
                if (failureText.contains("already started") || failureText.contains("workflowexecutionalreadystarted")) {
                    outbox.setStatus("DISPATCHED");
                    outbox.setClaimedBy(workerId);
                    outbox.setClaimedAt(Instant.now());
                    outbox.setUpdatedAt(Instant.now());
                    dispatches.save(outbox);
                } else {
                    throw alreadyStarted;
                }
            }
        });
    }

    private String resumeNode(String inputJson) {
        try {
            var root = mapper.readTree(inputJson == null ? "{}" : inputJson);
            String value = root.path("_soar").path("resumeFromNodeId").asText("");
            return value.isBlank() ? null : value;
        } catch (Exception ignored) { return null; }
    }

    private void fail(SoarDispatchOutboxEntity outbox, RuntimeException failure) {
        TenantContext.runAsSystem(() -> {
            int attempts = outbox.getAttempts() + 1;
            outbox.setAttempts(attempts);
            outbox.setLastError(redactFreeText(failure.getMessage(), 2048));
            outbox.setUpdatedAt(Instant.now());
            if (attempts >= MAX_ATTEMPTS) {
                outbox.setStatus("DEAD");
                runs.findByTenantIdAndId(outbox.getTenantId(), outbox.getRunId()).ifPresent(run -> {
                    run.setStatus("DEAD");
                    run.setErrorCode("DISPATCH_DEAD_LETTER");
                    run.setErrorMessage("Temporal dispatch exhausted retries");
                    run.setUpdatedAt(Instant.now());
                    runs.save(run);
                });
            } else {
                outbox.setStatus("PENDING");
                outbox.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(attempts, 8))));
                // The run is allowed to be retried when Temporal is down or a
                // transient start call fails. Never leave it stuck in the
                // intermediate DISPATCHING projection.
                runs.findByTenantIdAndId(outbox.getTenantId(), outbox.getRunId()).ifPresent(run -> {
                    if (!"RUNNING".equals(run.getStatus()) && !"WAITING_APPROVAL".equals(run.getStatus())) {
                        run.setStatus("QUEUED");
                        run.setUpdatedAt(Instant.now());
                        runs.save(run);
                    }
                });
            }
            dispatches.save(outbox);
            log.warn("SOAR V2 dispatch failed run={} attempt={}: {}", outbox.getRunId(), attempts,
                    redactFreeText(failure.getMessage(), 2048));
        });
    }

    private static String redactFreeText(String value, int max) {
        if (value == null) return "dispatch failed";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
