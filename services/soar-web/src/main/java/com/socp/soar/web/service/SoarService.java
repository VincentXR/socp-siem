package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.artifact.SoarArtifactStore;
import com.socp.soar.web.config.SoarRuntimeProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Application service for the durable SOAR control plane. */
@Service
public class SoarService {
    private final SoarPlaybookCommandService playbookCommands;
    private final SoarRunCommandService runCommands;
    private final SoarArtifactCommandService artifactCommands;
    private final SoarApprovalCommandService approvalCommands;
    private final SoarOutboxCommandService outboxCommands;
    private final SoarDefinitionPolicy definitionPolicy;
    private final SoarQueryService queries;
    private final SoarRecords records;
    private final SoarRuntimeGate gates;
    private final SoarEventWriter eventWriter;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarService(SoarPlaybookRepository playbooks, PlaybookVersionRepository versions,
                         SoarRunRepository runs, SoarDispatchOutboxRepository dispatches,
                         SoarNodeRunRepository nodes, SoarRunEventRepository events,
                         SoarApprovalRepository approvals, SoarDefinitionValidator validator,
                         ObjectMapper mapper, TemporalExecutor temporal,
                         SoarActionAttemptRepository attempts, SoarManualTaskRepository manualTasks,
                         SoarSignalOutboxRepository signals, SoarConnectorRepository connectors,
                         SoarConnectorRegistry connectorRegistry) {
        SoarReadModelMapper readModels = new SoarReadModelMapper(mapper);
        SoarManualInputValidator manualInputValidator = new SoarManualInputValidator(mapper);
        SoarJson json = new SoarJson(mapper);
        this.records = new SoarRecords(playbooks, versions, runs);
        this.gates = new SoarRuntimeGate();
        this.eventWriter = new SoarEventWriter(runs, events, signals, mapper, json);
        this.definitionPolicy = new SoarDefinitionPolicy(playbooks, versions, mapper, connectors, connectorRegistry, json);
        this.playbookCommands = new SoarPlaybookCommandService(playbooks, versions, validator, mapper, readModels,
                json, records, gates, definitionPolicy);
        this.runCommands = new SoarRunCommandService(playbooks, versions, runs, dispatches, nodes, approvals,
                validator, mapper, manualInputValidator, manualTasks, readModels, json, records, gates, eventWriter,
                definitionPolicy);
        this.artifactCommands = new SoarArtifactCommandService(nodes, mapper, readModels, json, records, eventWriter);
        this.approvalCommands = new SoarApprovalCommandService(versions, runs, dispatches, approvals, readModels, json, eventWriter);
        this.outboxCommands = new SoarOutboxCommandService(runs, dispatches, signals, eventWriter);
        this.queries = new SoarQueryService(playbooks, versions, runs, dispatches, nodes, events, approvals,
                attempts, manualTasks, signals, readModels, records);
    }

