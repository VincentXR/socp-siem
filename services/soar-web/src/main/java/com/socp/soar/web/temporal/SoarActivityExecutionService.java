package com.socp.soar.web.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.artifact.SoarArtifactStore;
import com.socp.soar.web.connector.ActionQuery;
import com.socp.soar.web.connector.ActionResult;
import com.socp.soar.web.connector.ConnectionContext;
import com.socp.soar.web.connector.SecretResolver;
import com.socp.soar.web.domain.PlaybookActionStatus;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.service.PlaybookExecutor;
import com.socp.soar.web.service.SoarActionCatalog;
import com.socp.soar.web.temporal.request.ActionRequest;
import com.socp.soar.web.temporal.request.SoarNodeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Executes connector actions and their compensation.  This collaborator is
 * deliberately separate from the projection activities: it owns the
 * three-stage lifecycle (prepare, remote I/O, finalize) and keeps the
 * transaction boundary visible in one place.
 */
final class SoarActivityExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SoarActivityExecutionService.class);
    private static final int INLINE_OUTPUT_LIMIT_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    private static final long ARTIFACT_RETENTION_DAYS = 30;

    private final PlaybookExecutor executor;
    private final SoarRunRepository runs;
    private final SoarNodeRunRepository nodeRuns;
    private final SoarRunEventRepository events;
    private final SoarActionAttemptRepository attempts;
    private final SoarConnectorRepository connectors;
    private final com.socp.soar.web.connector.SoarConnectorRegistry connectorRegistry;
    private final SecretResolver secretResolver;
    private final ObjectMapper mapper;
    private SoarArtifactRepository artifacts;
    private SoarArtifactStore artifactStore;
    private TransactionTemplate transactionTemplate;

    SoarActivityExecutionService(PlaybookExecutor executor, SoarRunRepository runs,
                                 SoarNodeRunRepository nodeRuns, SoarRunEventRepository events,
                                 SoarActionAttemptRepository attempts,
                                 SoarConnectorRepository connectors,
                                 com.socp.soar.web.connector.SoarConnectorRegistry connectorRegistry,
                                 SecretResolver secretResolver, ObjectMapper mapper) {
        this.executor = executor;
        this.runs = runs;
        this.nodeRuns = nodeRuns;
        this.events = events;
        this.attempts = attempts;
        this.connectors = connectors;
        this.connectorRegistry = connectorRegistry;
        this.secretResolver = secretResolver;
        this.mapper = mapper;
    }

    void setArtifacts(SoarArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    void setArtifactStore(SoarArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    void setTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            this.transactionTemplate = null;
            return;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = template;
    }

    SoarNodeResult executeNode(SoarNodeRequest request) {
        return TenantContext.callWith(request.tenantId(), () -> {
            PreparedNode prepared = prepareNodeExecution(request);
            if (prepared.earlyResult() != null) return prepared.earlyResult();

            Map<String, Object> input = prepared.input();
            Map<String, Object> output;
            ActionResult actionResult;
            String status;
            String errorCode = null;
            String errorMessage = null;
            if (prepared.replayedAttempt()) {
                // The remote side effect may have completed before an Activity
                // transaction was interrupted. Reuse the durable receipt for
                // the same attempt instead of invoking the connector again.
                SoarActionAttemptEntity priorAttempt = prepared.existingAttempt().orElseThrow();
                output = readMap(priorAttempt.getReceiptJson());
                status = normalizeAttemptStatus(priorAttempt.getStatus());
                errorCode = priorAttempt.getErrorCode();
                errorMessage = redactFreeText(priorAttempt.getErrorMessage(), 2048);
                actionResult = replayActionResult(priorAttempt, output);
            } else try {
                if (prepared.connectionFailure() != null) throw prepared.connectionFailure();
                // The attempt row committed in prepareNodeExecution(), so a
                // worker crash here is visible to recovery/reconciliation.
                actionResult = executeConnector(request, input, prepared.nodeRunId(),
                        prepared.attemptNo(), prepared.connection());
                output = actionOutput(actionResult);
                status = "SUCCEEDED".equals(actionResult.status()) ? "SUCCEEDED" : actionResult.status();
                if (!"SUCCEEDED".equals(status)) {
                    errorCode = actionResult.errorCode() == null ? "ACTION_FAILED" : actionResult.errorCode();
                    errorMessage = actionResult.errorMessage() == null ? "action failed"
                            : redactFreeText(actionResult.errorMessage(), 2048);
                }
            } catch (RuntimeException failure) {
                output = new LinkedHashMap<>();
                status = "FAILED";
                errorCode = failure.getMessage() != null
                        && failure.getMessage().startsWith("SOAR_CONNECTION_UNAVAILABLE")
                        ? "SOAR_CONNECTION_UNAVAILABLE" : "ACTION_EXCEPTION";
                errorMessage = redactFreeText(limit(failure.getMessage(), 2048), 2048);
                output.put("status", status);
                output.put("retryable", !"SOAR_CONNECTION_UNAVAILABLE".equals(errorCode));
                actionResult = ActionResult.failed(errorCode, errorMessage,
                        !"SOAR_CONNECTION_UNAVAILABLE".equals(errorCode));
            }

            String outputJson = writeJson(redact(output));
            long outputBytes = outputJson.getBytes(StandardCharsets.UTF_8).length;
            PendingArtifact pendingArtifact = null;
            if (!prepared.replayedAttempt() && outputBytes > MAX_OUTPUT_BYTES) {
                status = "FAILED";
                errorCode = "SOAR_OUTPUT_TOO_LARGE";
                errorMessage = "action output exceeds 10 MiB";
                output = boundedFailureOutput(errorCode, errorMessage);
                actionResult = ActionResult.failed(errorCode, errorMessage, false);
                outputJson = writeJson(output);
            } else if (!prepared.replayedAttempt() && outputBytes > INLINE_OUTPUT_LIMIT_BYTES) {
                if (artifacts == null || artifactStore == null) {
                    status = "FAILED";
                    errorCode = "SOAR_ARTIFACT_STORAGE_UNAVAILABLE";
                    errorMessage = "large action output has no artifact storage adapter";
                    output = boundedFailureOutput(errorCode, errorMessage);
                    actionResult = ActionResult.failed(errorCode, errorMessage, false);
                    outputJson = writeJson(output);
                } else {
                    try {
                        // Object-store I/O is outside the database transaction;
                        // metadata is attached in finalizeNode().
                        pendingArtifact = stageArtifact(request, prepared.nodeRunId(), outputJson, outputBytes);
                        output = new LinkedHashMap<>();
                        output.put("status", status);
                        output.put("retryable", actionResult.retryable());
                        output.put("artifact", artifactView(pendingArtifact.artifact()));
                        output.put("outputTruncated", true);
                        if (actionResult.errorCode() != null) output.put("errorCode", actionResult.errorCode());
                        if (actionResult.errorMessage() != null) {
                            output.put("error", redactFreeText(actionResult.errorMessage(), 2048));
                        }
                        outputJson = writeJson(output);
                    } catch (RuntimeException storageFailure) {
                        status = "FAILED";
                        errorCode = "SOAR_ARTIFACT_STORAGE_UNAVAILABLE";
                        errorMessage = "artifact storage could not persist action output";
                        output = boundedFailureOutput(errorCode, errorMessage);
                        actionResult = ActionResult.failed(errorCode, errorMessage, false);
                        outputJson = writeJson(output);
                    }
                }
            }

            NodeOutcome outcome = new NodeOutcome(prepared, status, errorCode, errorMessage,
                    outputJson, output, actionResult, pendingArtifact);
            try {
                return finalizeNode(request, outcome);
            } catch (RuntimeException failure) {
                cleanupStagedArtifact(pendingArtifact);
                throw failure;
            }
        });
    }

    SoarNodeResult compensateNode(SoarNodeRequest request, String compensationRef) {
        return TenantContext.callWith(request.tenantId(), () -> {
            if (inTransaction(() -> runTerminalOrCancelling(request.tenantId(), request.runId()))) {
                return terminalNodeResult();
            }
            if (compensationRef == null || compensationRef.isBlank()) {
                return new SoarNodeResult("FAILED", "{}", "COMPENSATION_REF_REQUIRED",
                        "compensationRef is required", false);
            }
            if (connectorRegistry == null) {
                return new SoarNodeResult("FAILED", "{}", "COMPENSATION_UNAVAILABLE",
                        "connector registry is unavailable", false);
            }
            ActionResult result;
            try {
                ActionRequest primary = new ActionRequest(request.tenantId(), request.runId(),
                        request.nodeId(), 1, request.actionRef(), request.idempotencyKey(),
                        readMap(request.inputJson()), request.target(), connectionFor(request));
                // No transaction is open while a compensation connector runs.
                result = connectorRegistry.compensate(primary, compensationRef).orElse(null);
            } catch (RuntimeException failure) {
                recordCompensationEvent(request, "ACTION_COMPENSATION_FAILED",
                        "Compensation connector could not be invoked");
                return new SoarNodeResult("FAILED", "{}", "COMPENSATION_FAILED",
                        redactFreeText(limit(failure.getMessage(), 2048), 2048), false);
            }
            if (result == null) {
                recordCompensationEvent(request, "ACTION_COMPENSATION_UNAVAILABLE",
                        "No connector compensation capability for " + compensationRef);
                return new SoarNodeResult("FAILED", "{}", "COMPENSATION_UNAVAILABLE",
                        "connector does not expose compensation", false);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", result.status());
            output.put("compensationRef", compensationRef);
            output.put("output", redact(result.output()));
            output.put("receipt", redact(result.receipt()));
            if (result.errorCode() != null) output.put("errorCode", result.errorCode());
            if (result.errorMessage() != null) output.put("error", redactFreeText(result.errorMessage(), 2048));
            recordCompensationEvent(request, "ACTION_COMPENSATION_" + result.status(),
                    "Compensation action completed");
            return new SoarNodeResult(result.status(), writeJson(output), result.errorCode(),
                    redactFreeText(result.errorMessage(), 2048), result.retryable());
        });
    }

    private record PreparedNode(SoarNodeResult earlyResult, String nodeRunId, int attemptNo,
                                java.util.Optional<SoarActionAttemptEntity> existingAttempt,
                                boolean replayedAttempt, ConnectionContext connection,
                                RuntimeException connectionFailure, Instant started,
                                Map<String, Object> input) { }

    private record PendingArtifact(SoarArtifactEntity artifact, String storageRef) { }

    private record NodeOutcome(PreparedNode prepared, String status, String errorCode,
                               String errorMessage, String outputJson, Map<String, Object> output,
                               ActionResult actionResult, PendingArtifact pendingArtifact) { }

    /** Short transaction before the connector call. */
    private PreparedNode prepareNodeExecution(SoarNodeRequest request) {
        return inTransaction(() -> {
            String iteration = request.iterationPath() == null ? "" : request.iterationPath();
            SoarNodeRunEntity prior = nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(
                    request.tenantId(), request.runId(), request.nodeId(), iteration).orElse(null);
            if (prior != null && ("SUCCEEDED".equals(prior.getStatus())
                    || "CONFIRMED_SUCCEEDED".equals(prior.getStatus()))) {
                return new PreparedNode(new SoarNodeResult("SUCCEEDED", prior.getOutputJson(),
                        prior.getErrorCode(), prior.getErrorMessage()), prior.getId(), 0,
                        java.util.Optional.empty(), false, null, null, prior.getStartedAt(), Map.of());
            }
            if (runTerminalOrCancelling(request.tenantId(), request.runId())) {
                return new PreparedNode(terminalNodeResult(), null, 0, java.util.Optional.empty(),
                        false, null, null, Instant.now(), Map.of());
            }
            Instant started = Instant.now();
            Map<String, Object> input = readMap(request.inputJson());
            input.put("tenantId", request.tenantId());
            input.put("runId", request.runId());
            input.putIfAbsent("id", request.runId());
            input.putIfAbsent("playbookId", request.runId());
            String nodeRunId = prior == null ? nodeIdForAttempt(request) : prior.getId();
            List<SoarActionAttemptEntity> priorAttempts = attempts
                    .findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(request.tenantId(), nodeRunId);
            int attemptNo = request.attemptNo() > 0 ? request.attemptNo()
                    : (priorAttempts == null ? 0 : priorAttempts.size()) + 1;
            java.util.Optional<SoarActionAttemptEntity> existingAttempt = findAttemptForUpdate(
                    request.tenantId(), nodeRunId, attemptNo);
            boolean replayedAttempt = existingAttempt.isPresent()
                    && completedAttempt(existingAttempt.get());
            if (replayedAttempt) {
                return new PreparedNode(null, nodeRunId, attemptNo, existingAttempt, true,
                        null, null, started, input);
            }

            ConnectionContext connection = null;
            RuntimeException connectionFailure = null;
            try {
                connection = connectionFor(request);
            } catch (RuntimeException failure) {
                connectionFailure = failure;
            }
            recordAttemptStarted(request.tenantId(), nodeRunId, attemptNo,
                    writeJson(redact(input)), request.idempotencyKey(), connection);
            return new PreparedNode(null, nodeRunId, attemptNo, existingAttempt, false,
                    connection, connectionFailure, started, input);
        });
    }

    private Map<String, Object> actionOutput(ActionResult actionResult) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", actionResult.status());
        if (actionResult.operationId() != null) output.put("operationId", actionResult.operationId());
        output.put("output", redact(actionResult.output()));
        output.put("receipt", redact(actionResult.receipt()));
        output.put("retryable", actionResult.retryable());
        if (actionResult.errorCode() != null) output.put("errorCode", actionResult.errorCode());
        if (actionResult.errorMessage() != null) output.put("error", redactFreeText(actionResult.errorMessage(), 2048));
        return output;
    }

    /** Short transaction after remote I/O; no connector call is made here. */
    private SoarNodeResult finalizeNode(SoarNodeRequest request, NodeOutcome outcome) {
        return inTransaction(() -> {
            PendingArtifact pending = outcome.pendingArtifact();
            if (pending != null) {
                SoarArtifactEntity saved = artifacts.save(pending.artifact());
                if (saved == null) throw new IllegalStateException("artifact metadata save returned no row");
            }
            if (!outcome.prepared().replayedAttempt()) {
                completeAttempt(request.tenantId(), outcome.prepared().nodeRunId(),
                        outcome.prepared().attemptNo(), outcome.actionResult(), outcome.output());
            }
            String iteration = request.iterationPath() == null ? "" : request.iterationPath();
            SoarNodeRunEntity row = nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(
                    request.tenantId(), request.runId(), request.nodeId(), iteration)
                    .orElseGet(SoarNodeRunEntity::new);
            if (row.getId() == null) row.setId(outcome.prepared().nodeRunId());
            row.setTenantId(request.tenantId());
            row.setRunId(request.runId());
            row.setNodeId(request.nodeId());
            row.setIterationPath(iteration);
            row.setNodeType(request.nodeType());
            row.setStatus(outcome.status());
            row.setInputJson(writeJson(redact(readMap(request.inputJson()))));
            row.setOutputJson(outcome.outputJson());
            row.setIdempotencyKey(request.idempotencyKey());
            row.setConnectionId(request.connectionRef() == null || request.connectionRef().isBlank()
                    ? null : request.connectionRef());
            Integer connectionRevision;
            if (outcome.prepared().connection() == null) {
                connectionRevision = outcome.prepared().existingAttempt()
                        .map(SoarActionAttemptEntity::getConnectionRevision).orElse((Integer) null);
            } else {
                connectionRevision = outcome.prepared().connection().revision();
            }
            row.setConnectionRevision(connectionRevision);
            row.setErrorCode(outcome.errorCode());
            row.setErrorMessage(outcome.errorMessage());
            row.setStartedAt(outcome.prepared().started());
            row.setCompletedAt(Instant.now());
            row.setUpdatedAt(Instant.now());
            nodeRuns.save(row);
            appendEvent(request.tenantId(), request.runId(), "NODE_" + outcome.status(),
                    request.nodeId() + " completed", row.getId());
            boolean retryable = outcome.output().get("retryable") instanceof Boolean value && value;
            return new SoarNodeResult(outcome.status(), outcome.outputJson(), outcome.errorCode(),
                    outcome.errorMessage(), retryable);
        });
    }

    private void recordCompensationEvent(SoarNodeRequest request, String type, String summary) {
        inTransaction(() -> {
            appendEvent(request.tenantId(), request.runId(), type, summary, null);
            return null;
        });
    }

    private PendingArtifact stageArtifact(SoarNodeRequest request, String nodeRunId,
                                          String redactedJson, long sizeBytes) {
        SoarArtifactEntity artifact = new SoarArtifactEntity();
        artifact.setId(UUID.randomUUID().toString().replace("-", ""));
        artifact.setTenantId(request.tenantId());
        artifact.setRunId(request.runId());
        artifact.setNodeRunId(nodeRunId);
        artifact.setMediaType("application/json");
        byte[] content = redactedJson.getBytes(StandardCharsets.UTF_8);
        SoarArtifactStore.StoredArtifact external = null;
        try {
            external = artifactStore.put(request.tenantId(), request.runId(), artifact.getId(),
                    "application/json", content);
            if (external == null) throw new IllegalStateException("artifact store returned no object");
            artifact.setSizeBytes(external.sizeBytes() <= 0 ? sizeBytes : external.sizeBytes());
            artifact.setSha256(external.sha256() == null ? sha256(redactedJson) : external.sha256());
            artifact.setStorageRef(external.storageRef());
            artifact.setClassification("INTERNAL");
            artifact.setInlineJson(null);
            artifact.setCreatedAt(Instant.now());
            artifact.setExpiresAt(Instant.now().plusSeconds(ARTIFACT_RETENTION_DAYS * 24 * 3600));
            return new PendingArtifact(artifact, external.storageRef());
        } catch (RuntimeException failure) {
            if (external != null && artifactStore != null && external.storageRef() != null) {
                try {
                    artifactStore.delete(external.storageRef());
                } catch (RuntimeException cleanupFailure) {
                    log.warn("SOAR artifact orphan cleanup deferred after activity metadata failure");
                }
            }
            throw failure;
        }
    }

    private void cleanupStagedArtifact(PendingArtifact pending) {
        if (pending == null || artifactStore == null || pending.storageRef() == null
                || pending.storageRef().isBlank()) return;
        try {
            artifactStore.delete(pending.storageRef());
        } catch (RuntimeException cleanupFailure) {
            log.warn("SOAR artifact orphan cleanup deferred after activity metadata failure");
        }
    }

    private Map<String, Object> boundedFailureOutput(String code, String message) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "FAILED");
        output.put("retryable", false);
        output.put("errorCode", code);
        output.put("error", message);
        return output;
    }

    private Map<String, Object> artifactView(SoarArtifactEntity artifact) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", artifact.getId());
        view.put("mediaType", artifact.getMediaType());
        view.put("sizeBytes", artifact.getSizeBytes());
        view.put("sha256", artifact.getSha256());
        view.put("storageRef", artifact.getStorageRef());
        view.put("classification", artifact.getClassification());
        view.put("expiresAt", artifact.getExpiresAt());
        return view;
    }

    private String nodeIdForAttempt(SoarNodeRequest request) {
        SoarNodeRunEntity existing = nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(
                request.tenantId(), request.runId(), request.nodeId(),
                request.iterationPath() == null ? "" : request.iterationPath()).orElse(null);
        return existing == null ? UUID.nameUUIDFromBytes((request.runId() + "\u0000" + request.nodeId()
                + "\u0000" + (request.iterationPath() == null ? "" : request.iterationPath()))
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "") : existing.getId();
    }

    private ActionResult executeConnector(SoarNodeRequest request, Map<String, Object> input,
                                          String nodeRunId, int attemptNo,
                                          ConnectionContext connection) {
        ActionRequest actionRequest = new ActionRequest(request.tenantId(), request.runId(),
                nodeRunId, attemptNo, request.actionRef(), request.idempotencyKey(),
                input, request.target(), connection);
        ActionResult result = connectorRegistry.execute(actionRequest);
        if ("UNKNOWN".equalsIgnoreCase(result.status())) {
            result = connectorRegistry.reconcile(new ActionQuery(request.tenantId(), request.runId(),
                            nodeRunId, request.actionRef(), request.idempotencyKey(), request.target(), input))
                    .filter(candidate -> candidate != null
                            && ("SUCCEEDED".equalsIgnoreCase(candidate.status())
                            || "FAILED".equalsIgnoreCase(candidate.status())))
                    .orElse(result);
        }
        if ("SOAR_ACTION_NOT_FOUND".equals(result.errorCode())
                && !SoarActionCatalog.isNamespaced(request.actionRef())) {
            Map<String, Object> legacy = executor.executeAction(SoarActionCatalog.toLegacyAction(request.actionRef()),
                    input, false, request.nodeId().hashCode() & Integer.MAX_VALUE);
            String wire = String.valueOf(legacy.getOrDefault("status", "failed"));
            return new ActionResult(PlaybookActionStatus.isSuccessful(wire) ? "SUCCEEDED" : "FAILED",
                    String.valueOf(legacy.getOrDefault("operationId", "")), legacy,
                    false, String.valueOf(legacy.getOrDefault("errorCode", "")),
                    String.valueOf(legacy.getOrDefault("error", "")), null, legacy);
        }
        return result;
    }

    private ConnectionContext connectionFor(SoarNodeRequest request) {
        if (request.connectionRef() == null || request.connectionRef().isBlank()) return null;
        SoarConnectorEntity row = connectors.findByTenantIdAndId(request.tenantId(), request.connectionRef())
                .orElseThrow(() -> new IllegalStateException("SOAR_CONNECTION_UNAVAILABLE"));
        if (!row.isEnabled() || row.getDeletedAt() != null) {
            throw new IllegalStateException("SOAR_CONNECTION_UNAVAILABLE: connection is disabled");
        }
        Map<String, Object> config = readMap(row.getConfigJson());
        Map<String, String> refs = readStringMap(row.getSecretRefsJson());
        if (row.getAuthSecretRef() != null && !row.getAuthSecretRef().isBlank()) {
            refs.putIfAbsent("auth", row.getAuthSecretRef());
        }
        return new ConnectionContext(request.tenantId(), row.getId(), row.getRevision() <= 0 ? 1 : row.getRevision(),
                row.getConnectorType(), row.getEndpoint(), config, refs, secretResolver,
                java.time.Duration.ofSeconds(60), readList(row.getAllowedHostsJson()));
    }

    private void recordAttemptStarted(String tenant, String nodeRunId, int attemptNo,
                                      String inputJson, String idempotencyKey,
                                      ConnectionContext connection) {
        java.util.Optional<SoarActionAttemptEntity> existing = findAttemptForUpdate(tenant, nodeRunId, attemptNo);
        if (existing != null && existing.isPresent()) return;
        SoarActionAttemptEntity row = new SoarActionAttemptEntity();
        row.setId(UUID.randomUUID().toString().replace("-", ""));
        row.setTenantId(tenant);
        row.setNodeRunId(nodeRunId);
        row.setAttemptNo(attemptNo);
        row.setStatus("RUNNING");
        row.setRequestHash(sha256(inputJson + "\u0000" + idempotencyKey));
        row.setConnectionId(connection == null ? null : connection.connectionId());
        row.setConnectionRevision(connection == null ? null : connection.revision());
        row.setRetryable(false);
        row.setStartedAt(Instant.now());
        row.setCreatedAt(Instant.now());
        attempts.save(row);
    }

    private java.util.Optional<SoarActionAttemptEntity> findAttemptForUpdate(
            String tenant, String nodeRunId, int attemptNo) {
        java.util.Optional<SoarActionAttemptEntity> existing = attempts
                .findByTenantIdAndNodeRunIdAndAttemptNoForUpdate(tenant, nodeRunId, attemptNo);
        if (existing == null || existing.isEmpty()) {
            existing = attempts.findByTenantIdAndNodeRunIdAndAttemptNo(tenant, nodeRunId, attemptNo);
        }
        return existing == null ? java.util.Optional.empty() : existing;
    }

    private static boolean completedAttempt(SoarActionAttemptEntity attempt) {
        String status = attempt == null ? null : attempt.getStatus();
        return status != null && !status.isBlank() && !"RUNNING".equalsIgnoreCase(status);
    }

    private static String normalizeAttemptStatus(String status) {
        if (status == null || status.isBlank()) return "FAILED";
        return status.toUpperCase(java.util.Locale.ROOT);
    }

    private ActionResult replayActionResult(SoarActionAttemptEntity attempt, Map<String, Object> output) {
        String status = normalizeAttemptStatus(attempt.getStatus());
        String operationId = attempt.getRemoteOperationId();
        Map<String, Object> nestedOutput = objectMap(output.get("output"));
        Map<String, Object> receipt = objectMap(output.get("receipt"));
        if ("SUCCEEDED".equals(status)) return ActionResult.success(operationId, nestedOutput, receipt);
        if ("UNKNOWN".equals(status) || "ACTION_UNKNOWN".equals(status)) {
            return ActionResult.unknown(attempt.getErrorCode(), attempt.getErrorMessage());
        }
        return ActionResult.failed(attempt.getErrorCode(), attempt.getErrorMessage(), attempt.isRetryable());
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private void completeAttempt(String tenant, String nodeRunId, int attemptNo,
                                 ActionResult action, Map<String, Object> output) {
        findAttemptForUpdate(tenant, nodeRunId, attemptNo).ifPresent(row -> {
            row.setStatus(action.status());
            row.setRemoteOperationId(action.operationId());
            row.setRemoteTime(action.remoteTime());
            row.setReceiptJson(writeJson(redact(output)));
            row.setErrorCode(action.errorCode());
            row.setErrorMessage(redactFreeText(action.errorMessage(), 2048));
            row.setRetryable(action.retryable());
            row.setCompletedAt(Instant.now());
            attempts.save(row);
        });
    }

    private Map<String, String> readStringMap(String json) {
        try {
            Map<String, String> value = mapper.readValue(json == null ? "{}" : json, Map.class);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> readList(String json) {
        try {
            return mapper.readValue(json == null ? "[]" : json,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("secret") || lower.contains("token") || lower.contains("password")
                        || lower.contains("authorization") || lower.equals("cookie")) {
                    result.put(key, "[REDACTED]");
                } else {
                    result.put(key, redact(entry.getValue()));
                }
            }
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception failure) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private boolean runTerminalOrCancelling(String tenantId, String runId) {
        java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(tenantId, runId);
        if (locked == null) locked = runs.findByTenantIdAndId(tenantId, runId);
        if (locked == null || locked.isEmpty()) return false;
        SoarRunEntity run = locked.get();
        return terminalProjection(run) || "CANCELLING".equals(run.getStatus());
    }

    private SoarNodeResult terminalNodeResult() {
        return new SoarNodeResult("CANCELLED", "{\"status\":\"CANCELLED\",\"retryable\":false}",
                "SOAR_RUN_NOT_RESUMABLE", "run is already terminal or cancelling", false);
    }

    private static boolean terminalProjection(SoarRunEntity run) {
        if (run == null) return false;
        String status = run.getStatus();
        String code = run.getErrorCode() == null ? "" : run.getErrorCode();
        boolean operatorTerminal = ("SUPPRESSED".equals(status)
                && ("SIGNAL_DISCARDED".equals(code) || "DISPATCH_DISCARDED".equals(code)))
                || ("CANCELLED".equals(status) && "SOAR_RUN_CANCELLED".equals(code));
        return (status != null && Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED",
                "SUPPRESSED", "TIMED_OUT", "DEAD").contains(status)) || operatorTerminal;
    }

    private void appendEvent(String tenantId, String runId, String type, String summary, String nodeRunId) {
        java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(tenantId, runId);
        if (locked == null) locked = runs.findByTenantIdAndId(tenantId, runId);
        (locked == null ? java.util.Optional.<SoarRunEntity>empty() : locked)
                .orElseThrow(() -> new IllegalStateException("SOAR run not found: " + runId));
        SoarRunEventEntity event = new SoarRunEventEntity();
        event.setId(UUID.randomUUID().toString().replace("-", ""));
        event.setTenantId(tenantId);
        event.setRunId(runId);
        event.setNodeRunId(nodeRunId);
        long previousSequence = events.findTopByTenantIdAndRunIdOrderBySequenceNoDesc(tenantId, runId)
                .map(SoarRunEventEntity::getSequenceNo)
                .orElseGet(() -> {
                    List<SoarRunEventEntity> legacyTail = events.findByTenantIdAndRunIdOrderBySequenceNoAsc(
                            tenantId, runId);
                    return legacyTail.isEmpty() ? 0L : legacyTail.get(legacyTail.size() - 1).getSequenceNo();
                });
        event.setSequenceNo(previousSequence + 1L);
        event.setEventType(type);
        event.setActor("temporal");
        event.setSummary(summary);
        event.setDetailJson("{}");
        event.setCreatedAt(Instant.now());
        events.save(event);
    }

    private <T> T inTransaction(Supplier<T> operation) {
        if (transactionTemplate == null) return operation.get();
        return transactionTemplate.execute(status -> operation.get());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            Map<String, Object> value = mapper.readValue(json == null ? "{}" : json, Map.class);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static String redactFreeText(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static String limit(String value, int max) {
        if (value == null) return "action execution failed";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
