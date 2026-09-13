package com.socp.soar.web.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
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
import com.socp.soar.web.connector.SecretResolver;
import com.socp.soar.web.artifact.SoarArtifactStore;
import com.socp.soar.web.temporal.request.SoarNodeRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Spring activity implementation; every side effect is tenant-scoped and durable. */
@Component
public class SoarActivityImpl implements SoarActivity {

    private final PlaybookExecutor executor;
    private final SoarRunRepository runs;
    private final SoarNodeRunRepository nodeRuns;
    private final SoarRunEventRepository events;
    private final SoarApprovalRepository approvals;
    private SoarApprovalDecisionRepository approvalDecisions;
    private final SoarActionAttemptRepository attempts;
    private final SoarConnectorRepository connectors;
    private final com.socp.soar.web.connector.SoarConnectorRegistry connectorRegistry;
    private final SecretResolver secretResolver;
    private final SoarManualTaskRepository manualTasks;
    private final PlaybookVersionRepository versions;
    private final ObjectMapper mapper;
    private SoarArtifactRepository artifacts;
    private SoarArtifactStore artifactStore;
    private final SoarActivityExecutionService executionService;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarActivityImpl(PlaybookExecutor executor, SoarRunRepository runs,
                              SoarNodeRunRepository nodeRuns, SoarRunEventRepository events,
                              SoarApprovalRepository approvals, SoarActionAttemptRepository attempts,
                              SoarConnectorRepository connectors,
                              com.socp.soar.web.connector.SoarConnectorRegistry connectorRegistry,
                              SecretResolver secretResolver, SoarManualTaskRepository manualTasks,
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
        this.executionService = new SoarActivityExecutionService(executor, runs, nodeRuns, events,
                attempts, connectors, connectorRegistry, secretResolver, mapper);
    }

    /** Compatibility constructor for isolated Activity tests. */
    public SoarActivityImpl(PlaybookExecutor executor, SoarRunRepository runs,
                              SoarNodeRunRepository nodeRuns, SoarRunEventRepository events,
                              SoarApprovalRepository approvals, SoarActionAttemptRepository attempts,
                              SoarConnectorRepository connectors,
                              com.socp.soar.web.connector.SoarConnectorRegistry connectorRegistry,
                              SecretResolver secretResolver, SoarManualTaskRepository manualTasks,
                              ObjectMapper mapper) {
        this(executor, runs, nodeRuns, events, approvals, attempts, connectors, connectorRegistry,
                secretResolver, manualTasks, null, mapper);
    }

    /** Optional for isolated activity tests; production wiring supplies the V11 artifact repository. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setArtifacts(SoarArtifactRepository artifacts) {
        this.artifacts = artifacts;
        this.executionService.setArtifacts(artifacts);
    }

    /** Optional in preview; production config supplies the S3-compatible store. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setArtifactStore(SoarArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
        this.executionService.setArtifactStore(artifactStore);
    }

    /** Optional for compatibility tests; production wiring records expiry votes. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApprovalDecisions(SoarApprovalDecisionRepository approvalDecisions) {
        this.approvalDecisions = approvalDecisions;
    }

    /** Optional for isolated unit tests; production wiring supplies JPA's manager. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.executionService.setTransactionManager(transactionManager);
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
            if (terminalProjection(run) || "CANCELLING".equals(run.getStatus())) return null;
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

    /**
     * Reserve a node slot under the run row lock.  Child workflows and
     * parallel branches deliberately share the same run id, so this is the
     * cross-workflow/cross-instance budget gate that a workflow-local counter
     * cannot provide.  A missing projection is a durable-integrity failure;
     * dispatched production runs must fail closed instead of spending a slot
     * that cannot be accounted for.
     */
    @Override
    @Transactional
    public boolean reserveNodeExecution(String tenantId, String runId, int budgetLimit) {
        if (budgetLimit <= 0) return true;
        return TenantContext.callWith(tenantId, () -> {
            java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(tenantId, runId);
            if (locked == null || locked.isEmpty()) {
                locked = runs.findByTenantIdAndId(tenantId, runId);
            }
            if (locked == null || locked.isEmpty()) {
                return false;
            }
            SoarRunEntity run = locked.get();
            if (terminalProjection(run) || "CANCELLING".equals(run.getStatus())) return false;
            int used = run.getExecutionNodeCount() == null ? 0 : run.getExecutionNodeCount();
            if (used >= budgetLimit) return false;
            run.setExecutionNodeCount(used + 1);
            run.setUpdatedAt(Instant.now());
            runs.save(run);
            return true;
        });
    }

