package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Durable, version-fenced dispatch; Temporal calls run outside database transactions. */
@Component
public class SoarDispatchWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarDispatchWorker.class);
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarRunRepository runs;
    private final PlaybookVersionRepository versions;
    private final SoarDispatchState state;
    private final TemporalExecutor temporal;
    private final ObjectMapper mapper;
    private SoarRuntimeProperties runtimeProperties;
    private final String workerId = "soar-" + UUID.randomUUID().toString().substring(0, 12);

    public SoarDispatchWorker(SoarDispatchOutboxRepository dispatches, SoarRunRepository runs,
                              PlaybookVersionRepository versions, SoarDispatchState state,
                              TemporalExecutor temporal, ObjectMapper mapper) {
        this.dispatches = dispatches;
        this.runs = runs;
        this.versions = versions;
        this.state = state;
        this.temporal = temporal;
        this.mapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeProperties(SoarRuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(fixedDelayString = "${socp.soar.dispatch-poll-ms:1000}",
            initialDelayString = "${socp.soar.dispatch-initial-delay-ms:3000}")
    @TenantSystemJob
    public void tick() {
        Instant now = Instant.now();
        recoverStaleClaims(now);
        if (runtimeProperties != null && !runtimeProperties.isExecutionEnabled()) return;
        if (!temporal.isAvailable()) return;
        for (var candidate : dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now)) {
            try {
                dispatch(candidate);
            } catch (RuntimeException failure) {
                // Database failures leave the lease recoverable. Never run an
                // unfenced failure handler against the polled entity snapshot.
                log.warn("SOAR dispatch persistence failed run={}: {}", candidate.getRunId(),
                        redactFreeText(failure.getMessage(), 2048));
            }
        }
    }

    private void recoverStaleClaims(Instant now) {
        Instant cutoff = now.minusSeconds(120);
        for (var candidate : dispatches.findRecoveryCandidates(cutoff, SoarDispatchState.MAX_ATTEMPTS, 100)) {
            try {
                state.recover(candidate, cutoff, now);
            } catch (RuntimeException failure) {
                log.warn("SOAR dispatch recovery failed run={}: {}", candidate.getRunId(),
                        redactFreeText(failure.getMessage(), 2048));
            }
        }
    }

    private void dispatch(SoarDispatchOutboxEntity candidate) {
        var claimed = state.claim(candidate, workerId, Instant.now());
        if (claimed.isEmpty()) return;
        var claim = claimed.get();
        SoarWorkflowRequest request;
        try {
            request = request(claim);
        } catch (InvalidPayload invalid) {
            state.fail(claim, invalid.getMessage(), true, Instant.now());
            return;
        } catch (RuntimeException failure) {
            fail(claim, failure);
            return;
        }
        if (!state.beforeStart(claim, Instant.now())) return;
        WorkflowExecution execution;
        try {
            execution = temporal.startWorkflow(request, claim.workflowId());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            if (!claim.workflowId().equals(duplicate.getExecution().getWorkflowId())) {
                fail(claim, duplicate);
                return;
            }
            execution = duplicate.getExecution();
        } catch (RuntimeException failure) {
            fail(claim, failure);
            return;
        }
        // A persistence error after acceptance must leave a recoverable lease,
        // not masquerade as a failed remote start and reset the projection.
        state.complete(claim, execution.getRunId(), Instant.now());
        runs.findByTenantIdAndId(claim.tenant(), claim.runId()).ifPresent(run -> {
            if ("CANCELLING".equals(run.getStatus())) {
                try { temporal.cancelWorkflow(claim.workflowId()); }
                catch (RuntimeException failure) {
                    // SoarCancellationWorker durably retries CANCELLING runs.
                    log.warn("Unable to deliver late cancellation for SOAR run {}: {}", claim.runId(),
                            redactFreeText(failure.getMessage(), 2048));
                }
            }
        });
    }

    private SoarWorkflowRequest request(SoarDispatchState.Claim claim) {
        var run = claim.run();
        var version = versions.findByTenantIdAndId(claim.tenant(), run.getPlaybookVersionId())
                .orElseThrow(() -> new InvalidPayload("dispatch playbook version is missing"));
        JsonNode input = object(run.getInputJson(), "input");
        JsonNode definition = object(version.getDefinitionJson(), "definition");
        String resume = null;
        JsonNode soar = input.get("_soar");
        if (soar != null) {
            if (!soar.isObject()) throw new InvalidPayload("dispatch _soar must be an object");
            JsonNode node = soar.get("resumeFromNodeId");
            if (node != null && !node.isNull()) {
                if (!node.isTextual()) {
                    throw new InvalidPayload("dispatch resumeFromNodeId must be a string");
                }
                resume = node.asText().isBlank() ? null : node.asText();
            }
        }
        JsonNode limits = definition.get("limits");
        if (limits != null && !limits.isObject()) throw new InvalidPayload("dispatch limits must be an object");
        JsonNode budget = limits == null ? null : limits.get("maxNodeExecutions");
        if (budget != null && (!budget.isIntegralNumber() || !budget.canConvertToInt()
                || budget.intValue() < 1 || budget.intValue() > 500)) {
            throw new InvalidPayload("dispatch maxNodeExecutions must be an integer from 1 to 500");
        }
        return new SoarWorkflowRequest(claim.tenant(), run.getId(), version.getId(), version.getDefinitionJson(),
                run.getInputJson(), run.getExecutionSeriesId(), resume, true, "", null,
                budget == null ? 500 : budget.intValue(), 0);
    }

    private JsonNode object(String json, String label) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > SoarDefinitionValidator.MAX_BYTES) {
            throw new InvalidPayload("dispatch " + label + " is missing or exceeds 256 KiB");
        }
        try {
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(json);
            if (node == null || !node.isObject()) throw new InvalidPayload("dispatch " + label + " must be an object");
            return node;
        } catch (InvalidPayload invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new InvalidPayload("dispatch " + label + " is not valid JSON");
        }
    }

    private void fail(SoarDispatchState.Claim claim, RuntimeException failure) {
        state.fail(claim, redactFreeText(failure.getMessage(), 2048), false, Instant.now());
    }

    private static String redactFreeText(String value, int max) {
        if (value == null) return "dispatch failed";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static final class InvalidPayload extends IllegalArgumentException {
        InvalidPayload(String message) { super(message); }
    }
}
