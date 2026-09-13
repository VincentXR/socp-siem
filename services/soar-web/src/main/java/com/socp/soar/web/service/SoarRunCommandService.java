package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.domain.SoarPlaybookVersionStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


/**
 * Run admission, retry, manual-task and cancellation commands extracted from
 * {@link SoarService}.  The façade retains the public transaction/audit
 * annotations while this class owns run state transitions.
 */
final class SoarRunCommandService {

    private final SoarService service;
    private final SoarRunRepository runs;
    private final SoarPlaybookRepository playbooks;
    private final PlaybookVersionRepository versions;
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarNodeRunRepository nodes;
    private final SoarApprovalRepository approvals;
    private final SoarManualTaskRepository manualTasks;
    private final SoarDefinitionValidator validator;
    private final SoarManualInputValidator manualInputValidator;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    SoarRunCommandService(SoarService service) {
        this.service = service;
        this.runs = service.runs;
        this.playbooks = service.playbooks;
        this.versions = service.versions;
        this.dispatches = service.dispatches;
        this.nodes = service.nodes;
        this.approvals = service.approvals;
        this.manualTasks = service.manualTasks;
        this.validator = service.validator;
        this.manualInputValidator = service.manualInputValidator;
        this.mapper = service.mapper;
    }

    private String tenant() { return service.tenant(); }
    private void requireExecution(String tenant) { service.requireExecution(tenant); }
    private static String actor() { return SoarService.actor(); }
    private static String required(String value, String field, int max) {
        return SoarService.required(value, field, max);
    }
    private static String limit(String value, int max) { return SoarService.limit(value, max); }
    private static ResponseStatusException error(HttpStatus status, String code, String message) {
        return SoarService.error(status, code, message);
    }
    private String write(Object value) { return service.write(value); }
    private Object redact(Object value) { return service.redact(value); }
    private static String redactFreeText(String value, int max) {
        return SoarService.redactFreeText(value, max);
    }
    private Map<String, Object> runView(SoarRunEntity run) { return service.runView(run); }
    private Map<String, Object> nodeView(SoarNodeRunEntity node) { return service.nodeView(node); }
    private Map<String, Object> manualTaskView(SoarManualTaskEntity task) { return service.manualTaskView(task); }
    private void appendEvent(String runId, String type, String actor, String summary, Map<String, Object> detail) {
        service.appendEvent(runId, type, actor, summary, detail);
    }
    private void enqueueSignal(SoarRunEntity run, String type, Map<String, Object> payload) {
        service.enqueueSignal(run, type, payload);
    }
    private SoarRunEntity run(String id) { return service.run(id); }
    private void validateConnections(String definitionJson, String tenant) {
        service.validateConnections(definitionJson, tenant);
    }
    private void validateSubPlaybookGraph(String tenant, PlaybookVersionEntity version) {
        service.validateSubPlaybookGraph(tenant, version);
    }
    private boolean terminalRunProjection(SoarRunEntity run) {
        return SoarService.terminalRunProjection(run);
    }
    private Map<String, Object> readMap(String json) { return service.readMap(json); }
    private Map<String, Object> castObjectMap(Map<?, ?> value) { return SoarService.castObjectMap(value); }
    private String shortHash(String value) { return service.shortHash(value); }
    private String approvalPolicyJson(String snapshot) { return service.approvalPolicyJson(snapshot); }
    private ApprovalContext buildApprovalContext(String definitionJson, String inputJson) {
        SoarService.ApprovalContext value = service.buildApprovalContext(definitionJson, inputJson);
        return new ApprovalContext(value.actionRef(), value.inputHash(), value.targetSnapshotJson());
    }
    private String text(Map<String, Object> map, String key) { return SoarService.text(map, key); }

    private record ApprovalContext(String actionRef, String inputHash, String targetSnapshotJson) {
        private static ApprovalContext empty(String inputJson) {
            return new ApprovalContext("", serviceSha256(inputJson == null ? "" : inputJson), "{}");
        }
        private static String serviceSha256(String value) {
            return SoarService.sha256(value);
        }
    }

