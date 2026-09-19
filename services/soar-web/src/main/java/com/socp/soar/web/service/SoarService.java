package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.domain.SoarPlaybookVersionStatus;
import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Application service for the durable SOAR control plane. */
@Service
public class SoarService {
    final SoarPlaybookRepository playbooks;
    final PlaybookVersionRepository versions;
    final SoarRunRepository runs;
    final SoarDispatchOutboxRepository dispatches;
    final SoarNodeRunRepository nodes;
    final SoarRunEventRepository events;
    final SoarApprovalRepository approvals;
    SoarApprovalDecisionRepository approvalDecisions;
    final TemporalExecutor temporal;
    final SoarDefinitionValidator validator;
    final ObjectMapper mapper;
    final SoarManualInputValidator manualInputValidator;
    final SoarActionAttemptRepository attempts;
    final SoarManualTaskRepository manualTasks;
    final SoarSignalOutboxRepository signals;
    final SoarConnectorRepository connectors;
    final SoarConnectorRegistry connectorRegistry;
    SoarArtifactRepository artifacts;
    SoarArtifactStore artifactStore;
    SoarRuntimeProperties runtimeProperties;
    final SoarReadModelMapper readModels;
    private final SoarPlaybookCommandService playbookCommands;
    private final SoarRunCommandService runCommands;
    private final SoarArtifactCommandService artifactCommands;
    private final SoarApprovalCommandService approvalCommands;
    private final SoarOutboxCommandService outboxCommands;
    private final SoarDefinitionPolicy definitionPolicy;
    private final SoarQueryService queries;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarService(SoarPlaybookRepository playbooks, PlaybookVersionRepository versions,
                         SoarRunRepository runs, SoarDispatchOutboxRepository dispatches,
                         SoarNodeRunRepository nodes, SoarRunEventRepository events,
                         SoarApprovalRepository approvals, SoarDefinitionValidator validator,
                         ObjectMapper mapper, TemporalExecutor temporal,
                         SoarActionAttemptRepository attempts, SoarManualTaskRepository manualTasks,
                         SoarSignalOutboxRepository signals, SoarConnectorRepository connectors,
                         SoarConnectorRegistry connectorRegistry) {
        this.playbooks = playbooks;
        this.versions = versions;
        this.runs = runs;
        this.dispatches = dispatches;
        this.nodes = nodes;
        this.events = events;
        this.approvals = approvals;
        this.temporal = temporal;
        this.validator = validator;
        this.mapper = mapper;
        this.readModels = new SoarReadModelMapper(versions, playbooks, mapper);
        this.playbookCommands = new SoarPlaybookCommandService(this);
        this.manualInputValidator = new SoarManualInputValidator(mapper);
        this.attempts = attempts;
        this.manualTasks = manualTasks;
        this.signals = signals;
        this.connectors = connectors;
        this.connectorRegistry = connectorRegistry;
        this.runCommands = new SoarRunCommandService(this);
        this.artifactCommands = new SoarArtifactCommandService(this);
        this.approvalCommands = new SoarApprovalCommandService(this);
        this.outboxCommands = new SoarOutboxCommandService(this);
        this.definitionPolicy = new SoarDefinitionPolicy(this);
        this.queries = new SoarQueryService(this);
    }

