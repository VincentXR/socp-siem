package com.socp.soar.web.temporal.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.PlaybookActionStatus;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.service.PlaybookExecutor;
import com.socp.soar.web.service.SoarActionCatalog;
import com.socp.soar.web.connector.ActionRequest;
import com.socp.soar.web.connector.ActionResult;
import com.socp.soar.web.connector.ActionQuery;
import com.socp.soar.web.connector.ConnectionContext;
import com.socp.soar.web.connector.EnvironmentSecretResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Spring activity implementation; every side effect is tenant-scoped and durable. */
@Component
public class SoarV2ActivityImpl implements SoarV2Activity {

    private static final int INLINE_OUTPUT_LIMIT_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    private static final long ARTIFACT_RETENTION_DAYS = 30;

    private final PlaybookExecutor executor;
    private final SoarRunRepository runs;
    private final SoarNodeRunRepository nodeRuns;
    private final SoarRunEventRepository events;
    private final SoarApprovalRepository approvals;
    private SoarApprovalDecisionRepository approvalDecisions;
    private final SoarActionAttemptRepository attempts;
    private final SoarConnectorRepository connectors;
    private final com.socp.soar.web.connector.SoarConnectorRegistry connectorRegistry;
    private final EnvironmentSecretResolver secretResolver;
    private final SoarManualTaskRepository manualTasks;
    private final PlaybookVersionRepository versions;
    private final ObjectMapper mapper;
    private SoarArtifactRepository artifacts;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarV2ActivityImpl(PlaybookExecutor executor, SoarRunRepository runs,
                              SoarNodeRunRepository nodeRuns, SoarRunEventRepository events,
                              SoarApprovalRepository approvals, SoarActionAttemptRepository attempts,
                              SoarConnectorRepository connectors,
                              com.socp.soar.web.connector.SoarConnectorRegistry connectorRegistry,
                              EnvironmentSecretResolver secretResolver, SoarManualTaskRepository manualTasks,
                              PlaybookVersionRepository versions,
                              ObjectMapper mapper) {
        this.executor = executor;
        this.runs = runs;
        this.nodeRuns = nodeRuns;
        this.events = events;
        this.approvals = approvals;
        this.attempts = attempts;
        this.connectors = connectors;
        this.connectorRegistry = connectorRegistry;
        this.secretResolver = secretResolver;
        this.manualTasks = manualTasks;
        this.versions = versions;
        this.mapper = mapper;
    }

    /** Compatibility constructor for isolated Activity tests. */
    public SoarV2ActivityImpl(PlaybookExecutor executor, SoarRunRepository runs,
                              SoarNodeRunRepository nodeRuns, SoarRunEventRepository events,
                              SoarApprovalRepository approvals, SoarActionAttemptRepository attempts,
                              SoarConnectorRepository connectors,
                              com.socp.soar.web.connector.SoarConnectorRegistry connectorRegistry,
                              EnvironmentSecretResolver secretResolver, SoarManualTaskRepository manualTasks,
                              ObjectMapper mapper) {
        this(executor, runs, nodeRuns, events, approvals, attempts, connectors, connectorRegistry,
                secretResolver, manualTasks, null, mapper);
    }