    public Map<String, Object> queueManualRun(String requestId, String versionId, Map<String, Object> subject,
                                              Map<String, Object> inputs) {
        String tenant = tenant();
        requestId = required(requestId, "requestId", 128);
        SoarRunEntity existingEntity = runs.findByTenantIdAndRequestId(tenant, requestId).orElse(null);
        if (existingEntity != null) {
            // An idempotency key is bound to the complete immutable request,
            // not merely to a tenant.  Silently returning an old Run when a
            // caller accidentally reuses the key for another version/input
            // would make a retry look successful while executing the wrong
            // response plan.  Keep the duplicate path cheap, but reject key
            // reuse with a different payload explicitly.
            if (!idempotencyRequestMatches(existingEntity, versionId, subject, inputs)) {
                throw error(HttpStatus.CONFLICT, "SOAR_IDEMPOTENCY_KEY_REUSED",
                        "requestId is already bound to a different run request");
            }
            Map<String, Object> existing = runView(existingEntity);
            existing.put("duplicate", true);
            return existing;
        }
        requireExecution(tenant);
        PlaybookVersionEntity version = versions.findByTenantIdAndId(tenant, versionId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "published version not found"));
        if (!SoarPlaybookVersionStatus.PUBLISHED.name().equals(version.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_VERSION_NOT_PUBLISHED", "run requires a published version");
        }
        SoarPlaybookEntity playbook = playbooks.findByTenantIdAndId(tenant, version.getPlaybookId())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
        if (!"ACTIVE".equalsIgnoreCase(playbook.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_PLAYBOOK_ARCHIVED", "archived playbooks cannot start new runs");
        }
        DefinitionValidationResult checked = validator.validate(version.getDefinitionJson());
        if (!checked.valid()) {
            throw error(HttpStatus.CONFLICT, "SOAR_DEFINITION_INVALID",
                    "published definition failed runtime validation");
        }
        // Connector health and enabled/deleted bindings are mutable after a
        // version is published. Re-check them at admission so a run never
        // enters the durable queue with an already-unusable target.
        validateConnections(version.getDefinitionJson(), tenant);
        validateSubPlaybookGraph(tenant, version);
        if (subject != null && subject.size() > 8) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_INPUT_INVALID", "subject has too many fields");
        }
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        SoarRunEntity run = new SoarRunEntity();
        run.setId(runId);
        run.setTenantId(tenant);
        run.setRequestId(requestId);
        run.setExecutionSeriesId(runId);
        run.setPlaybookId(version.getPlaybookId());
        run.setPlaybookVersionId(version.getId());
        run.setPlaybookVersionNo(version.getVersionNo());
        run.setDefinitionHash(version.getDefinitionHash());
        run.setTriggerType("MANUAL");
        run.setSubjectType(text(subject, "type"));
        run.setSubjectId(text(subject, "id"));
        run.setStatus(SoarRunStatus.QUEUED.name());
        run.setExecutionNodeCount(0);
        String inputJson = write(redact(Map.of("subject", subject == null ? Map.of() : subject,
                "inputs", inputs == null ? Map.of() : inputs)));
        if (inputJson.getBytes(StandardCharsets.UTF_8).length > SoarDefinitionValidator.MAX_BYTES) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_INPUT_TOO_LARGE",
                    "run input exceeds 256 KiB");
        }
        run.setInputJson(inputJson);
        run.setRequestedBy(actor());
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        DefinitionValidationResult risk = checked;
        boolean approvalRequired = risk.highRiskActionCount() > 0;
        ApprovalContext approvalContext = approvalRequired
                ? buildApprovalContext(version.getDefinitionJson(), inputJson) : ApprovalContext.empty(inputJson);
        if (approvalRequired) run.setStatus(SoarRunStatus.WAITING_APPROVAL.name());
        runs.save(run);

        SoarDispatchOutboxEntity outbox = new SoarDispatchOutboxEntity();
        outbox.setId(UUID.randomUUID().toString());
        outbox.setTenantId(tenant);
        outbox.setRunId(runId);
        outbox.setStatus(approvalRequired ? "HOLD" : "PENDING");
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        dispatches.save(outbox);
        if (approvalRequired) {
            SoarApprovalEntity approval = new SoarApprovalEntity();
            approval.setId(UUID.randomUUID().toString());
            approval.setTenantId(tenant);
            approval.setRunId(runId);
            approval.setApprovalKey(runId);
            approval.setStatus("PENDING");
            approval.setRequestedBy(actor());
            approval.setActionRef(approvalContext.actionRef());
            approval.setInputHash(approvalContext.inputHash());
            approval.setTargetSnapshotJson(approvalContext.targetSnapshotJson());
            approval.setPolicyJson(approvalPolicyJson(approvalContext.targetSnapshotJson()));
            approval.setReason("published version contains high-risk response actions");
            approval.setCreatedAt(now);
            approval.setExpiresAt(now.plusSeconds(24 * 3600));
            approvals.save(approval);
            appendEvent(runId, "RUN_WAITING_APPROVAL", actor(), "Run is waiting for approval",
                    Map.of("requestId", requestId, "highRiskActionCount", risk.highRiskActionCount()));
        } else {
            appendEvent(runId, "RUN_QUEUED", actor(), "Run accepted and queued", Map.of("requestId", requestId));
        }
        Map<String, Object> result = runView(run);
        result.put("duplicate", false);
        return result;
    }

    private boolean idempotencyRequestMatches(SoarRunEntity existing, String versionId,
                                               Map<String, Object> subject,
                                               Map<String, Object> inputs) {
        if (!Objects.equals(existing.getPlaybookVersionId(), versionId)) return false;
        String stored = existing.getInputJson();
        if (stored == null || stored.isBlank()) {
            return (subject == null || subject.isEmpty()) && (inputs == null || inputs.isEmpty());
        }
        try {
            JsonNode expected = mapper.readTree(stored);
            JsonNode requested = mapper.valueToTree(redact(Map.of(
                    "subject", subject == null ? Map.of() : subject,
                    "inputs", inputs == null ? Map.of() : inputs)));
            return expected.equals(requested);
        } catch (RuntimeException | JsonProcessingException malformed) {
            // A legacy/corrupt projection must not be used as proof that a
            // new payload is the same request.  Force the operator/client to
            // choose a fresh idempotency key instead.
            return false;
        }
    }

    public Map<String, Object> retryRun(String id, String reason) {
        requireExecution(tenant());
        SoarRunEntity original = run(id);
        if (!Set.of(SoarRunStatus.FAILED.name(), SoarRunStatus.ACTION_UNKNOWN.name(),
                SoarRunStatus.DEAD.name(), SoarRunStatus.TIMED_OUT.name()).contains(original.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RETRYABLE", "run is not in a retryable state");
        }
        // An ACTION_UNKNOWN node means the remote side effect may already have
        // happened.  Auto-retrying under the same execution series would risk
        // duplicating that effect, so the operator must first resolve the
        // unknown through SOAR_RESOLVE_UNKNOWN (CONFIRMED_SUCCEEDED skips
        // the node; CONFIRMED_NOT_EXECUTED re-queues it safely).
        boolean unresolvedUnknown = nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(tenant(), id).stream()
                .anyMatch(node -> Set.of("ACTION_UNKNOWN", "UNKNOWN").contains(node.getStatus()));
        if (unresolvedUnknown || SoarRunStatus.ACTION_UNKNOWN.name().equals(original.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RETRYABLE",
                    "run contains an unresolved unknown action result; resolve it before retrying");
        }
        String resumeNode = nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(tenant(), id).stream()
                .filter(node -> Set.of("FAILED", "TIMED_OUT").contains(node.getStatus()))
                .map(SoarNodeRunEntity::getNodeId).findFirst().orElse(null);
        return cloneRun(original, "retry-" + id + "-" + shortHash(String.valueOf(reason)),
                original.getExecutionSeriesId(), resumeNode, reason, false);
    }

    public Map<String, Object> rerun(String id, String reason, boolean confirm) {
        if (!confirm) throw error(HttpStatus.CONFLICT, "SOAR_RERUN_CONFIRMATION_REQUIRED",
                "rerun requires explicit confirmation");
        requireExecution(tenant());
        SoarRunEntity original = run(id);
        return cloneRun(original, "rerun-" + id + "-" + shortHash(String.valueOf(reason)),
                UUID.randomUUID().toString(), null, reason, true);
    }

    public Map<String, Object> resolveUnknown(String nodeRunId, String resolution,
                                              String evidence, String reason) {
        String tenant = tenant();
        java.util.Optional<SoarNodeRunEntity> lockedNode = nodes.findByTenantIdAndIdForUpdate(tenant, nodeRunId);
        if (lockedNode == null) lockedNode = nodes.findByTenantIdAndId(tenant, nodeRunId);
        SoarNodeRunEntity node = (lockedNode == null ? java.util.Optional.<SoarNodeRunEntity>empty() : lockedNode)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_NODE_RUN_NOT_FOUND", "node run not found"));
        if (!"ACTION_UNKNOWN".equals(node.getStatus()) && !"UNKNOWN".equals(node.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_ACTION_RESULT_NOT_UNKNOWN", "node is not unknown");
        }
        String normalized = resolution == null ? "" : resolution.trim().toUpperCase();
        if (!Set.of("CONFIRMED_SUCCEEDED", "CONFIRMED_NOT_EXECUTED").contains(normalized)) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_ACTION_RESULT_UNKNOWN", "invalid unknown resolution");
        }
        if (evidence == null || evidence.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_ACTION_RESULT_UNKNOWN", "evidence is required");
        }
        if (reason == null || reason.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_ACTION_RESULT_UNKNOWN", "reason is required");
        }
        String safeEvidence = redactFreeText(evidence, 4096);
        String safeReason = redactFreeText(reason, 2048);

        // Resolve is an operator decision on a still-live UNKNOWN action. Do
        // not let a late browser request revive a run that has already reached
        // a terminal projection (including PARTIALLY_SUCCEEDED), or one that
        // is already in the cancellation fence. The node and run are checked
        // before either projection is mutated so a rejected late decision is
        // side-effect free.
        java.util.Optional<SoarRunEntity> lockedOwner = runs.findByTenantIdAndIdForUpdate(tenant, node.getRunId());
        if (lockedOwner == null) lockedOwner = runs.findByTenantIdAndId(tenant, node.getRunId());
        SoarRunEntity owner = (lockedOwner == null ? java.util.Optional.<SoarRunEntity>empty() : lockedOwner)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
        if (terminalRunProjection(owner)) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                    "the run is already terminal and cannot resolve an unknown action");
        }
        if (SoarRunStatus.CANCELLING.name().equals(owner.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                    "the run is cancelling and cannot resolve an unknown action");
        }

        node.setStatus(normalized);
        node.setErrorCode(null);
        node.setErrorMessage(safeReason);
        node.setOutputJson(write(Map.of("resolution", normalized, "evidence", safeEvidence)));
        node.setUpdatedAt(Instant.now());
        nodes.save(node);
        appendEvent(node.getRunId(), "ACTION_UNKNOWN_RESOLVED", actor(),
                "Unknown action result was resolved", Map.of("nodeRunId", nodeRunId,
                        "resolution", normalized, "reason", redactFreeText(safeReason, 512)));
        boolean workflowAttached = owner.getTemporalWorkflowId() != null
                && !owner.getTemporalWorkflowId().isBlank();
        boolean workflowCanResume = workflowAttached && Set.of(
                SoarRunStatus.RUNNING.name(), SoarRunStatus.ACTION_UNKNOWN.name()).contains(owner.getStatus());
        owner.setStatus(workflowCanResume ? SoarRunStatus.RUNNING.name()
                : (workflowAttached ? owner.getStatus() : SoarRunStatus.QUEUED.name()));
        if (workflowCanResume || !workflowAttached) {
            owner.setErrorCode(null);
            owner.setErrorMessage(null);
        }
        owner.setUpdatedAt(Instant.now());
        runs.save(owner);
        if (workflowCanResume) {
            enqueueSignal(owner, "UNKNOWN_RESOLUTION", Map.of(
                    "nodeId", node.getNodeId(), "resolution", normalized,
                    "evidence", safeEvidence, "reason", safeReason));
        } else if (!workflowAttached) {
            dispatches.findByTenantIdAndRunId(tenant, owner.getId()).ifPresent(outbox -> {
                outbox.setStatus("PENDING"); outbox.setNextAttemptAt(Instant.now());
                outbox.setUpdatedAt(Instant.now()); dispatches.save(outbox);
            });
        }
        return nodeView(node);
    }

    public Map<String, Object> completeManualTask(String id, Map<String, Object> input) {
        String tenant = tenant();
        java.util.Optional<SoarManualTaskEntity> lockedTask = manualTasks.findByTenantIdAndIdForUpdate(tenant, id);
        if (lockedTask == null || lockedTask.isEmpty()) lockedTask = manualTasks.findByTenantIdAndId(tenant, id);
        SoarManualTaskEntity task = (lockedTask == null ? java.util.Optional.<SoarManualTaskEntity>empty() : lockedTask)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_MANUAL_TASK_NOT_FOUND", "manual task not found"));
        if (!"PENDING".equals(task.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_MANUAL_TASK_ALREADY_COMPLETED", "manual task is not pending");
        }
        manualInputValidator.validate(task.getFormSchemaJson(), input);
        // The task row and its owning run are independent projections. Lock
        // the run before completing the task so a late operator submission
        // cannot move a cancelled/suppressed/partially-successful run back to
        // RUNNING or enqueue a signal for a closed workflow.
        java.util.Optional<SoarRunEntity> lockedOwner = runs.findByTenantIdAndIdForUpdate(tenant, task.getRunId());
        if (lockedOwner == null || lockedOwner.isEmpty()) lockedOwner = runs.findByTenantIdAndId(tenant, task.getRunId());
        // A lock projection must belong to the requested run.  Besides being
        // a useful integrity check, this keeps compatibility with old test
        // doubles that return a generic lock row for every id; the ordinary
        // tenant-scoped lookup still resolves the actual owner.
        if (lockedOwner != null && lockedOwner.isPresent()
                && lockedOwner.get().getId() != null
                && !task.getRunId().equals(lockedOwner.get().getId())) {
            java.util.Optional<SoarRunEntity> requestedOwner = runs.findByTenantIdAndId(tenant, task.getRunId());
            if (requestedOwner != null && requestedOwner.isPresent()) lockedOwner = requestedOwner;
        }
        SoarRunEntity owner = (lockedOwner == null ? java.util.Optional.<SoarRunEntity>empty() : lockedOwner)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
        if (terminalRunProjection(owner)) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                    "the run is already terminal and cannot complete a manual task");
        }
        if (SoarRunStatus.CANCELLING.name().equals(owner.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                    "the run is cancelling and cannot complete a manual task");
        }
        Instant now = Instant.now();
        task.setInputJson(write(redact(input)));
        task.setStatus("COMPLETED");
        task.setCompletedBy(actor()); task.setCompletedAt(now); task.setUpdatedAt(now);
        manualTasks.save(task);
        boolean attachedWorkflow = owner.getTemporalWorkflowId() != null
                && !owner.getTemporalWorkflowId().isBlank();
        // A running Temporal workflow must not look QUEUED while it is being
        // resumed: QUEUED is reserved for the dispatch outbox and can be
        // mistaken for a second start by recovery/operations tooling.
        owner.setStatus(attachedWorkflow ? SoarRunStatus.RUNNING.name() : SoarRunStatus.QUEUED.name());
        owner.setUpdatedAt(now); runs.save(owner);
        enqueueSignal(owner, "MANUAL_TASK", Map.of("taskId", id, "nodeId", task.getNodeId(),
                "input", redact(input == null ? Map.of() : input)));
        appendEvent(owner.getId(), "MANUAL_TASK_COMPLETED", actor(), "Manual task completed",
                Map.of("taskId", id));
        return manualTaskView(task);
    }

    public Map<String, Object> cancelRun(String id, String reason) {
        String safeReason = redactFreeText(reason == null ? "operator requested cancellation" : reason, 2048);
        SoarRunEntity run = run(id);
        String status = run.getStatus();
        boolean hasWorkflow = run.getTemporalWorkflowId() != null
                && !run.getTemporalWorkflowId().isBlank();
        if (SoarRunStatus.QUEUED.name().equals(status)
                || (SoarRunStatus.DISPATCHING.name().equals(status) && !hasWorkflow)
                || ((SoarRunStatus.WAITING_APPROVAL.name().equals(status)
                || SoarRunStatus.WAITING_INPUT.name().equals(status)) && !hasWorkflow)) {
            Instant now = Instant.now();
            run.setStatus(SoarRunStatus.CANCELLED.name());
            run.setErrorCode("SOAR_RUN_CANCELLED");
            run.setErrorMessage(safeReason);
            run.setCompletedAt(now);
            run.setUpdatedAt(now);
            dispatches.findByTenantIdAndRunId(tenant(), run.getId()).ifPresent(outbox -> {
                if (!"DISPATCHED".equals(outbox.getStatus())) {
                    outbox.setStatus("CANCELLED");
                    outbox.setUpdatedAt(now);
                    dispatches.save(outbox);
                }
            });
            approvals.findAllByTenantIdAndRunIdOrderByCreatedAtAsc(tenant(), run.getId())
                    .forEach(approval -> {
                        if ("PENDING".equals(approval.getStatus())) {
                            approval.setStatus("CANCELLED");
                            approval.setDecidedAt(now);
                            approval.setDecisionReason("run cancelled");
                            approvals.save(approval);
                        }
                    });
            runs.save(run);
            appendEvent(id, "RUN_CANCELLED", actor(), "Queued run cancelled", Map.of("reason", limit(safeReason, 512)));
            return runView(run);
        }
        if (SoarRunStatus.RUNNING.name().equals(status)
                || SoarRunStatus.DISPATCHING.name().equals(status)
                || ((SoarRunStatus.WAITING_APPROVAL.name().equals(status)
                || SoarRunStatus.WAITING_INPUT.name().equals(status)) && hasWorkflow)) {
            run.setStatus(SoarRunStatus.CANCELLING.name());
            run.setErrorCode("SOAR_RUN_CANCEL_REQUESTED");
            run.setErrorMessage(safeReason);
            run.setUpdatedAt(Instant.now());
            runs.save(run);
            appendEvent(id, "RUN_CANCEL_REQUESTED", actor(), "Cancellation requested", Map.of("reason", limit(safeReason, 512)));
            return runView(run);
        }
        throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_CANCELLABLE", "run is already terminal");
    }

    private Map<String, Object> cloneRun(SoarRunEntity original, String requestId,
                                         String seriesId, String resumeNode, String reason,
                                         boolean rerun) {
        String tenant = tenant();
        String safeReason = redactFreeText(reason == null ? "" : reason, 1024);
        String normalizedRequestId = required(requestId, "requestId", 128);
        // Retry/rerun requests are safe to repeat from a UI after a network
        // timeout.  Return the already-created run instead of surfacing a
        // unique-key violation or accidentally creating a second series.
        Map<String, Object> existing = runs.findByTenantIdAndRequestId(tenant, normalizedRequestId)
                .map(this::runView).orElse(null);
        if (existing != null) {
            existing.put("duplicate", true);
            return existing;
        }
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        SoarRunEntity clone = new SoarRunEntity();
        clone.setId(runId); clone.setTenantId(tenant); clone.setRequestId(normalizedRequestId);
        clone.setExecutionSeriesId(seriesId == null ? runId : seriesId);
        clone.setPlaybookId(original.getPlaybookId()); clone.setPlaybookVersionId(original.getPlaybookVersionId());
        clone.setPlaybookVersionNo(original.getPlaybookVersionNo()); clone.setDefinitionHash(original.getDefinitionHash());
        clone.setTriggerType(rerun ? "RERUN" : "RETRY"); clone.setSubjectType(original.getSubjectType());
        clone.setSubjectId(original.getSubjectId());
        PlaybookVersionEntity sourceVersion = versions.findByTenantIdAndId(tenant, original.getPlaybookVersionId())
                .orElseThrow(() -> error(HttpStatus.CONFLICT, "SOAR_VERSION_NOT_FOUND",
                        "source published version is unavailable"));
        if (!SoarPlaybookVersionStatus.PUBLISHED.name().equals(sourceVersion.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_VERSION_NOT_PUBLISHED",
                    "source version is no longer published");
        }
        DefinitionValidationResult sourceChecked = validator.validate(sourceVersion.getDefinitionJson());
        if (!sourceChecked.valid()) {
            throw error(HttpStatus.CONFLICT, "SOAR_DEFINITION_INVALID",
                    "source published definition failed runtime validation");
        }
        validateConnections(sourceVersion.getDefinitionJson(), tenant);
        validateSubPlaybookGraph(tenant, sourceVersion);
        boolean approvalRequired = sourceChecked.highRiskActionCount() > 0;
        clone.setStatus(approvalRequired ? SoarRunStatus.WAITING_APPROVAL.name() : SoarRunStatus.QUEUED.name());
        clone.setExecutionNodeCount(0);
        Map<String, Object> input = new LinkedHashMap<>(redact(readMap(original.getInputJson())) instanceof Map<?, ?> value
                ? castObjectMap(value) : Map.of());
        // A terminal workflow projection carries a redacted variable
        // snapshot.  Restore it before placing the resume marker so a safe
        // retry has the same upstream context as the failed attempt (for
        // example values written by SET_VARIABLE or enrichment actions).
        Map<String, Object> snapshot = resumeVariables(original.getOutputJson());
        if (!snapshot.isEmpty()) input.putAll(snapshot);
        input.put("_soar", Map.of("reason", safeReason, "resumeFromNodeId", resumeNode == null ? "" : resumeNode));
        clone.setInputJson(write(redact(input))); clone.setRequestedBy(actor());
        ApprovalContext approvalContext = approvalRequired
                ? buildApprovalContext(sourceVersion.getDefinitionJson(), clone.getInputJson())
                : ApprovalContext.empty(clone.getInputJson());
        clone.setCreatedAt(now); clone.setUpdatedAt(now);
        runs.save(clone);
        SoarDispatchOutboxEntity outbox = new SoarDispatchOutboxEntity();
        outbox.setId(UUID.randomUUID().toString()); outbox.setTenantId(tenant); outbox.setRunId(runId);
        outbox.setStatus(approvalRequired ? "HOLD" : "PENDING"); outbox.setAttempts(0); outbox.setNextAttemptAt(now);
        outbox.setCreatedAt(now); outbox.setUpdatedAt(now); dispatches.save(outbox);
        if (approvalRequired) {
            SoarApprovalEntity approval = new SoarApprovalEntity();
            approval.setId(UUID.randomUUID().toString()); approval.setTenantId(tenant); approval.setRunId(runId);
            approval.setApprovalKey(runId);
            approval.setStatus("PENDING"); approval.setRequestedBy(actor());
            approval.setActionRef(approvalContext.actionRef());
            approval.setInputHash(approvalContext.inputHash());
            approval.setTargetSnapshotJson(approvalContext.targetSnapshotJson());
            approval.setPolicyJson(approvalPolicyJson(approvalContext.targetSnapshotJson()));
            approval.setReason("retry/rerun contains high-risk response actions");
            approval.setCreatedAt(now); approval.setExpiresAt(now.plusSeconds(24 * 3600)); approvals.save(approval);
        }
        appendEvent(runId, rerun ? "RUN_RERUN_QUEUED" : "RUN_RETRY_QUEUED", actor(),
                rerun ? "Explicit rerun queued" : "Safe retry queued", Map.of("sourceRunId", original.getId(),
                        "reason", limit(safeReason, 512), "resumeNodeId", resumeNode == null ? "" : resumeNode));
        Map<String, Object> result = runView(clone); result.put("duplicate", false); return result;
    }

    private Map<String, Object> resumeVariables(String outputJson) {
        try {
            JsonNode output = mapper.readTree(outputJson == null ? "{}" : outputJson);
            JsonNode state = output == null ? null : output.get("variables");
            if (state == null || !state.isObject()) return new LinkedHashMap<>();
            Map<String, Object> value = mapper.convertValue(state, Map.class);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }
}