    /** Optional setter keeps isolated control-plane tests independent of artifact storage. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setArtifacts(SoarArtifactRepository artifacts) {
        this.artifacts = artifacts;
        this.artifactCommands.setArtifacts(artifacts);
    }

    /** Optional in preview; production supplies the configured S3-compatible store. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setArtifactStore(SoarArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
        this.artifactCommands.setArtifactStore(artifactStore);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeProperties(SoarRuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
    }

    /** Optional setter keeps compatibility/unit tests independent of the V14 vote projection. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApprovalDecisions(SoarApprovalDecisionRepository approvalDecisions) {
        this.approvalDecisions = approvalDecisions;
        this.readModels.setApprovalDecisions(approvalDecisions);
        this.approvalCommands.setApprovalDecisions(approvalDecisions);
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
     * unfiltered path stays a database Page; tag/risk predicates are applied
     * over the tenant-owned set so JSON tag semantics and the published risk
     * summary remain exact on both PostgreSQL and H2.
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

    void enqueueSignal(SoarRunEntity run, String type, Map<String, Object> payload) {
        if (signals == null) return;
        Instant now = Instant.now();
        String signalKey = signalKey(type, payload);
        java.util.Optional<SoarSignalOutboxEntity> existing = signals
                .findByTenantIdAndRunIdAndSignalTypeAndSignalKey(
                        run.getTenantId(), run.getId(), type, signalKey);
        // Isolated compatibility tests and rows written by V10 may not expose
        // the keyed projection. Reuse the legacy singleton only for the empty
        // key; keyed gates must never overwrite one another.
        if ((existing == null || existing.isEmpty()) && signalKey.isBlank()) {
            existing = signals.findByTenantIdAndRunIdAndSignalType(run.getTenantId(), run.getId(), type);
        }
        SoarSignalOutboxEntity signal = (existing == null ? java.util.Optional.<SoarSignalOutboxEntity>empty() : existing)
                .orElseGet(() -> {
                    SoarSignalOutboxEntity created = new SoarSignalOutboxEntity();
                    created.setId(UUID.randomUUID().toString()); created.setTenantId(run.getTenantId());
                    created.setRunId(run.getId()); created.setSignalType(type); created.setSignalKey(signalKey);
                    created.setAttempts(0);
                    created.setCreatedAt(now); return created;
                });
        signal.setPayloadJson(write(payload)); signal.setStatus("PENDING");
        signal.setNextAttemptAt(now); signal.setUpdatedAt(now); signals.save(signal);
    }

    /**
     * Signal delivery is at-least-once, but its durable business key must be
     * gate-specific. The payload remains the source of truth for old workers;
     * empty keys preserve compatibility with V10 rows and legacy signals.
     */
    private static String signalKey(String type, Map<String, Object> payload) {
        if (payload == null) return "";
        String field = switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "APPROVAL" -> "approvalKey";
            case "MANUAL_TASK", "UNKNOWN_RESOLUTION" -> "nodeId";
            default -> "signalKey";
        };
        Object value = payload.get(field);
        if (value == null && "APPROVAL".equalsIgnoreCase(type)) value = payload.get("approvalId");
        return value == null ? "" : limit(String.valueOf(value).trim(), 255);
    }



    void validateConnections(String definitionJson, String tenant) {
        definitionPolicy.validateConnections(definitionJson, tenant);
    }

    /** Per-connection readiness summary referenced by a definition. Used by the
     * publish result (design 6.4); never treated as a live connectivity test. */
    List<Map<String, Object>> connectionHealth(String definitionJson, String tenant) {
        return definitionPolicy.connectionHealth(definitionJson, tenant);
    }

    record ApprovalContext(String actionRef, String inputHash, String targetSnapshotJson) {
        private static ApprovalContext empty(String inputJson) {
            return new ApprovalContext("", sha256(inputJson == null ? "" : inputJson), "{}");
        }
    }

    /**
     * Build the pre-dispatch approval evidence from the immutable published
     * definition.  A high-risk run can contain several actions, therefore the
     * snapshot carries every risky action and uses MULTIPLE as the summary
     * actionRef instead of pretending that the first action is the only one.
     */
    ApprovalContext buildApprovalContext(String definitionJson, String inputJson) {
        return definitionPolicy.buildApprovalContext(definitionJson, inputJson);
    }

    String approvalPolicyJson(String targetSnapshotJson) {
        return definitionPolicy.approvalPolicyJson(targetSnapshotJson);
    }

    Map<String, Object> attemptView(SoarActionAttemptEntity attempt) {
        return readModels.attemptView(attempt);
    }

    Map<String, Object> manualTaskView(SoarManualTaskEntity task) {
        return readModels.manualTaskView(task);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> readMap(String json) {
        try { Map<String, Object> value = mapper.readValue(json == null ? "{}" : json, Map.class);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key).toLowerCase(Locale.ROOT);
                output.put(String.valueOf(key), name.contains("secret") || name.contains("token")
                        || name.contains("password") || name.contains("authorization") || name.equals("cookie")
                        ? "[REDACTED]" : redact(item));
            }); return output;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
    }

    /** Redact credential-shaped material even when an operator pasted it into
     * free-text evidence/reason rather than a structured JSON field. */
    static String redactFreeText(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    static Map<String, Object> castObjectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (int i = 0; i < 8; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(String.valueOf(value).hashCode()); }
    }

    Map<String, Object> playbookView(SoarPlaybookEntity playbook) {
        return readModels.playbookView(tenant(), playbook);
    }

    Map<String, Object> playbookView(SoarPlaybookEntity playbook, PlaybookVersionEntity draft) {
        return readModels.playbookView(playbook, draft);
    }

    Map<String, Object> versionView(PlaybookVersionEntity version) {
        return readModels.versionView(tenant(), version);
    }

    Map<String, Object> runView(SoarRunEntity run) {
        return readModels.runView(run);
    }

    Map<String, Object> nodeView(SoarNodeRunEntity node) {
        return readModels.nodeView(node);
    }

    Map<String, Object> artifactView(SoarArtifactEntity artifact) {
        return readModels.artifactView(artifact);
    }

    Map<String, Object> eventView(SoarRunEventEntity event) {
        return readModels.eventView(event);
    }

    Map<String, Object> approvalView(SoarApprovalEntity approval) {
        return readModels.approvalView(tenant(), approval);
    }

    @Transactional
    protected void appendEvent(String runId, String type, String actor, String summary, Map<String, Object> detail) {
        String tenant = tenant();
        // Event sequence numbers are part of the public SSE cursor contract.
        // Lock the owning run before reading the current tail so concurrent
        // activity completions cannot allocate the same (tenant, run, seq).
        java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(tenant, runId);
        if (locked == null || locked.isEmpty()) locked = runs.findByTenantIdAndId(tenant, runId);
        (locked == null ? java.util.Optional.<SoarRunEntity>empty() : locked)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
        SoarRunEventEntity event = new SoarRunEventEntity();
        event.setId(UUID.randomUUID().toString());
        event.setTenantId(tenant);
        event.setRunId(runId);
        long previousSequence = events.findTopByTenantIdAndRunIdOrderBySequenceNoDesc(tenant, runId)
                .map(SoarRunEventEntity::getSequenceNo)
                .orElseGet(() -> {
                    List<SoarRunEventEntity> legacyTail = events.findByTenantIdAndRunIdOrderBySequenceNoAsc(tenant, runId);
                    return legacyTail.isEmpty() ? 0L : legacyTail.get(legacyTail.size() - 1).getSequenceNo();
                });
        event.setSequenceNo(previousSequence + 1);
        event.setEventType(type);
        event.setActor(actor);
        event.setSummary(redactFreeText(limit(summary, 1024), 1024));
        event.setDetailJson(write(redact(detail == null ? Map.of() : detail)));
        event.setCreatedAt(Instant.now());
        events.save(event);
    }

    /**
     * A run projection is one-way once it has a terminal outcome.  Keep this
     * helper in the control-plane service (rather than relying on callers to
     * remember the enum list) so late human/API requests cannot resurrect a
     * PARTIALLY_SUCCEEDED run either.
     */
    static boolean terminalRunProjection(SoarRunEntity run) {
        return run != null && run.getStatus() != null && Set.of(
                SoarRunStatus.SUCCEEDED.name(), SoarRunStatus.PARTIALLY_SUCCEEDED.name(),
                SoarRunStatus.FAILED.name(), SoarRunStatus.TIMED_OUT.name(),
                SoarRunStatus.CANCELLED.name(), SoarRunStatus.SUPPRESSED.name(),
                SoarRunStatus.DEAD.name()).contains(run.getStatus());
    }

    SoarPlaybookEntity playbook(String id) {
        return playbooks.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
    }

    /**
     * Validate the immutable SUB_PLAYBOOK call graph at the publication
     * boundary. Runtime resolution remains a defensive check, but it must not
     * be the first place where existence, publication state or recursion is
     * discovered. Inline definitions are intentionally rejected for published
     * versions: a child is a versioned, tenant-scoped artifact and therefore
     * has to be pinned before it can be executed.
     */
    void validateSubPlaybookGraph(String tenant, PlaybookVersionEntity rootVersion) {
        definitionPolicy.validateSubPlaybookGraph(tenant, rootVersion);
    }

    PlaybookVersionEntity version(String playbookId, int versionNo) {
        playbook(playbookId);
        return versions.findByTenantIdAndPlaybookIdAndVersionNo(tenant(), playbookId, versionNo)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "version not found"));
    }

    SoarRunEntity run(String id) {
        return runs.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
    }

    SoarArtifactEntity artifact(String id) {
        if (artifacts == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                    "artifact storage adapter is not configured");
        }
        SoarArtifactEntity value = artifacts.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_ARTIFACT_NOT_FOUND", "artifact not found"));
        if (value.getExpiresAt() != null && value.getExpiresAt().isBefore(Instant.now())) {
            throw error(HttpStatus.GONE, "SOAR_ARTIFACT_EXPIRED", "artifact has expired");
        }
        return value;
    }

    /** Package-visible rollout gates used by the automation/control-plane
     * services as well as this service.  A null properties object is retained
     * for focused compatibility tests that construct the service directly. */
    void requireControlPlane() {
        if (runtimeProperties != null && !runtimeProperties.isControlPlaneEnabled()) {
            throw error(HttpStatus.GONE, "SOAR_CONTROL_PLANE_DISABLED",
                    "SOAR control plane is disabled for this deployment");
        }
    }

    void requireEvaluation() {
        requireExecution(tenant());
        if (runtimeProperties != null && !runtimeProperties.isEvaluationEnabled()) {
            throw error(HttpStatus.GONE, "SOAR_EVALUATION_DISABLED",
                    "SOAR event evaluation is disabled for this deployment");
        }
    }

    void requireExecution(String tenant) {
        requireControlPlane();
        if (runtimeProperties == null) return;
        if (!runtimeProperties.isExecutionEnabled()) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_EXECUTION_DISABLED",
                    "SOAR execution is paused by the deployment feature flag");
        }
        String configured = runtimeProperties.getExecutionTenantAllowlist();
        if (configured == null || configured.isBlank()) return;
        boolean allowed = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .anyMatch(value -> value.equals(tenant));
        if (!allowed) {
            throw error(HttpStatus.FORBIDDEN, "SOAR_TENANT_NOT_ENABLED",
                    "SOAR execution is not enabled for this tenant");
        }
    }

    String tenant() { return TenantContext.require(); }

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

    String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("cannot serialize SOAR data", failure); }
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

    JsonNode readTree(String value) {
        if (value == null || value.isBlank()) return mapper.createObjectNode();
        try { return mapper.readTree(value); }
        catch (JsonProcessingException ignored) { return mapper.createObjectNode(); }
    }

    /** Parse persisted JSON through the structured redactor before exposing it. */
    private JsonNode redactedTree(String value) {
        try {
            Object parsed = mapper.readValue(value == null || value.isBlank() ? "{}" : value, Object.class);
            return mapper.valueToTree(redact(parsed));
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private List<String> readList(String value) {
        try {
            JsonNode node = mapper.readTree(value == null ? "[]" : value);
            List<String> result = new ArrayList<>();
            if (node != null && node.isArray()) node.forEach(item -> result.add(item.asText()));
            return result;
        } catch (JsonProcessingException ignored) { return List.of(); }
    }

    boolean hasTag(SoarPlaybookEntity playbook, String requested) {
        return readList(playbook.getTagsJson()).stream()
                .anyMatch(tag -> requested.equalsIgnoreCase(tag));
    }

    boolean riskMatches(SoarPlaybookEntity playbook, String requested) {
        String risk = requested.toUpperCase(Locale.ROOT);
        List<PlaybookVersionEntity> history = versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(
                tenant(), playbook.getId());
        PlaybookVersionEntity published = history.stream()
                .filter(version -> SoarPlaybookVersionStatus.PUBLISHED.name().equals(version.getStatus()))
                .findFirst().orElse(null);
        if (published == null) return "NONE".equals(risk);
        JsonNode summary = readTree(published.getRiskSummaryJson());
        int high = summary.path("highRiskActionCount").asInt(0);
        int actions = summary.path("actionCount").asInt(0);
        return switch (risk) {
            case "HIGH", "CRITICAL" -> high > 0;
            case "LOW", "READ_ONLY" -> high == 0;
            case "MEDIUM" -> high == 0 && actions > 0;
            case "NONE" -> actions == 0;
            default -> false;
        };
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

    private static String nullSafe(String value) { return value == null ? "" : value; }

    static ResponseStatusException error(HttpStatus status, String code, String message) {
        return new ResponseStatusException(status, code + ": " + message);
    }
}