    /** Optional setter keeps isolated control-plane tests independent of artifact storage. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setArtifacts(SoarArtifactRepository artifacts) {
        this.artifactCommands.setArtifacts(artifacts);
        this.records.setArtifacts(artifacts);
        this.queries.setArtifacts(artifacts);
    }

    /** Optional in preview; production supplies the configured S3-compatible store. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setArtifactStore(SoarArtifactStore artifactStore) {
        this.artifactCommands.setArtifactStore(artifactStore);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeProperties(SoarRuntimeProperties runtimeProperties) {
        this.gates.setRuntimeProperties(runtimeProperties);
        this.definitionPolicy.setRuntimeProperties(runtimeProperties);
    }

    /** Optional setter keeps compatibility/unit tests independent of the V14 vote projection. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApprovalDecisions(SoarApprovalDecisionRepository approvalDecisions) {
        this.approvalCommands.setApprovalDecisions(approvalDecisions);
        this.queries.setApprovalDecisions(approvalDecisions);
    }

    @Transactional
    @AuditOperation(action = "SOAR_CREATE_PLAYBOOK", target = "t_soar_playbook")
    public Map<String, Object> createPlaybook(String name, String description, List<String> tags) {
        return playbookCommands.createPlaybook(name, description, tags);
    }

    /** Import a definition as an editable draft. Import never publishes or schedules execution. */
    @Transactional
    @AuditOperation(action = "SOAR_IMPORT_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> importDraft(String name, String description, List<String> tags,
                                            JsonNode definition, JsonNode layout) {
        return playbookCommands.importDraft(name, description, tags, definition, layout);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listPlaybooks(Pageable pageable) {
        return queries.listPlaybooks(pageable);
    }

    /**
     * Filtered playbook listing used by operators and automation pickers.  The
     * database applies exact tag tokens and the latest published numeric risk
     * metadata before paging, with the same semantics on PostgreSQL and H2.
     */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listPlaybooks(Pageable pageable, String status,
                                                   String owner, String tag, String risk) {
        return queries.listPlaybooks(pageable, status, owner, tag, risk);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPlaybook(String id) {
        return queries.getPlaybook(id);
    }

    /**
     * Change only playbook metadata.  Version definitions remain immutable;
     * archiving pauses future automation evaluation while existing runs retain
     * their published snapshot.
     */
    @Transactional
    @AuditOperation(action = "SOAR_UPDATE_PLAYBOOK", target = "t_soar_playbook")
    public Map<String, Object> updatePlaybook(String id, String name, String description,
                                               List<String> tags, String status,
                                               Long expectedRowVersion) {
        return playbookCommands.updatePlaybook(id, name, description, tags, status, expectedRowVersion);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listVersions(String playbookId) {
        return queries.listVersions(playbookId);
    }

    /** Create a new immutable draft from the latest version after a publish. */
    @Transactional
    @AuditOperation(action = "SOAR_CREATE_VERSION", target = "t_soar_playbook_version")
    public Map<String, Object> createVersion(String playbookId) {
        return playbookCommands.createVersion(playbookId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getVersion(String playbookId, int versionNo) {
        return queries.getVersion(playbookId, versionNo);
    }

    @Transactional(readOnly = true)
    @AuditOperation(action = "SOAR_EXPORT_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> exportVersion(String playbookId, int versionNo) {
        return queries.exportVersion(playbookId, versionNo);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getVersionById(String versionId) {
        return queries.getVersionById(versionId);
    }

    /**
     * Re-validate an automation target at enable time.  Published status is
     * immutable, but connector health/configuration and tenant bindings are
     * mutable; enabling a rule must not resurrect a stale reference.
     */
    @Transactional(readOnly = true)
    public void validatePublishedVersionForAutomation(String versionId) {
        playbookCommands.validatePublishedVersionForAutomation(versionId);
    }

    @Transactional
    @AuditOperation(action = "SOAR_SAVE_DRAFT", target = "t_soar_playbook_version")
    public Map<String, Object> saveDraft(String playbookId, int versionNo, String definition,
                                         String layout, Long expectedRowVersion) {
        return playbookCommands.saveDraft(playbookId, versionNo, definition, layout, expectedRowVersion);
    }

    @Transactional(readOnly = true)
    @AuditOperation(action = "SOAR_VALIDATE_PLAYBOOK", target = "t_soar_playbook_version")
    public DefinitionValidationResult validateVersion(String playbookId, int versionNo) {
        return playbookCommands.validateVersion(playbookId, versionNo);
    }

    /**
     * Preview a draft without invoking connectors or writing execution rows.
     * Published versions are accepted for troubleshooting, but the response
     * remains visibly SIMULATED and cannot be mistaken for a real run.
     */
    @Transactional(readOnly = true)
    @AuditOperation(action = "SOAR_DRY_RUN_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> dryRun(String playbookId, int versionNo,
                                      Map<String, Object> subject, Map<String, Object> inputs) {
        return playbookCommands.dryRun(playbookId, versionNo, subject, inputs);
    }

    /** Machine-readable schema used by the Workbench editor and import checks. */
    @Transactional(readOnly = true)
    public JsonNode definitionSchema() {
        return playbookCommands.definitionSchema();
    }

    @Transactional
    @AuditOperation(action = "SOAR_PUBLISH_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> publish(String playbookId, int versionNo) {
        return playbookCommands.publish(playbookId, versionNo);
    }

    @Transactional
    @AuditOperation(action = "SOAR_DEPRECATE_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> deprecate(String playbookId, int versionNo) {
        return playbookCommands.deprecate(playbookId, versionNo);
    }

    /** Restore an older revision as a new editable draft (history stays immutable). */
    @Transactional
    @AuditOperation(action = "SOAR_ROLLBACK_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> rollbackToDraft(String playbookId, int versionNo) {
        return playbookCommands.rollbackToDraft(playbookId, versionNo);
    }

    @Transactional
    @AuditOperation(action = "SOAR_QUEUE_RUN", target = "t_soar_run")
    public Map<String, Object> queueManualRun(String requestId, String versionId, Map<String, Object> subject,
                                              Map<String, Object> inputs) {
        return runCommands.queueManualRun(requestId, versionId, subject, inputs);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listRuns(Pageable pageable) {
        return queries.listRuns(pageable);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listRuns(Pageable pageable, String status,
                                              String playbookVersionId, String triggerType,
                                              String requestedBy, Instant createdFrom,
                                              Instant createdTo) {
        return queries.listRuns(pageable, status, playbookVersionId, triggerType,
                requestedBy, createdFrom, createdTo);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String id) {
        return queries.getRun(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listNodes(String runId) {
        return queries.listNodes(runId);
    }

    /**
     * Paged node projection for large fan-out/FOREACH runs.  The list-shaped
     * overload remains the compatibility path used by older Workbench builds.
     */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listNodes(String runId, Pageable pageable) {
        return queries.listNodes(runId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listArtifacts(String runId) {
        return queries.listArtifacts(runId);
    }

    /** Paged artifact projection; the list overload is retained for clients
     * that do not request pagination explicitly. */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listArtifacts(String runId, Pageable pageable) {
        return queries.listArtifacts(runId, pageable);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getArtifact(String id) {
        return queries.getArtifact(id);
    }

    @Transactional(readOnly = true)
    public String getArtifactContent(String id) {
        return artifactCommands.getArtifactContent(id);
    }

    /** Upload a bounded analyst artifact when no object-store adapter is configured. */
    @Transactional
    @AuditOperation(action = "SOAR_UPLOAD_ARTIFACT", target = "t_soar_artifact")
    public Map<String, Object> uploadArtifact(String runId, String nodeRunId,
                                               String mediaType, String classification,
                                               JsonNode content) {
        return artifactCommands.uploadArtifact(runId, nodeRunId, mediaType, classification, content);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listNodeAttempts(String nodeRunId, Pageable pageable) {
        return queries.listNodeAttempts(nodeRunId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEvents(String runId) {
        return queries.listEvents(runId);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listEvents(String runId, long afterSequence, Pageable pageable) {
        return queries.listEvents(runId, afterSequence, pageable);
    }

    /** Safe retry creates a new durable run in the same execution series. */
    @Transactional
    @AuditOperation(action = "SOAR_RETRY_RUN", target = "t_soar_run")
    public Map<String, Object> retryRun(String id, String reason) {
        return runCommands.retryRun(id, reason);
    }

    /** Explicit rerun intentionally gets a new execution series and idempotency keys. */
    @Transactional
    @AuditOperation(action = "SOAR_RERUN_RUN", target = "t_soar_run")
    public Map<String, Object> rerun(String id, String reason, boolean confirm) {
        return runCommands.rerun(id, reason, confirm);
    }

    @Transactional
    @AuditOperation(action = "SOAR_RESOLVE_UNKNOWN", target = "t_soar_node_run")
    public Map<String, Object> resolveUnknown(String nodeRunId, String resolution,
                                              String evidence, String reason) {
        return runCommands.resolveUnknown(nodeRunId, resolution, evidence, reason);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listManualTasks(boolean pendingOnly) {
        return queries.listManualTasks(pendingOnly);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listManualTasks(boolean pendingOnly, Pageable pageable) {
        return queries.listManualTasks(pendingOnly, pageable);
    }

    @Transactional
    @AuditOperation(action = "SOAR_COMPLETE_MANUAL_TASK", target = "t_soar_manual_task")
    public Map<String, Object> completeManualTask(String id, Map<String, Object> input) {
        return runCommands.completeManualTask(id, input);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        return queries.stats();
    }

    /** System-wide dispatch/signal backlog for the health endpoint. The health
     * surface is intentionally not tenant-scoped: operators need a cross-tenant
     * view of stuck work. */
    @Transactional(readOnly = true)
    public Map<String, Object> healthBacklog() {
        return queries.healthBacklog();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadDispatches() {
        return queries.deadDispatches();
    }

    @Transactional
    @AuditOperation(action = "SOAR_REQUEUE_DEAD_OUTBOX", target = "t_soar_dispatch_outbox")
    public Map<String, Object> requeueDead(String id, String reason) {
        return outboxCommands.requeueDead(id, reason);
    }

    @Transactional
    @AuditOperation(action = "SOAR_DISCARD_DEAD_OUTBOX", target = "t_soar_dispatch_outbox")
    public Map<String, Object> discardDead(String id, String reason) {
        return outboxCommands.discardDead(id, reason);
    }

    @Transactional
    @AuditOperation(action = "SOAR_CANCEL_RUN", target = "t_soar_run")
    public Map<String, Object> cancelRun(String id, String reason) {
        return runCommands.cancelRun(id, reason);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listApprovals() {
        return queries.listApprovals();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listApprovals(Pageable pageable) {
        return queries.listApprovals(pageable);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listApprovals(Pageable pageable, String status) {
        return queries.listApprovals(pageable, status);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getApproval(String id) { return queries.getApproval(id); }


    @Transactional(noRollbackFor = ResponseStatusException.class)
    @AuditOperation(action = "SOAR_DECIDE_APPROVAL", target = "t_soar_approval")
    public Map<String, Object> decideApproval(String id, boolean approve, String decisionReason) {
        return approvalCommands.decideApproval(id, approve, decisionReason);
    }

    /**
     * Idempotent janitor entry point for approvals that expire while no
     * operator is attempting a decision.  The caller supplies no tenant; the
     * authenticated/system tenant context still scopes every repository read.
     */
    @Transactional
    @AuditOperation(action = "SOAR_EXPIRE_APPROVAL", target = "t_soar_approval")
    public boolean expireApproval(String id, Instant now) {
        return approvalCommands.expireApproval(id, now);
    }

    /** Helpers run within the public facade's existing Spring transaction. */
    void enqueueSignal(SoarRunEntity run, String type, Map<String, Object> payload) {
        eventWriter.enqueueSignal(run, type, payload);
    }

    void requireControlPlane() { gates.requireControlPlane(); }
    void requireEvaluation() { gates.requireEvaluation(); }

    static String redactFreeText(String value, int max) { return SoarRedaction.freeText(value, max); }

    static Map<String, Object> castObjectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    static boolean terminalRunProjection(SoarRunEntity run) {
        return run != null && run.getStatus() != null && Set.of(
                SoarRunStatus.SUCCEEDED.name(), SoarRunStatus.PARTIALLY_SUCCEEDED.name(),
                SoarRunStatus.FAILED.name(), SoarRunStatus.TIMED_OUT.name(),
                SoarRunStatus.CANCELLED.name(), SoarRunStatus.SUPPRESSED.name(),
                SoarRunStatus.DEAD.name()).contains(run.getStatus());
    }

    static String actor() {
        return AuthenticatedIdentityContext.current()
                .map(identity -> limit(identity.subject(), 128))
                .orElse("system");
    }

    static String text(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) return null;
        String value = String.valueOf(map.get(key)).trim();
        return value.isBlank() ? null : limit(value, 255);
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format("%02x", item));
            return out.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    static String normalizeFilter(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw error(HttpStatus.BAD_REQUEST, "SOAR_INPUT_INVALID", field + " is required");
        return limit(value.trim(), max);
    }

    static String limit(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }

    static ResponseStatusException error(HttpStatus status, String code, String message) {
        return new ResponseStatusException(status, code + ": " + message);
    }
}