    /** Optional for isolated activity tests; production wiring supplies the V11 artifact repository. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setArtifacts(SoarArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    /** Optional for compatibility tests; production wiring records expiry votes. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApprovalDecisions(SoarApprovalDecisionRepository approvalDecisions) {
        this.approvalDecisions = approvalDecisions;
    }

    @Override
    @Transactional
    public void markRunStarted(String tenantId, String runId) {
        TenantContext.callWith(tenantId, () -> {
            java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(tenantId, runId);
            if (locked == null) locked = runs.findByTenantIdAndId(tenantId, runId);
            SoarRunEntity run = (locked == null ? java.util.Optional.<SoarRunEntity>empty() : locked)
                    .orElseThrow(() -> new IllegalStateException("SOAR run not found: " + runId));
            // A stale Temporal command must not resurrect an operator-terminal
            // projection (cancel/discard/dead) after the database has already
            // committed that decision.
            if (terminalProjection(run)) return null;
            if (!"RUNNING".equals(run.getStatus())) {
                run.setStatus("RUNNING");
                run.setStartedAt(run.getStartedAt() == null ? Instant.now() : run.getStartedAt());
                run.setUpdatedAt(Instant.now());
                runs.save(run);
                appendEvent(tenantId, runId, "RUN_STARTED", "Temporal workflow started", null);
            }
            return null;
        });
    }

    @Override
    @Transactional
    public SoarV2NodeResult executeNode(SoarV2NodeRequest request) {
        return TenantContext.callWith(request.tenantId(), () -> {
            SoarNodeRunEntity prior = nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(
                    request.tenantId(), request.runId(), request.nodeId(),
                    request.iterationPath() == null ? "" : request.iterationPath()).orElse(null);
            if (prior != null && ("SUCCEEDED".equals(prior.getStatus())
                    || "CONFIRMED_SUCCEEDED".equals(prior.getStatus()))) {
                return new SoarV2NodeResult("SUCCEEDED", prior.getOutputJson(),
                        prior.getErrorCode(), prior.getErrorMessage());
            }
            Instant started = Instant.now();
            Map<String, Object> input = readMap(request.inputJson());
            Map<String, Object> output;
            ActionResult actionResult;
            String status;
            String errorCode = null;
            String errorMessage = null;
            // The deterministic workflow supplies the action-level attempt
            // number.  Falling back to the historical count keeps old
            // workflow histories/tests readable, while new executions no
            // longer have a read-then-insert race across SOAR instances.
            List<SoarActionAttemptEntity> priorAttempts = attempts
                    .findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(
                            request.tenantId(), nodeIdForAttempt(request));
            int attemptNo = request.attemptNo() > 0 ? request.attemptNo()
                    : (priorAttempts == null ? 0 : priorAttempts.size()) + 1;
            String nodeRunId = prior == null ? nodeIdForAttempt(request) : prior.getId();
            ConnectionContext attemptConnection = null;
            try {
                input.put("tenantId", request.tenantId());
                input.put("runId", request.runId());
                input.putIfAbsent("id", request.runId());
                input.putIfAbsent("playbookId", request.runId());
                String inputJson = writeJson(redact(input));
                // Resolve the connection before the attempt row so the attempt
                // and its node projection record which connection revision was
                // in effect.  A resolution failure is intentionally quiet here:
                // executeConnector() below re-checks and surfaces the error on
                // the attempt exactly as it did before.
                attemptConnection = quietConnection(request);
                recordAttemptStarted(request.tenantId(), nodeRunId, attemptNo, inputJson,
                        request.idempotencyKey(), attemptConnection);
                actionResult = executeConnector(request, input, nodeRunId, attemptNo);
                output = new LinkedHashMap<>();
                output.put("status", actionResult.status());
                if (actionResult.operationId() != null) output.put("operationId", actionResult.operationId());
                output.put("output", redact(actionResult.output()));
                output.put("receipt", redact(actionResult.receipt()));
                output.put("retryable", actionResult.retryable());
                if (actionResult.errorCode() != null) output.put("errorCode", actionResult.errorCode());
                if (actionResult.errorMessage() != null) output.put("error", redactFreeText(actionResult.errorMessage(), 2048));
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
            long outputBytes = outputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (outputBytes > MAX_OUTPUT_BYTES) {
                status = "FAILED";
                errorCode = "SOAR_OUTPUT_TOO_LARGE";
                errorMessage = "action output exceeds 10 MiB";
                output = boundedFailureOutput(errorCode, errorMessage);
                actionResult = ActionResult.failed(errorCode, errorMessage, false);
                outputJson = writeJson(output);
            } else if (outputBytes > INLINE_OUTPUT_LIMIT_BYTES) {
                if (artifacts == null) {
                    status = "FAILED";
                    errorCode = "SOAR_ARTIFACT_STORAGE_UNAVAILABLE";
                    errorMessage = "large action output has no artifact storage adapter";
                    output = boundedFailureOutput(errorCode, errorMessage);
                    actionResult = ActionResult.failed(errorCode, errorMessage, false);
                    outputJson = writeJson(output);
                } else {
                    SoarArtifactEntity artifact = persistArtifact(request, nodeRunId, outputJson, outputBytes);
                    output = new LinkedHashMap<>();
                    output.put("status", status);
                    output.put("retryable", actionResult.retryable());
                    output.put("artifact", artifactView(artifact));
                    output.put("outputTruncated", true);
                    if (actionResult.errorCode() != null) output.put("errorCode", actionResult.errorCode());
                    if (actionResult.errorMessage() != null) output.put("error", redactFreeText(actionResult.errorMessage(), 2048));
                    outputJson = writeJson(output);
                }
            }
            completeAttempt(request.tenantId(), nodeRunId, attemptNo, actionResult, output);
            SoarNodeRunEntity row = prior == null ? new SoarNodeRunEntity() : prior;
            if (row.getId() == null) row.setId(nodeIdForAttempt(request));
            row.setTenantId(request.tenantId());
            row.setRunId(request.runId());
            row.setNodeId(request.nodeId());
            row.setIterationPath(request.iterationPath() == null ? "" : request.iterationPath());
            row.setNodeType(request.nodeType());
            row.setStatus(status);
            row.setInputJson(writeJson(redact(readMap(request.inputJson()))));
            row.setOutputJson(outputJson);
            row.setIdempotencyKey(request.idempotencyKey());
            row.setConnectionId(request.connectionRef() == null || request.connectionRef().isBlank()
                    ? null : request.connectionRef());
            row.setConnectionRevision(attemptConnection == null ? null : attemptConnection.revision());
            row.setErrorCode(errorCode);
            row.setErrorMessage(errorMessage);
            row.setStartedAt(started);
            row.setCompletedAt(Instant.now());
            row.setUpdatedAt(Instant.now());
            if (row.getRowVersion() == null) row.setRowVersion(0L);
            nodeRuns.save(row);
            appendEvent(request.tenantId(), request.runId(), "NODE_" + status,
                    request.nodeId() + " completed", row.getId());
            boolean retryable = output.get("retryable") instanceof Boolean value && value;
            return new SoarV2NodeResult(status, outputJson, errorCode, errorMessage, retryable);
        });
    }

    @Override
    @Transactional
    public SoarV2NodeResult compensateNode(SoarV2NodeRequest request, String compensationRef) {
        return TenantContext.callWith(request.tenantId(), () -> {
            if (compensationRef == null || compensationRef.isBlank()) {
                return new SoarV2NodeResult("FAILED", "{}", "COMPENSATION_REF_REQUIRED",
                        "compensationRef is required", false);
            }
            if (connectorRegistry == null) {
                return new SoarV2NodeResult("FAILED", "{}", "COMPENSATION_UNAVAILABLE",
                        "connector registry is unavailable", false);
            }
            ActionResult result;
            try {
                ActionRequest primary = new ActionRequest(request.tenantId(), request.runId(),
                        request.nodeId(), 1, request.actionRef(), request.idempotencyKey(),
                        readMap(request.inputJson()), request.target(), connectionFor(request));
                result = connectorRegistry.compensate(primary, compensationRef).orElse(null);
            } catch (RuntimeException failure) {
                appendEvent(request.tenantId(), request.runId(), "ACTION_COMPENSATION_FAILED",
                        "Compensation connector could not be invoked", null);
                return new SoarV2NodeResult("FAILED", "{}", "COMPENSATION_FAILED",
                        redactFreeText(limit(failure.getMessage(), 2048), 2048), false);
            }
            if (result == null) {
                appendEvent(request.tenantId(), request.runId(), "ACTION_COMPENSATION_UNAVAILABLE",
                        "No connector compensation capability for " + compensationRef, null);
                return new SoarV2NodeResult("FAILED", "{}", "COMPENSATION_UNAVAILABLE",
                        "connector does not expose compensation", false);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", result.status());
            output.put("compensationRef", compensationRef);
            output.put("output", redact(result.output()));
            output.put("receipt", redact(result.receipt()));
            if (result.errorCode() != null) output.put("errorCode", result.errorCode());
            if (result.errorMessage() != null) output.put("error", redactFreeText(result.errorMessage(), 2048));
            appendEvent(request.tenantId(), request.runId(),
                    "ACTION_COMPENSATION_" + result.status(),
                    "Compensation action completed", null);
            return new SoarV2NodeResult(result.status(), writeJson(output), result.errorCode(),
                    redactFreeText(result.errorMessage(), 2048), result.retryable());
        });
    }

    private SoarArtifactEntity persistArtifact(SoarV2NodeRequest request, String nodeRunId,
                                               String redactedJson, long sizeBytes) {
        SoarArtifactEntity artifact = new SoarArtifactEntity();
        artifact.setId(UUID.randomUUID().toString().replace("-", ""));
        artifact.setTenantId(request.tenantId());
        artifact.setRunId(request.runId());
        artifact.setNodeRunId(nodeRunId);
        artifact.setMediaType("application/json");
        artifact.setSizeBytes(sizeBytes);
        artifact.setSha256(sha256(redactedJson));
        artifact.setStorageRef("db://soar-artifacts/" + artifact.getId());
        artifact.setClassification("INTERNAL");
        artifact.setInlineJson(redactedJson);
        artifact.setCreatedAt(Instant.now());
        artifact.setExpiresAt(Instant.now().plusSeconds(ARTIFACT_RETENTION_DAYS * 24 * 3600));
        return artifacts.save(artifact);
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

    private String nodeIdForAttempt(SoarV2NodeRequest request) {
        SoarNodeRunEntity existing = nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(
                request.tenantId(), request.runId(), request.nodeId(),
                request.iterationPath() == null ? "" : request.iterationPath()).orElse(null);
        return existing == null ? UUID.nameUUIDFromBytes((request.runId() + "\u0000" + request.nodeId()
                + "\u0000" + (request.iterationPath() == null ? "" : request.iterationPath()))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "") : existing.getId();
    }

    private ActionResult executeConnector(SoarV2NodeRequest request, Map<String, Object> input,
                                          String nodeRunId, int attemptNo) {
        ConnectionContext connection = connectionFor(request);
        ActionRequest actionRequest = new ActionRequest(request.tenantId(), request.runId(),
                nodeRunId, attemptNo, request.actionRef(), request.idempotencyKey(),
                input, request.target(), connection);
        ActionResult result = connectorRegistry.execute(actionRequest);
        if ("UNKNOWN".equalsIgnoreCase(result.status())) {
            // A transport timeout may happen after the vendor committed the
            // write. Give the connector one deterministic chance to prove the
            // outcome through its query API before exposing ACTION_UNKNOWN to
            // an operator. An empty Optional deliberately preserves UNKNOWN.
            result = connectorRegistry.reconcile(new ActionQuery(request.tenantId(), request.runId(),
                            nodeRunId, request.actionRef(), request.idempotencyKey(), request.target(), input))
                    .filter(candidate -> candidate != null
                            && ("SUCCEEDED".equalsIgnoreCase(candidate.status())
                            || "FAILED".equalsIgnoreCase(candidate.status())))
                    .orElse(result);
        }
        // Preserve the legacy adapter for old V2 drafts only. It is never a
        // production fallback for a namespaced action unknown to the registry.
        if ("SOAR_ACTION_NOT_FOUND".equals(result.errorCode()) && !SoarActionCatalog.isNamespaced(request.actionRef())) {
            Map<String, Object> legacy = executor.executeAction(SoarActionCatalog.toLegacyAction(request.actionRef()),
                    input, false, Math.abs(request.nodeId().hashCode()));
            String wire = String.valueOf(legacy.getOrDefault("status", "failed"));
            return new ActionResult(PlaybookActionStatus.isSuccessful(wire) ? "SUCCEEDED" : "FAILED",
                    String.valueOf(legacy.getOrDefault("operationId", "")), legacy,
                    false, String.valueOf(legacy.getOrDefault("errorCode", "")),
                    String.valueOf(legacy.getOrDefault("error", "")), null, legacy);
        }
        return result;
    }

    private ConnectionContext quietConnection(SoarV2NodeRequest request) {
        if (request.connectionRef() == null || request.connectionRef().isBlank()) return null;
        try {
            return connectionFor(request);
        } catch (RuntimeException ignored) {
            // The real execution path re-checks and reports the failure; here
            // we only want to know which connection revision to annotate.
            return null;
        }
    }

    private ConnectionContext connectionFor(SoarV2NodeRequest request) {
        if (request.connectionRef() == null || request.connectionRef().isBlank()) return null;
        {
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
            return new ConnectionContext(request.tenantId(), row.getId(),
                    row.getRevision() <= 0 ? 1 : row.getRevision(), row.getConnectorType(), row.getEndpoint(),
                    config, refs, secretResolver, java.time.Duration.ofSeconds(60), readList(row.getAllowedHostsJson()));
        }
    }

    private void recordAttemptStarted(String tenant, String nodeRunId, int attemptNo,
                                      String inputJson, String idempotencyKey,
                                      ConnectionContext connection) {
        // An Activity may be redelivered after the remote side effect has
        // happened but before the database transaction committed.  The
        // attempt business key is durable, so do not turn that redelivery
        // into a unique-key failure (or a second attempt number).
        java.util.Optional<SoarActionAttemptEntity> existing = attempts
                .findByTenantIdAndNodeRunIdAndAttemptNoForUpdate(tenant, nodeRunId, attemptNo);
        if (existing == null) {
            // Mockito/legacy isolated tests may not stub the lock projection;
            // production Spring Data always returns an Optional.
            existing = attempts.findByTenantIdAndNodeRunIdAndAttemptNo(tenant, nodeRunId, attemptNo);
        }
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

    private void completeAttempt(String tenant, String nodeRunId, int attemptNo,
                                 ActionResult action, Map<String, Object> output) {
        attempts.findByTenantIdAndNodeRunIdAndAttemptNo(tenant, nodeRunId, attemptNo).ifPresent(row -> {
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

    @SuppressWarnings("unchecked")
    private Map<String, String> readStringMap(String json) {
        try {
            Map<String, String> value = mapper.readValue(json == null ? "{}" : json, Map.class);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        } catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private List<String> readList(String json) {
        try { return mapper.readValue(json == null ? "[]" : json,
                mapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception ignored) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("secret") || lower.contains("token") || lower.contains("password")
                        || lower.contains("authorization") || lower.equals("cookie")) {
                    result.put(key, "[REDACTED]");
                } else result.put(key, redact(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception failure) { return Integer.toHexString(value.hashCode()); }
    }

    @Override
    @Transactional
    public void markRunWaiting(String tenantId, String runId, String nodeId) {
        markRunWaitingWithPolicy(tenantId, runId, nodeId, 24 * 3600L, 1);
    }

    @Override
    @Transactional
    public void markRunWaitingWithPolicy(String tenantId, String runId, String nodeId,
                                         long timeoutSeconds, int requiredApprovals) {
        markRunWaitingWithPolicyV2(tenantId, runId, nodeId, timeoutSeconds, requiredApprovals,
                "", "", "{}");
    }

    @Override
    @Transactional
    public void markRunWaitingWithPolicyV2(String tenantId, String runId, String nodeId,
                                           long timeoutSeconds, int requiredApprovals,
                                           String actionRef, String inputHash,
                                           String targetSnapshotJson) {
        TenantContext.callWith(tenantId, () -> {
            java.util.Optional<SoarRunEntity> lockedRun = runs.findByTenantIdAndIdForUpdate(tenantId, runId);
            if (lockedRun == null) lockedRun = runs.findByTenantIdAndId(tenantId, runId);
            SoarRunEntity run = (lockedRun == null ? java.util.Optional.<SoarRunEntity>empty() : lockedRun)
                    .orElseThrow(() -> new IllegalStateException("SOAR run not found: " + runId));
            if (terminalProjection(run) || "CANCELLING".equals(run.getStatus())) return null;
            run.setStatus("WAITING_APPROVAL");
            run.setUpdatedAt(Instant.now());
            runs.save(run);
            // A run may contain more than one explicit APPROVAL node. Bind
            // each gate to its node key so a pre-dispatch approval (or an
            // earlier approved node) cannot satisfy a later gate.
            String approvalKey = runId + ":node:" + nodeId;
            java.util.Optional<SoarApprovalEntity> existing = approvals
                    .findByTenantIdAndApprovalKeyForUpdate(tenantId, approvalKey);
            if (existing == null) {
                // Mockito/legacy isolated tests may not stub the lock query;
                // production Spring Data always returns an Optional.
                existing = approvals.findByTenantIdAndApprovalKey(tenantId, approvalKey);
            }
            if (existing == null || existing.isEmpty()) {
                SoarApprovalEntity approval = new SoarApprovalEntity();
                approval.setId(UUID.randomUUID().toString());
                approval.setTenantId(tenantId);
                approval.setRunId(runId);
                approval.setApprovalKey(approvalKey);
                approval.setNodeRunId(UUID.nameUUIDFromBytes((runId + "\u0000" + nodeId + "\u0000")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", ""));
                approval.setStatus("PENDING");
                approval.setRequestedBy(run.getRequestedBy() == null || run.getRequestedBy().isBlank()
                        ? "workflow" : limit(run.getRequestedBy(), 128));
                approval.setActionRef(limit(actionRef, 255));
                approval.setInputHash(limit(inputHash, 128));
                approval.setTargetSnapshotJson(redactJson(targetSnapshotJson));
                approval.setPolicyJson(approvalPolicyJson(targetSnapshotJson));
                approval.setReason("workflow human gate: " + nodeId);
                approval.setCreatedAt(Instant.now());
                long boundedTimeout = timeoutSeconds <= 0 ? 24 * 3600L
                        : Math.min(7 * 24 * 3600L, timeoutSeconds);
                approval.setRequiredApprovals(Math.max(1, Math.min(20, requiredApprovals)));
                approval.setExpiresAt(Instant.now().plusSeconds(boundedTimeout));
                approvals.save(approval);
            } else {
                SoarApprovalEntity approval = existing.get();
                approval.setRequiredApprovals(Math.max(1, Math.min(20, requiredApprovals)));
                // A pending legacy row may predate the context-aware Activity;
                // backfill it once, but never mutate an already-decided gate.
                if ("PENDING".equals(approval.getStatus())) {
                    if ((approval.getRequestedBy() == null || approval.getRequestedBy().isBlank()
                            || "workflow".equalsIgnoreCase(approval.getRequestedBy()))
                            && run.getRequestedBy() != null && !run.getRequestedBy().isBlank()) {
                        approval.setRequestedBy(limit(run.getRequestedBy(), 128));
                    }
                    if (approval.getActionRef() == null || approval.getActionRef().isBlank()) {
                        approval.setActionRef(limit(actionRef, 255));
                    }
                    if (approval.getInputHash() == null || approval.getInputHash().isBlank()) {
                        approval.setInputHash(limit(inputHash, 128));
                    }
                    if (approval.getTargetSnapshotJson() == null || approval.getTargetSnapshotJson().isBlank()) {
                        approval.setTargetSnapshotJson(redactJson(targetSnapshotJson));
                    }
                    if (approval.getPolicyJson() == null || approval.getPolicyJson().isBlank()) {
                        approval.setPolicyJson(approvalPolicyJson(targetSnapshotJson));
                    }
                }
                long boundedTimeout = timeoutSeconds <= 0 ? 24 * 3600L
                        : Math.min(7 * 24 * 3600L, timeoutSeconds);
                if ("PENDING".equals(approval.getStatus())) {
                    approval.setExpiresAt(Instant.now().plusSeconds(boundedTimeout));
                    approvals.save(approval);
                }
            }
            appendEvent(tenantId, runId, "RUN_WAITING_APPROVAL", "Workflow reached human gate: " + nodeId, null);
            return null;
        });
    }

    @Override
    @Transactional
    public void markApprovalExpired(String tenantId, String runId, String nodeId) {
        TenantContext.callWith(tenantId, () -> {
            String approvalKey = runId + ":node:" + nodeId;
            java.util.Optional<SoarApprovalEntity> found = approvals
                    .findByTenantIdAndApprovalKeyForUpdate(tenantId, approvalKey);
            if (found == null) found = approvals.findByTenantIdAndApprovalKey(tenantId, approvalKey);
            if (found != null && found.isPresent()) {
                SoarApprovalEntity approval = found.get();
                if ("PENDING".equals(approval.getStatus())) {
                    approval.setStatus("EXPIRED");
                    approval.setDecidedAt(Instant.now());
                    approval.setDecisionReason("approval expired by workflow timer");
                    recordApprovalDecision(tenantId, approval.getId(), "system", "EXPIRE",
                            "approval expired by workflow timer", approval.getDecidedAt());
                    approvals.save(approval);
                    appendEvent(tenantId, runId, "APPROVAL_EXPIRED",
                            "Approval expired at the published gate timeout", null);
                }
            }
            return null;
        });
    }

    @Override
    @Transactional
    public void markRunUnknown(String tenantId, String runId, String nodeId) {
        TenantContext.callWith(tenantId, () -> {
            java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(tenantId, runId);
            if (locked == null) locked = runs.findByTenantIdAndId(tenantId, runId);
            SoarRunEntity run = (locked == null ? java.util.Optional.<SoarRunEntity>empty() : locked)
                    .orElseThrow(() -> new IllegalStateException("SOAR run not found: " + runId));
            if (terminalProjection(run) || "CANCELLING".equals(run.getStatus())) return null;
            if (!"ACTION_UNKNOWN".equals(run.getStatus())) {
                run.setStatus("ACTION_UNKNOWN");
                run.setErrorCode("SOAR_ACTION_RESULT_UNKNOWN");
                run.setErrorMessage("connector result requires operator resolution: " + nodeId);
                run.setUpdatedAt(Instant.now());
                runs.save(run);
                appendEvent(tenantId, runId, "ACTION_UNKNOWN", "Connector result requires operator resolution", null);
            }
            return null;
        });
    }

    @Override
    @Transactional
    public void markManualTaskWaiting(String tenantId, String runId, String nodeId,
                                      String formSchemaJson, String assignee, String dueAt) {
        TenantContext.callWith(tenantId, () -> {
            java.util.Optional<SoarRunEntity> lockedRun = runs.findByTenantIdAndIdForUpdate(tenantId, runId);
            if (lockedRun == null) lockedRun = runs.findByTenantIdAndId(tenantId, runId);
            SoarRunEntity run = (lockedRun == null ? java.util.Optional.<SoarRunEntity>empty() : lockedRun)
                    .orElseThrow(() -> new IllegalStateException("SOAR run not found: " + runId));
            if (terminalProjection(run) || "CANCELLING".equals(run.getStatus())) return null;
            run.setStatus("WAITING_INPUT"); run.setUpdatedAt(Instant.now()); runs.save(run);
            java.util.Optional<SoarManualTaskEntity> existingTask = manualTasks
                    .findByTenantIdAndRunIdAndNodeIdForUpdate(tenantId, runId, nodeId);
            if (existingTask == null) {
                // Mockito/legacy isolated tests may not stub the lock query;
                // production Spring Data always returns an Optional.
                existingTask = manualTasks.findByTenantIdAndRunIdAndNodeId(tenantId, runId, nodeId);
            }
            if (existingTask == null || existingTask.isEmpty()) {
                SoarManualTaskEntity task = new SoarManualTaskEntity();
                task.setId(UUID.randomUUID().toString()); task.setTenantId(tenantId);
                task.setRunId(runId); task.setNodeId(nodeId);
                task.setFormSchemaJson(formSchemaJson == null || formSchemaJson.isBlank() ? "{\"type\":\"object\"}" : formSchemaJson);
                task.setAssignee(assignee == null || assignee.isBlank() ? null : assignee);
                try { task.setDueAt(dueAt == null || dueAt.isBlank() ? Instant.now().plusSeconds(86400) : Instant.parse(dueAt)); }
                catch (Exception ignored) { task.setDueAt(Instant.now().plusSeconds(86400)); }
                task.setStatus("PENDING"); task.setCreatedAt(Instant.now()); task.setUpdatedAt(Instant.now());
                task.setRowVersion(0L); manualTasks.save(task);
            }
            appendEvent(tenantId, runId, "RUN_WAITING_INPUT", "Workflow reached manual task: " + nodeId, null);
            return null;
        });
    }

    @Override
    @Transactional
    public void markManualTaskExpired(String tenantId, String runId, String nodeId) {
        TenantContext.callWith(tenantId, () -> {
            java.util.Optional<SoarManualTaskEntity> locked = manualTasks
                    .findByTenantIdAndRunIdAndNodeIdForUpdate(tenantId, runId, nodeId);
            if (locked == null) locked = manualTasks.findByTenantIdAndRunIdAndNodeId(tenantId, runId, nodeId);
            if (locked != null) locked.ifPresent(task -> {
                if ("PENDING".equals(task.getStatus())) {
                    task.setStatus("EXPIRED");
                    task.setUpdatedAt(Instant.now());
                    manualTasks.save(task);
                    appendEvent(tenantId, runId, "MANUAL_TASK_EXPIRED",
                            "Manual task expired without completion: " + nodeId, task.getId());
                }
            });
            return null;
        });
    }

    @Override
    @Transactional
    public void recordNode(SoarV2NodeRequest request, SoarV2NodeResult result) {
        TenantContext.callWith(request.tenantId(), () -> {
            String iteration = request.iterationPath() == null ? "" : request.iterationPath();
            SoarNodeRunEntity row = nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(
                    request.tenantId(), request.runId(), request.nodeId(), iteration).orElseGet(SoarNodeRunEntity::new);
            if (row.getId() == null) row.setId(UUID.nameUUIDFromBytes((request.runId() + "\u0000" + request.nodeId()
                    + "\u0000" + iteration).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", ""));
            row.setTenantId(request.tenantId()); row.setRunId(request.runId()); row.setNodeId(request.nodeId());
            row.setIterationPath(iteration); row.setNodeType(request.nodeType()); row.setStatus(result.status());
            row.setInputJson(writeJson(redact(readMap(request.inputJson())))); row.setOutputJson(redactJson(result.outputJson()));
            row.setIdempotencyKey(request.idempotencyKey()); row.setErrorCode(result.errorCode());
            row.setErrorMessage(redactFreeText(result.errorMessage(), 2048));
            if (row.getStartedAt() == null) row.setStartedAt(Instant.now());
            row.setCompletedAt(Instant.now()); row.setUpdatedAt(Instant.now());
            if (row.getRowVersion() == null) row.setRowVersion(0L); nodeRuns.save(row);
            appendEvent(request.tenantId(), request.runId(), "NODE_" + result.status(),
                    request.nodeId() + " completed", row.getId());
            return null;
        });
    }

    @Override
    @Transactional
    public void markRunCompleted(SoarV2RunUpdate update) {
        TenantContext.callWith(update.tenantId(), () -> {
            java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(
                    update.tenantId(), update.runId());
            if (locked == null) locked = runs.findByTenantIdAndId(update.tenantId(), update.runId());
            SoarRunEntity run = locked
                    .orElseThrow(() -> new IllegalStateException("SOAR run not found: " + update.runId()));
            // An operator may explicitly discard a dead dispatch/signal while
            // a stale Temporal completion is still in flight. Never let that
            // late completion resurrect a run that was deliberately made
            // terminal; this is the database-side fence for reconciliation.
            if (terminalProjection(run)) {
                appendEvent(update.tenantId(), update.runId(), "RUN_COMPLETION_IGNORED",
                        "Late Temporal completion ignored after operator terminal decision", null);
                return null;
            }
            run.setStatus(update.status());
            run.setOutputJson(redactJson(update.outputJson()));
            run.setErrorCode(update.errorCode());
            run.setErrorMessage(redactFreeText(update.errorMessage(), 2048));
            run.setCompletedAt(Instant.now());
            run.setUpdatedAt(Instant.now());
            runs.save(run);
            appendEvent(update.tenantId(), update.runId(), "RUN_" + update.status(),
                    "Temporal workflow completed", null);
            return null;
        });
    }

    private static boolean operatorTerminalProjection(SoarRunEntity run) {
        if (run == null) return false;
        String code = run.getErrorCode() == null ? "" : run.getErrorCode();
        return ("SUPPRESSED".equals(run.getStatus())
                && ("SIGNAL_DISCARDED".equals(code) || "DISPATCH_DISCARDED".equals(code)))
                || ("CANCELLED".equals(run.getStatus()) && "SOAR_RUN_CANCELLED".equals(code));
    }

    /** Any terminal projection is a one-way fence for stale Temporal calls. */
    private static boolean terminalProjection(SoarRunEntity run) {
        if (run == null) return false;
        return Set.of("SUCCEEDED", "FAILED", "CANCELLED", "SUPPRESSED", "TIMED_OUT", "DEAD")
                .contains(run.getStatus()) || operatorTerminalProjection(run);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolvePublishedDefinition(String tenantId, String versionId) {
        if (versions == null || tenantId == null || tenantId.isBlank()
                || versionId == null || versionId.isBlank()) {
            throw new IllegalStateException("SOAR_SUB_PLAYBOOK_UNAVAILABLE");
        }
        return TenantContext.callWith(tenantId, () -> versions.findByTenantIdAndId(tenantId, versionId)
                .filter(version -> "PUBLISHED".equals(version.getStatus()))
                .map(version -> {
                    String definition = version.getDefinitionJson();
                    if (definition == null || definition.isBlank()
                            || definition.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 * 1024) {
                        throw new IllegalStateException("SOAR_SUB_PLAYBOOK_DEFINITION_INVALID");
                    }
                    return definition;
                })
                .orElseThrow(() -> new IllegalStateException("SOAR_SUB_PLAYBOOK_NOT_FOUND")));
    }

    private void recordApprovalDecision(String tenantId, String approvalId, String actor,
                                        String decision, String reason, Instant createdAt) {
        if (approvalDecisions == null) return;
        SoarApprovalDecisionEntity vote = new SoarApprovalDecisionEntity();
        vote.setId(UUID.randomUUID().toString());
        vote.setTenantId(tenantId);
        vote.setApprovalId(approvalId);
        vote.setActorId(actor == null || actor.isBlank() ? "system" : limit(actor, 128));
        vote.setDecision(decision);
        vote.setReason(redactFreeText(reason, 2048));
        vote.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        approvalDecisions.save(vote);
    }

    private void appendEvent(String tenantId, String runId, String type, String summary, String nodeRunId) {
        // The event sequence is used as the SSE resume cursor.  Serialize
        // allocation on the run row before reading the tail; parallel
        // branches may finish in the same millisecond and must never collide
        // on uq_soar_run_event_sequence.
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
                    List<SoarRunEventEntity> legacyTail = events.findByTenantIdAndRunIdOrderBySequenceNoAsc(tenantId, runId);
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

    private String redactJson(String value) {
        try {
            Object parsed = mapper.readValue(value == null || value.isBlank() ? "{}" : value, Object.class);
            return writeJson(redact(parsed));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /** Extract the already-sanitized role/group allow-list from gate evidence. */
    private String approvalPolicyJson(String targetSnapshotJson) {
        try {
            Object parsed = mapper.readValue(targetSnapshotJson == null || targetSnapshotJson.isBlank()
                    ? "{}" : targetSnapshotJson, Object.class);
            if (!(parsed instanceof Map<?, ?> snapshot)) return null;
            Object policy = snapshot.get("approvalPolicy");
            if (!(policy instanceof Map<?, ?>)) return null;
            String encoded = writeJson(redact(policy));
            return encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8 * 1024
                    ? encoded : null;
        } catch (Exception ignored) {
            return null;
        }
    }

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