    @Override
    public SoarNodeResult executeNode(SoarNodeRequest request) {
        return executionService.executeNode(request);
    }

    @Override
    public SoarNodeResult compensateNode(SoarNodeRequest request, String compensationRef) {
        return executionService.compensateNode(request, compensationRef);
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
        markRunWaitingWithContext(tenantId, runId, nodeId, timeoutSeconds, requiredApprovals,
                "", "", "{}");
    }

    @Override
    @Transactional
    public void markRunWaitingWithContext(String tenantId, String runId, String nodeId,
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
                    // Temporal may deliver the expiry timer after an operator
                    // decision or terminal run projection has already won the
                    // race. Never mutate the gate in that late-delivery case.
                    if (runTerminalOrCancelling(tenantId, runId)) return null;
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
                manualTasks.save(task);
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
                    if (runTerminalOrCancelling(tenantId, runId)) return;
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
    public void recordNode(SoarNodeRequest request, SoarNodeResult result) {
        TenantContext.callWith(request.tenantId(), () -> {
            if (runTerminalOrCancelling(request.tenantId(), request.runId())) return null;
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
            nodeRuns.save(row);
            appendEvent(request.tenantId(), request.runId(), "NODE_" + result.status(),
                    request.nodeId() + " completed", row.getId());
            return null;
        });
    }

    @Override
    @Transactional
    public void markRunCompleted(SoarRunUpdate update) {
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
            if (terminalProjection(run)
                    || ("CANCELLING".equals(run.getStatus())
                    && !"CANCELLED".equals(update.status()))
                    || ("ACTION_UNKNOWN".equals(run.getStatus())
                    && !"ACTION_UNKNOWN".equals(update.status()))) {
                appendEvent(update.tenantId(), update.runId(), "RUN_COMPLETION_IGNORED",
                        "Late Temporal completion ignored after operator terminal decision", null);
                return null;
            }
            String projectedStatus = update.status();
            String projectedErrorCode = update.errorCode();
            String projectedErrorMessage = update.errorMessage();
            if ("FAILED".equals(projectedStatus) && "SOAR_ACTIVITY_FAILURE".equals(projectedErrorCode)) {
                // The workflow caught an Activity failure. A durable RUNNING
                // attempt means the connector may have committed a side
                // effect before the worker lost the response; preserve that
                // uncertainty in the projection instead of exposing a blind
                // retryable FAILED result.
                boolean actionInFlight = attempts != null
                        && attempts.existsRunningByTenantIdAndRunId(update.tenantId(), update.runId());
                if (actionInFlight) {
                    projectedStatus = "ACTION_UNKNOWN";
                    projectedErrorCode = "SOAR_ACTION_RESULT_UNKNOWN";
                    projectedErrorMessage = "Activity failed while an action attempt remained RUNNING";
                }
            }
            run.setStatus(projectedStatus);
            run.setOutputJson(redactJson(update.outputJson()));
            run.setErrorCode(projectedErrorCode);
            run.setErrorMessage(redactFreeText(projectedErrorMessage, 2048));
            run.setCompletedAt(Instant.now());
            run.setUpdatedAt(Instant.now());
            runs.save(run);
            appendEvent(update.tenantId(), update.runId(), "RUN_" + projectedStatus,
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
        String status = run.getStatus();
        return (status != null && Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED",
                "SUPPRESSED", "TIMED_OUT", "DEAD").contains(status)) || operatorTerminalProjection(run);
    }

    private boolean runTerminalOrCancelling(String tenantId, String runId) {
        java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(tenantId, runId);
        if (locked == null) locked = runs.findByTenantIdAndId(tenantId, runId);
        if (locked == null || locked.isEmpty()) return false;
        SoarRunEntity run = locked.get();
        return terminalProjection(run) || "CANCELLING".equals(run.getStatus());
    }

    private SoarNodeResult terminalNodeResult() {
        return new SoarNodeResult("CANCELLED",
                "{\"status\":\"CANCELLED\",\"retryable\":false}",
                "SOAR_RUN_NOT_RESUMABLE", "run is already terminal or cancelling", false);
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
                } else {
                    result.put(key, redact(entry.getValue()));
                }
            }
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
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
