package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.domain.v2.SoarPlaybookVersionStatus;
import com.socp.soar.web.domain.v2.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
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
import com.socp.soar.web.config.SoarRuntimeProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
import java.util.Comparator;
import java.util.Collection;
import java.util.Optional;

/** Application service for the durable SOAR 2.0 control plane. */
@Service
public class SoarV2Service {
    private static final int MAX_APPROVAL_SNAPSHOT_BYTES = 64 * 1024;
    private static final String DEFAULT_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\"," 
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

    private final SoarPlaybookRepository playbooks;
    private final PlaybookVersionRepository versions;
    private final SoarRunRepository runs;
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarNodeRunRepository nodes;
    private final SoarRunEventRepository events;
    private final SoarApprovalRepository approvals;
    private SoarApprovalDecisionRepository approvalDecisions;
    private final TemporalExecutor temporal;
    private final SoarDefinitionValidator validator;
    private final ObjectMapper mapper;
    private final SoarActionAttemptRepository attempts;
    private final SoarManualTaskRepository manualTasks;
    private final SoarSignalOutboxRepository signals;
    private final SoarConnectorRepository connectors;
    private final SoarConnectorRegistry connectorRegistry;
    private SoarArtifactRepository artifacts;
    private SoarRuntimeProperties runtimeProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarV2Service(SoarPlaybookRepository playbooks, PlaybookVersionRepository versions,
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
        this.attempts = attempts;
        this.manualTasks = manualTasks;
        this.signals = signals;
        this.connectors = connectors;
        this.connectorRegistry = connectorRegistry;
    }

    /** Optional setter keeps isolated control-plane tests independent of artifact storage. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setArtifacts(SoarArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeProperties(SoarRuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
    }

    /** Optional setter keeps compatibility/unit tests independent of the V14 vote projection. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApprovalDecisions(SoarApprovalDecisionRepository approvalDecisions) {
        this.approvalDecisions = approvalDecisions;
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_CREATE_PLAYBOOK", target = "t_soar_playbook")
    public Map<String, Object> createPlaybook(String name, String description, List<String> tags) {
        String tenant = tenant();
        String actor = actor();
        Instant now = Instant.now();
        SoarPlaybookEntity playbook = new SoarPlaybookEntity();
        playbook.setId(UUID.randomUUID().toString());
        playbook.setTenantId(tenant);
        playbook.setName(required(name, "name", 128));
        playbook.setDescription(limit(description, 2048));
        playbook.setOwner(actor);
        playbook.setTagsJson(write(tags == null ? List.of() : tags));
        playbook.setStatus("ACTIVE");
        playbook.setRowVersion(0L);
        playbook.setCreatedAt(now);
        playbook.setUpdatedAt(now);
        playbooks.save(playbook);

        PlaybookVersionEntity draft = new PlaybookVersionEntity();
        draft.setId(UUID.randomUUID().toString());
        draft.setTenantId(tenant);
        draft.setPlaybookId(playbook.getId());
        draft.setVersionNo(1);
        draft.setStatus(SoarPlaybookVersionStatus.DRAFT.name());
        draft.setSchemaVersion(SoarDefinitionValidator.SCHEMA_VERSION);
        draft.setDefinitionJson(DEFAULT_DEFINITION);
        draft.setLayoutJson("{}");
        draft.setDefinitionHash(validator.canonicalHash(DEFAULT_DEFINITION));
        draft.setRiskSummaryJson("{\"highRiskActionCount\":0,\"actionCount\":0}");
        draft.setCreatedBy(actor);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        draft.setRowVersion(0L);
        versions.save(draft);
        return playbookView(playbook, draft);
    }

    /** Import a definition as an editable draft. Import never publishes or schedules execution. */
    @Transactional
    @AuditOperation(action = "SOAR_V2_IMPORT_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> importDraft(String name, String description, List<String> tags,
                                            JsonNode definition, JsonNode layout) {
        if (definition == null || definition.isNull() || !definition.isObject()) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID",
                    "import requires a JSON object definition");
        }
        Map<String, Object> created = createPlaybook(name, description, tags);
        String playbookId = String.valueOf(created.get("id"));
        Map<String, Object> draft = saveDraft(playbookId, 1, definition.toString(),
                layout == null ? "{}" : layout.toString(), null);
        draft.put("playbookId", playbookId);
        draft.put("imported", true);
        return draft;
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listPlaybooks(Pageable pageable) {
        String tenant = tenant();
        return playbooks.findByTenantId(tenant, pageable).map(this::playbookView);
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
        String normalizedStatus = normalizeFilter(status);
        String normalizedOwner = normalizeFilter(owner);
        String normalizedTag = normalizeFilter(tag);
        String normalizedRisk = normalizeFilter(risk);
        if (normalizedTag == null && normalizedRisk == null) {
            return playbooks.searchByTenant(tenant(), normalizedStatus, normalizedOwner, null, pageable)
                    .map(this::playbookView);
        }
        List<SoarPlaybookEntity> candidates = playbooks.findByTenantId(tenant()).stream()
                .filter(row -> normalizedStatus == null || normalizedStatus.equalsIgnoreCase(row.getStatus()))
                .filter(row -> normalizedOwner == null || normalizedOwner.equalsIgnoreCase(row.getOwner()))
                .filter(row -> normalizedTag == null || hasTag(row, normalizedTag))
                .filter(row -> normalizedRisk == null || riskMatches(row, normalizedRisk))
                .sorted(Comparator.comparing(SoarPlaybookEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int pageNumber = Math.max(0, pageable.getPageNumber());
        int from = Math.min(candidates.size(), pageNumber * pageable.getPageSize());
        int to = Math.min(candidates.size(), from + pageable.getPageSize());
        List<Map<String, Object>> content = candidates.subList(from, to).stream()
                .map(this::playbookView).toList();
        return new PageImpl<>(content, pageable, candidates.size());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPlaybook(String id) {
        SoarPlaybookEntity playbook = playbook(id);
        List<PlaybookVersionEntity> history = versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(tenant(), id);
        Map<String, Object> result = playbookView(playbook);
        result.put("versions", history.stream().map(this::versionView).toList());
        return result;
    }

    /**
     * Change only playbook metadata.  Version definitions remain immutable;
     * archiving pauses future automation evaluation while existing runs retain
     * their published snapshot.
     */
    @Transactional
    @AuditOperation(action = "SOAR_V2_UPDATE_PLAYBOOK", target = "t_soar_playbook")
    public Map<String, Object> updatePlaybook(String id, String name, String description,
                                               List<String> tags, String status,
                                               Long expectedRowVersion) {
        String tenant = tenant();
        SoarPlaybookEntity playbook = playbooks.findByTenantIdAndIdForUpdate(tenant, id)
                .or(() -> playbooks.findByTenantIdAndId(tenant, id))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
        if (expectedRowVersion != null && !expectedRowVersion.equals(playbook.getRowVersion())) {
            throw error(HttpStatus.CONFLICT, "SOAR_PLAYBOOK_CONFLICT", "playbook was changed by another editor");
        }
        if (name != null) playbook.setName(required(name, "name", 128));
        if (description != null) playbook.setDescription(limit(description.trim(), 2048));
        if (tags != null) {
            if (tags.size() > 32) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_INPUT_INVALID", "tags must contain at most 32 values");
            }
            List<String> normalized = tags.stream().map(value -> required(value, "tag", 64)).distinct().toList();
            playbook.setTagsJson(write(normalized));
        }
        if (status != null) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ACTIVE", "ARCHIVED").contains(normalized)) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_INPUT_INVALID", "status must be ACTIVE or ARCHIVED");
            }
            playbook.setStatus(normalized);
        }
        playbook.setUpdatedAt(Instant.now());
        playbooks.save(playbook);
        return playbookView(playbook);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listVersions(String playbookId) {
        playbook(playbookId);
        return versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(tenant(), playbookId)
                .stream().map(this::versionView).toList();
    }

    /** Create a new immutable draft from the latest version after a publish. */
    @Transactional
    @AuditOperation(action = "SOAR_V2_CREATE_VERSION", target = "t_soar_playbook_version")
    public Map<String, Object> createVersion(String playbookId) {
        String tenant = tenant();
        // Serialize draft creation on the aggregate row.  The follow-up
        // status check is still kept as a clear conflict for callers, while
        // concurrent editors cannot both observe "no draft" and insert one.
        playbooks.findByTenantIdAndIdForUpdate(tenant, playbookId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
        List<PlaybookVersionEntity> history = versions
                .findByTenantIdAndPlaybookIdOrderByVersionNoDesc(tenant, playbookId);
        if (versions.findFirstByTenantIdAndPlaybookIdAndStatusOrderByVersionNoDesc(
                tenant, playbookId, SoarPlaybookVersionStatus.DRAFT.name()).isPresent()) {
            throw error(HttpStatus.CONFLICT, "SOAR_DRAFT_ALREADY_EXISTS",
                    "the playbook already has an editable draft");
        }
        PlaybookVersionEntity base = history.isEmpty() ? null : history.get(0);
        int next = base == null ? 1 : base.getVersionNo() + 1;
        Instant now = Instant.now();
        PlaybookVersionEntity draft = new PlaybookVersionEntity();
        draft.setId(UUID.randomUUID().toString());
        draft.setTenantId(tenant);
        draft.setPlaybookId(playbookId);
        draft.setVersionNo(next);
        draft.setStatus(SoarPlaybookVersionStatus.DRAFT.name());
        draft.setSchemaVersion(SoarDefinitionValidator.SCHEMA_VERSION);
        draft.setDefinitionJson(base == null ? DEFAULT_DEFINITION : base.getDefinitionJson());
        draft.setLayoutJson(base == null ? "{}" : base.getLayoutJson());
        draft.setDefinitionHash(validator.canonicalHash(draft.getDefinitionJson()));
        draft.setRiskSummaryJson(base == null ? "{\"highRiskActionCount\":0,\"actionCount\":0}" : base.getRiskSummaryJson());
        draft.setCreatedBy(actor());
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        draft.setRowVersion(0L);
        versions.save(draft);
        return versionView(draft);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getVersion(String playbookId, int versionNo) {
        PlaybookVersionEntity version = version(playbookId, versionNo);
        return versionView(version);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportVersion(String playbookId, int versionNo) {
        Map<String, Object> exported = versionView(version(playbookId, versionNo));
        exported.put("format", "soar.playbook/v2");
        exported.put("exportedAt", Instant.now());
        return exported;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getVersionById(String versionId) {
        return versions.findByTenantIdAndId(tenant(), versionId)
                .map(this::versionView)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "version not found"));
    }

    /**
     * Re-validate an automation target at enable time.  Published status is
     * immutable, but connector health/configuration and tenant bindings are
     * mutable; enabling a rule must not resurrect a stale reference.
     */
    @Transactional(readOnly = true)
    public void validatePublishedVersionForAutomation(String versionId) {
        PlaybookVersionEntity version = versions.findByTenantIdAndId(tenant(), versionId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "version not found"));
        if (!SoarPlaybookVersionStatus.PUBLISHED.name().equals(version.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_VERSION_NOT_PUBLISHED",
                    "automation rule can only reference a published version");
        }
        SoarPlaybookEntity playbook = playbooks.findByTenantIdAndId(tenant(), version.getPlaybookId())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
        if (!"ACTIVE".equalsIgnoreCase(playbook.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_PLAYBOOK_ARCHIVED",
                    "archived playbooks cannot be enabled for automation");
        }
        DefinitionValidationResult checked = validator.validate(version.getDefinitionJson());
        if (!checked.valid()) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID",
                    "published definition is no longer valid");
        }
        validateConnections(version.getDefinitionJson(), tenant());
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_SAVE_DRAFT", target = "t_soar_playbook_version")
    public Map<String, Object> saveDraft(String playbookId, int versionNo, String definition,
                                         String layout, Long expectedRowVersion) {
        String tenant = tenant();
        // Aggregate locking is deliberately ordered playbook -> version,
        // matching publish/deprecate/createVersion.  Keeping one order avoids
        // a deadlock when an editor saves while another request publishes.
        playbooks.findByTenantIdAndIdForUpdate(tenant, playbookId)
                .or(() -> playbooks.findByTenantIdAndId(tenant, playbookId))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
        PlaybookVersionEntity version = versions.findByTenantIdAndPlaybookIdAndVersionNoForUpdate(
                        tenant, playbookId, versionNo)
                .or(() -> versions.findByTenantIdAndPlaybookIdAndVersionNo(tenant, playbookId, versionNo))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "version not found"));
        if (!SoarPlaybookVersionStatus.DRAFT.name().equals(version.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_VERSION_IMMUTABLE", "only a draft can be edited");
        }
        if (expectedRowVersion != null && !expectedRowVersion.equals(version.getRowVersion())) {
            throw error(HttpStatus.CONFLICT, "SOAR_VERSION_CONFLICT", "draft was changed by another editor");
        }
        if (definition == null || definition.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID", "definition is required");
        }
        DefinitionValidationResult checked = validator.validate(definition);
        // Invalid drafts may be kept for editor feedback, but a submitted
        // credential-shaped value must never be written to the version table.
        // The validator reports this as a normal issue so callers can render
        // a field-level error; enforce the stronger persistence boundary here
        // before mutating the entity.
        if (checked.errors().stream().anyMatch(issue ->
                "DEFINITION_SECRET_INLINE_FORBIDDEN".equals(issue.code())
                        || "ACTION_SECRET_INLINE_FORBIDDEN".equals(issue.code()))) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_SECRET_INLINE_FORBIDDEN",
                    "playbook definitions cannot persist inline secrets; use a connection secretRef");
        }
        version.setDefinitionJson(definition);
        version.setLayoutJson(layout == null ? "{}" : limit(layout, SoarDefinitionValidator.MAX_BYTES));
        version.setDefinitionHash(checked.definitionHash() == null
                ? validator.canonicalHash(definition) : checked.definitionHash());
        version.setSchemaVersion(checked.schemaVersion() == null ? SoarDefinitionValidator.SCHEMA_VERSION
                : checked.schemaVersion());
        version.setRiskSummaryJson(write(Map.of(
                "highRiskActionCount", checked.highRiskActionCount(),
                "actionCount", checked.actionCount(),
                "valid", checked.valid())));
        version.setUpdatedAt(Instant.now());
        versions.save(version);
        return versionView(version);
    }

    @Transactional(readOnly = true)
    @AuditOperation(action = "SOAR_V2_VALIDATE_PLAYBOOK", target = "t_soar_playbook_version")
    public DefinitionValidationResult validateVersion(String playbookId, int versionNo) {
        PlaybookVersionEntity version = version(playbookId, versionNo);
        DefinitionValidationResult checked = validator.validate(version.getDefinitionJson());
        if (checked.valid()) validateConnections(version.getDefinitionJson(), tenant());
        return checked;
    }

    /**
     * Preview a draft without invoking connectors or writing execution rows.
     * Published versions are accepted for troubleshooting, but the response
     * remains visibly SIMULATED and cannot be mistaken for a real run.
     */
    @Transactional(readOnly = true)
    @AuditOperation(action = "SOAR_V2_DRY_RUN_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> dryRun(String playbookId, int versionNo,
                                      Map<String, Object> subject, Map<String, Object> inputs) {
        PlaybookVersionEntity version = version(playbookId, versionNo);
        try {
            return new SoarDryRunEngine(mapper, validator).run(version.getDefinitionJson(), inputs, subject);
        } catch (IllegalArgumentException failure) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_DRY_RUN_INVALID", "dry-run input or definition is invalid");
        }
    }

    /** Machine-readable schema used by the Workbench editor and import checks. */
    @Transactional(readOnly = true)
    public JsonNode definitionSchema() {
        return readTree("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                + "\"$id\":\"https://socp.local/schema/soar.playbook/v2\","
                + "\"type\":\"object\",\"required\":[\"schemaVersion\",\"entryNodeId\",\"nodes\",\"edges\"],"
                + "\"properties\":{\"schemaVersion\":{\"const\":\"soar.playbook/v2\"},"
                + "\"entryNodeId\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_-]{0,63}$\"},"
                + "\"nodes\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":200},"
                + "\"edges\":{\"type\":\"array\",\"maxItems\":600},"
                + "\"limits\":{\"type\":\"object\",\"properties\":{\"executionTimeout\":{\"type\":\"string\"},\"maxNodeExecutions\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500},\"maxParallelism\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}}}}}");
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_PUBLISH_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> publish(String playbookId, int versionNo) {
        String tenant = tenant();
        SoarPlaybookEntity playbook = playbooks.findByTenantIdAndIdForUpdate(tenant, playbookId)
                .or(() -> playbooks.findByTenantIdAndId(tenant, playbookId))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
        PlaybookVersionEntity version = versions.findByTenantIdAndPlaybookIdAndVersionNoForUpdate(
                        tenant, playbookId, versionNo)
                .or(() -> versions.findByTenantIdAndPlaybookIdAndVersionNo(tenant, playbookId, versionNo))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "version not found"));
        if (!SoarPlaybookVersionStatus.DRAFT.name().equals(version.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_VERSION_IMMUTABLE", "only a draft can be published");
        }
        DefinitionValidationResult checked = validator.validate(version.getDefinitionJson());
        if (!checked.valid()) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID",
                    "definition has " + checked.errors().size() + " validation error(s)");
        }
        validateConnections(version.getDefinitionJson(), tenant);
        Instant now = Instant.now();
        version.setStatus(SoarPlaybookVersionStatus.PUBLISHED.name());
        version.setPublishedBy(actor());
        version.setPublishedAt(now);
        version.setUpdatedAt(now);
        version.setDefinitionHash(checked.definitionHash());
        version.setRiskSummaryJson(write(Map.of(
                "highRiskActionCount", checked.highRiskActionCount(),
                "actionCount", checked.actionCount(), "valid", true)));
        versions.save(version);
        playbook.setLatestPublishedVersion(versionNo);
        playbook.setUpdatedAt(now);
        playbooks.save(playbook);
        return versionView(version);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_DEPRECATE_PLAYBOOK", target = "t_soar_playbook_version")
    public Map<String, Object> deprecate(String playbookId, int versionNo) {
        String tenant = tenant();
        SoarPlaybookEntity playbook = playbooks.findByTenantIdAndIdForUpdate(tenant, playbookId)
                .or(() -> playbooks.findByTenantIdAndId(tenant, playbookId))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
        PlaybookVersionEntity version = versions.findByTenantIdAndPlaybookIdAndVersionNoForUpdate(
                        tenant, playbookId, versionNo)
                .or(() -> versions.findByTenantIdAndPlaybookIdAndVersionNo(tenant, playbookId, versionNo))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "version not found"));
        if (!SoarPlaybookVersionStatus.PUBLISHED.name().equals(version.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_VERSION_IMMUTABLE", "only a published version can be deprecated");
        }
        version.setStatus(SoarPlaybookVersionStatus.DEPRECATED.name());
        version.setUpdatedAt(Instant.now());
        versions.save(version);
        if (Integer.valueOf(versionNo).equals(playbook.getLatestPublishedVersion())) {
            Integer replacement = versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(tenant(), playbookId)
                    .stream()
                    .filter(candidate -> SoarPlaybookVersionStatus.PUBLISHED.name().equals(candidate.getStatus()))
                    .map(PlaybookVersionEntity::getVersionNo)
                    .findFirst().orElse(null);
            playbook.setLatestPublishedVersion(replacement);
            playbook.setUpdatedAt(Instant.now());
            playbooks.save(playbook);
        }
        return versionView(version);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_QUEUE_RUN", target = "t_soar_run")
    public Map<String, Object> queueManualRun(String requestId, String versionId, Map<String, Object> subject,
                                              Map<String, Object> inputs) {
        String tenant = tenant();
        requestId = required(requestId, "requestId", 128);
        Map<String, Object> existing = runs.findByTenantIdAndRequestId(tenant, requestId)
                .map(this::runView).orElse(null);
        if (existing != null) {
            existing.put("duplicate", true);
            return existing;
        }
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
        run.setRowVersion(0L);
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
        outbox.setRowVersion(0L);
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

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listRuns(Pageable pageable) {
        return runs.findByTenantIdOrderByCreatedAtDesc(tenant(), pageable).map(this::runView);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listRuns(Pageable pageable, String status,
                                              String playbookVersionId, String triggerType,
                                              String requestedBy, Instant createdFrom,
                                              Instant createdTo) {
        return runs.searchByTenant(tenant(), normalizeFilter(status), normalizeFilter(playbookVersionId),
                normalizeFilter(triggerType), normalizeFilter(requestedBy), createdFrom, createdTo, pageable)
                .map(this::runView);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String id) {
        return runView(run(id));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listNodes(String runId) {
        run(runId);
        return nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(tenant(), runId).stream()
                .map(this::nodeView).toList();
    }

    /**
     * Paged node projection for large fan-out/FOREACH runs.  The list-shaped
     * overload remains the compatibility path used by older Workbench builds.
     */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listNodes(String runId, Pageable pageable) {
        run(runId);
        return nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(tenant(), runId, pageable)
                .map(this::nodeView);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listArtifacts(String runId) {
        run(runId);
        if (artifacts == null) return List.of();
        return artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(tenant(), runId)
                .stream().map(this::artifactView).toList();
    }

    /** Paged artifact projection; the list overload is retained for clients
     * that do not request pagination explicitly. */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listArtifacts(String runId, Pageable pageable) {
        run(runId);
        if (artifacts == null) {
            return Page.empty(pageable);
        }
        return artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(tenant(), runId, pageable)
                .map(this::artifactView);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getArtifact(String id) {
        SoarArtifactEntity artifact = artifact(id);
        return artifactView(artifact);
    }

    @Transactional(readOnly = true)
    public String getArtifactContent(String id) {
        SoarArtifactEntity artifact = artifact(id);
        if (artifact.getInlineJson() == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_CONTENT_UNAVAILABLE",
                    "artifact storage adapter cannot serve this artifact");
        }
        return artifact.getInlineJson();
    }

    /** Upload a bounded analyst artifact when no object-store adapter is configured. */
    @Transactional
    @AuditOperation(action = "SOAR_V2_UPLOAD_ARTIFACT", target = "t_soar_artifact")
    public Map<String, Object> uploadArtifact(String runId, String nodeRunId,
                                               String mediaType, String classification,
                                               JsonNode content) {
        SoarRunEntity owner = run(runId); // tenant authorization before accepting any content
        if (artifacts == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                    "artifact storage adapter is not configured");
        }
        String type = mediaType == null || mediaType.isBlank() ? "application/json" : mediaType.trim();
        if (type.length() > 255 || !type.matches("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+(?:;.*)?")) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_ARTIFACT_INVALID", "invalid mediaType");
        }
        String kind = classification == null || classification.isBlank()
                ? "INTERNAL" : classification.trim().toUpperCase();
        if (!Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED").contains(kind)) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_ARTIFACT_INVALID", "invalid classification");
        }
        // Artifacts are evidence, but they must not become a second secret
        // store.  Apply the same key-based redaction used for run inputs
        // before persisting inline content or exposing it through the content
        // endpoint.
        String boundNodeRunId = nodeRunId == null || nodeRunId.isBlank() ? null : limit(nodeRunId, 64);
        if (boundNodeRunId != null) {
            SoarNodeRunEntity node = nodes.findByTenantIdAndId(tenant(), boundNodeRunId)
                    .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "SOAR_NODE_RUN_NOT_FOUND",
                            "nodeRunId does not belong to this tenant"));
            if (!runId.equals(node.getRunId()) || !owner.getId().equals(node.getRunId())) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_NODE_RUN_MISMATCH",
                        "nodeRunId does not belong to the requested run");
            }
        }
        Object jsonValue = content == null ? mapper.createObjectNode() : mapper.convertValue(content, Object.class);
        Object sanitized = redact(jsonValue);
        String inline = write(sanitized);
        long size = inline.getBytes(StandardCharsets.UTF_8).length;
        if (size > 64 * 1024L) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_ARTIFACT_TOO_LARGE",
                    "inline artifact exceeds 64 KiB; configure an object-store adapter");
        }
        SoarArtifactEntity artifact = new SoarArtifactEntity();
        artifact.setId(UUID.randomUUID().toString().replace("-", ""));
        artifact.setTenantId(tenant());
        artifact.setRunId(runId);
        artifact.setNodeRunId(boundNodeRunId);
        artifact.setMediaType(type);
        artifact.setSizeBytes(size);
        artifact.setSha256(sha256(inline));
        artifact.setStorageRef("db://soar-artifacts/" + artifact.getId());
        artifact.setClassification(kind);
        artifact.setInlineJson(inline);
        artifact.setCreatedAt(Instant.now());
        artifact.setExpiresAt(Instant.now().plusSeconds(30L * 24 * 3600));
        SoarArtifactEntity saved = artifacts.save(artifact);
        appendEvent(runId, "ARTIFACT_UPLOADED", actor(), "SOAR artifact uploaded",
                Map.of("artifactId", saved.getId(), "sizeBytes", size));
        return artifactView(saved);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listNodeAttempts(String nodeRunId, Pageable pageable) {
        String tenant = tenant();
        java.util.Optional<SoarNodeRunEntity> lockedNode = nodes.findByTenantIdAndIdForUpdate(tenant, nodeRunId);
        if (lockedNode == null) lockedNode = nodes.findByTenantIdAndId(tenant, nodeRunId);
        SoarNodeRunEntity node = (lockedNode == null ? java.util.Optional.<SoarNodeRunEntity>empty() : lockedNode)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_NODE_RUN_NOT_FOUND", "node run not found"));
        return attempts.findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(tenant(), node.getId(), pageable)
                .map(this::attemptView);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEvents(String runId) {
        run(runId);
        return events.findByTenantIdAndRunIdOrderBySequenceNoAsc(tenant(), runId).stream()
                .map(this::eventView).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listEvents(String runId, long afterSequence, Pageable pageable) {
        run(runId);
        return events.findByTenantIdAndRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        tenant(), runId, Math.max(0, afterSequence), pageable)
                .map(this::eventView);
    }

    /** Safe retry creates a new durable run in the same execution series. */
    @Transactional
    @AuditOperation(action = "SOAR_V2_RETRY_RUN", target = "t_soar_run")
    public Map<String, Object> retryRun(String id, String reason) {
        SoarRunEntity original = run(id);
        if (!Set.of(SoarRunStatus.FAILED.name(), SoarRunStatus.ACTION_UNKNOWN.name(),
                SoarRunStatus.DEAD.name(), SoarRunStatus.TIMED_OUT.name()).contains(original.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RETRYABLE", "run is not in a retryable state");
        }
        // An ACTION_UNKNOWN node means the remote side effect may already have
        // happened.  Auto-retrying under the same execution series would risk
        // duplicating that effect, so the operator must first resolve the
        // unknown through SOAR_V2_RESOLVE_UNKNOWN (CONFIRMED_SUCCEEDED skips
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

    /** Explicit rerun intentionally gets a new execution series and idempotency keys. */
    @Transactional
    @AuditOperation(action = "SOAR_V2_RERUN_RUN", target = "t_soar_run")
    public Map<String, Object> rerun(String id, String reason, boolean confirm) {
        if (!confirm) throw error(HttpStatus.CONFLICT, "SOAR_RERUN_CONFIRMATION_REQUIRED",
                "rerun requires explicit confirmation");
        SoarRunEntity original = run(id);
        return cloneRun(original, "rerun-" + id + "-" + shortHash(String.valueOf(reason)),
                UUID.randomUUID().toString(), null, reason, true);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_RESOLVE_UNKNOWN", target = "t_soar_node_run")
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
        node.setStatus(normalized);
        node.setErrorCode(null);
        node.setErrorMessage(safeReason);
        node.setOutputJson(write(Map.of("resolution", normalized, "evidence", safeEvidence)));
        node.setUpdatedAt(Instant.now());
        nodes.save(node);
        appendEvent(node.getRunId(), "ACTION_UNKNOWN_RESOLVED", actor(),
                "Unknown action result was resolved", Map.of("nodeRunId", nodeRunId,
                        "resolution", normalized, "reason", redactFreeText(safeReason, 512)));
        java.util.Optional<SoarRunEntity> lockedOwner = runs.findByTenantIdAndIdForUpdate(tenant, node.getRunId());
        if (lockedOwner == null) lockedOwner = runs.findByTenantIdAndId(tenant, node.getRunId());
        SoarRunEntity owner = (lockedOwner == null ? java.util.Optional.<SoarRunEntity>empty() : lockedOwner)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
        boolean workflowAttached = owner.getTemporalWorkflowId() != null
                && !owner.getTemporalWorkflowId().isBlank();
        boolean workflowCanResume = workflowAttached && Set.of(
                SoarRunStatus.RUNNING.name(), SoarRunStatus.ACTION_UNKNOWN.name(),
                SoarRunStatus.WAITING_APPROVAL.name(), SoarRunStatus.WAITING_INPUT.name(),
                SoarRunStatus.CANCELLING.name()).contains(owner.getStatus());
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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listManualTasks(boolean pendingOnly) {
        List<SoarManualTaskEntity> rows = pendingOnly
                ? manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(tenant(), "PENDING")
                : manualTasks.findByTenantIdOrderByCreatedAtDesc(tenant());
        return rows.stream().map(this::manualTaskView).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listManualTasks(boolean pendingOnly, Pageable pageable) {
        if (pendingOnly) {
            List<SoarManualTaskEntity> all = manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(
                    tenant(), "PENDING");
            int from = Math.min(all.size(), Math.max(0, pageable.getPageNumber()) * pageable.getPageSize());
            int to = Math.min(all.size(), from + pageable.getPageSize());
            return new PageImpl<>(all.subList(from, to).stream().map(this::manualTaskView).toList(),
                    pageable, all.size());
        }
        return manualTasks.findByTenantIdOrderByCreatedAtDesc(tenant(), pageable).map(this::manualTaskView);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_COMPLETE_MANUAL_TASK", target = "t_soar_manual_task")
    public Map<String, Object> completeManualTask(String id, Map<String, Object> input) {
        String tenant = tenant();
        java.util.Optional<SoarManualTaskEntity> lockedTask = manualTasks.findByTenantIdAndIdForUpdate(tenant, id);
        if (lockedTask == null || lockedTask.isEmpty()) lockedTask = manualTasks.findByTenantIdAndId(tenant, id);
        SoarManualTaskEntity task = (lockedTask == null ? java.util.Optional.<SoarManualTaskEntity>empty() : lockedTask)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_MANUAL_TASK_NOT_FOUND", "manual task not found"));
        if (!"PENDING".equals(task.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_MANUAL_TASK_ALREADY_COMPLETED", "manual task is not pending");
        }
        validateManualInput(task.getFormSchemaJson(), input);
        Instant now = Instant.now();
        task.setInputJson(write(redact(input)));
        task.setStatus("COMPLETED");
        task.setCompletedBy(actor()); task.setCompletedAt(now); task.setUpdatedAt(now);
        manualTasks.save(task);
        SoarRunEntity owner = run(task.getRunId());
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

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        String tenant = tenant();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (SoarRunStatus status : SoarRunStatus.values()) {
            long count = runs.countByTenantIdAndStatus(tenant, status.name());
            if (count > 0) byStatus.put(status.name(), count);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runsByStatus", byStatus);
        out.put("dispatchBacklog", dispatches.countByTenantIdAndStatusAndNextAttemptAtLessThanEqual(
                tenant, "PENDING", Instant.now()));
        out.put("signalBacklog", signals == null ? 0 : signals.countByTenantIdAndStatus(tenant, "PENDING"));
        out.put("generatedAt", Instant.now());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadDispatches() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SoarDispatchOutboxEntity row : dispatches.findByTenantIdAndStatusOrderByUpdatedAtAsc(tenant(), "DEAD")) {
            result.add(Map.of("id", row.getId(), "runId", row.getRunId(), "status", row.getStatus(),
                    "attempts", row.getAttempts(), "lastError", redactFreeText(row.getLastError(), 2048),
                    "updatedAt", row.getUpdatedAt()));
        }
        if (signals != null) for (SoarSignalOutboxEntity row : signals.findByTenantIdAndStatusOrderByUpdatedAtAsc(tenant(), "DEAD")) {
            result.add(Map.of("id", row.getId(), "runId", row.getRunId(), "kind", "SIGNAL", "status", row.getStatus(),
                    "signalType", nullSafe(row.getSignalType()), "signalKey", nullSafe(row.getSignalKey()),
                    "attempts", row.getAttempts(), "lastError", redactFreeText(row.getLastError(), 2048),
                    "updatedAt", row.getUpdatedAt()));
        }
        return result;
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_REQUEUE_DEAD_OUTBOX", target = "t_soar_dispatch_outbox")
    public Map<String, Object> requeueDead(String id, String reason) {
        String why = redactFreeText(reason == null ? "" : reason, 1024);
        String tenant = tenant();
        Optional<SoarDispatchOutboxEntity> dispatch = dispatches.findByTenantIdAndId(tenant, id);
        if (dispatch != null && dispatch.isPresent()) {
            SoarDispatchOutboxEntity row = dispatch.get();
            if (!"DEAD".equals(row.getStatus())) {
                throw error(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD", "outbox is not dead");
            }
            Instant now = Instant.now();
            row.setStatus("PENDING"); row.setAttempts(0); row.setLastError("requeued: " + why);
            row.setNextAttemptAt(now); row.setUpdatedAt(now); dispatches.save(row);
            runs.findByTenantIdAndId(tenant, row.getRunId()).ifPresent(run -> {
                run.setStatus("QUEUED"); run.setUpdatedAt(now); runs.save(run);
            });
            return Map.of("id", id, "kind", "DISPATCH", "status", "PENDING");
        }
        // Dead-letter operations expose dispatch and signal rows through one
        // operator endpoint.  A signal used to be visible in the list but
        // impossible to requeue because this method only looked in the
        // dispatch table; resolve the same public id against both stores.
        if (signals != null) {
            Optional<SoarSignalOutboxEntity> signal = signals.findByTenantIdAndId(tenant, id);
            if (signal != null && signal.isPresent()) {
                SoarSignalOutboxEntity row = signal.get();
                if (!"DEAD".equals(row.getStatus())) {
                    throw error(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD", "outbox is not dead");
                }
                Instant now = Instant.now();
                row.setStatus("PENDING"); row.setAttempts(0); row.setLastError("requeued: " + why);
                row.setNextAttemptAt(now); row.setUpdatedAt(now); signals.save(row);
                return Map.of("id", id, "kind", "SIGNAL", "status", "PENDING",
                        "signalType", nullSafe(row.getSignalType()),
                        "signalKey", nullSafe(row.getSignalKey()));
            }
        }
        throw error(HttpStatus.NOT_FOUND, "SOAR_OUTBOX_NOT_FOUND", "outbox not found");
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_DISCARD_DEAD_OUTBOX", target = "t_soar_dispatch_outbox")
    public Map<String, Object> discardDead(String id, String reason) {
        String why = redactFreeText(required(reason, "reason", 2048), 2048);
        String tenant = tenant();
        Optional<SoarDispatchOutboxEntity> dispatch = dispatches.findByTenantIdAndId(tenant, id);
        if (dispatch != null && dispatch.isPresent()) {
            SoarDispatchOutboxEntity row = dispatch.get();
            if (!"DEAD".equals(row.getStatus())) {
                throw error(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD", "outbox is not dead");
            }
            Instant now = Instant.now();
            row.setStatus("DISCARDED"); row.setLastError("discarded: " + why);
            row.setUpdatedAt(now); dispatches.save(row);
            Optional<SoarRunEntity> lockedRun = runs.findByTenantIdAndIdForUpdate(tenant, row.getRunId());
            if (lockedRun == null) lockedRun = runs.findByTenantIdAndId(tenant, row.getRunId());
            lockedRun.ifPresent(run -> {
                if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED", "SUPPRESSED", "TIMED_OUT")
                        .contains(run.getStatus())) {
                    run.setStatus("SUPPRESSED"); run.setErrorCode("DISPATCH_DISCARDED");
                    run.setErrorMessage(why); run.setCompletedAt(now); run.setUpdatedAt(now); runs.save(run);
                }
            });
            return Map.of("id", id, "kind", "DISPATCH", "status", "DISCARDED");
        }
        if (signals != null) {
            Optional<SoarSignalOutboxEntity> signal = signals.findByTenantIdAndId(tenant, id);
            if (signal != null && signal.isPresent()) {
                SoarSignalOutboxEntity row = signal.get();
                if (!"DEAD".equals(row.getStatus())) {
                    throw error(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD", "outbox is not dead");
                }
                Instant now = Instant.now();
                row.setStatus("DISCARDED"); row.setLastError("discarded: " + why);
                row.setUpdatedAt(now); signals.save(row);
                // A dead human/unknown signal must not leave a run waiting
                // forever.  Discard is an explicit operator terminal choice,
                // so suppress the run and retain the reason in its projection.
                Optional<SoarRunEntity> lockedRun = runs.findByTenantIdAndIdForUpdate(tenant, row.getRunId());
                if (lockedRun == null) lockedRun = runs.findByTenantIdAndId(tenant, row.getRunId());
                lockedRun.ifPresent(run -> {
                    if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED", "SUPPRESSED", "TIMED_OUT")
                            .contains(run.getStatus())) {
                        run.setStatus("SUPPRESSED"); run.setErrorCode("SIGNAL_DISCARDED");
                        run.setErrorMessage(why); run.setCompletedAt(now); run.setUpdatedAt(now); runs.save(run);
                        appendEvent(run.getId(), "SIGNAL_DISCARDED", actor(),
                                "Dead signal discarded by operator", Map.of("signalId", id,
                                        "signalType", nullSafe(row.getSignalType())));
                    }
                });
                return Map.of("id", id, "kind", "SIGNAL", "status", "DISCARDED",
                        "signalType", nullSafe(row.getSignalType()),
                        "signalKey", nullSafe(row.getSignalKey()));
            }
        }
        throw error(HttpStatus.NOT_FOUND, "SOAR_OUTBOX_NOT_FOUND", "outbox not found");
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_CANCEL_RUN", target = "t_soar_run")
    public Map<String, Object> cancelRun(String id, String reason) {
        String safeReason = redactFreeText(reason == null ? "operator requested cancellation" : reason, 2048);
        SoarRunEntity run = run(id);
        String status = run.getStatus();
        boolean hasWorkflow = run.getTemporalWorkflowId() != null
                && !run.getTemporalWorkflowId().isBlank();
        if (SoarRunStatus.QUEUED.name().equals(status)
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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listApprovals() {
        return approvals.findByTenantIdOrderByCreatedAtDesc(tenant()).stream()
                .map(this::approvalView).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listApprovals(Pageable pageable) {
        return approvals.findByTenantIdOrderByCreatedAtDesc(tenant(), pageable).map(this::approvalView);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    @AuditOperation(action = "SOAR_V2_DECIDE_APPROVAL", target = "t_soar_approval")
    public Map<String, Object> decideApproval(String id, boolean approve, String decisionReason) {
        String tenant = tenant();
        SoarApprovalEntity approval = approvals.findByTenantIdAndIdForUpdate(tenant, id)
                .or(() -> approvals.findByTenantIdAndId(tenant, id))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_APPROVAL_NOT_FOUND", "approval not found"));
        SoarRunEntity run = runs.findByTenantIdAndIdForUpdate(tenant, approval.getRunId())
                .or(() -> runs.findByTenantIdAndId(tenant, approval.getRunId()))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "approval run not found"));
        if (!"PENDING".equals(approval.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_APPROVAL_ALREADY_DECIDED", "approval is already decided");
        }
        Instant now = Instant.now();
        if (approval.getExpiresAt() != null && approval.getExpiresAt().isBefore(now)) {
            expireApprovalLocked(approval, run, now);
            throw error(HttpStatus.CONFLICT, "SOAR_APPROVAL_EXPIRED", "approval has expired");
        }
        String approver = actor();
        String runRequester = run.getRequestedBy();
        boolean sameAsRunRequester = approver != null && runRequester != null
                && approver.equalsIgnoreCase(runRequester);
        boolean sameAsApprovalRequester = approver != null && approval.getRequestedBy() != null
                && !"workflow".equalsIgnoreCase(approval.getRequestedBy())
                && approver.equalsIgnoreCase(approval.getRequestedBy());
        boolean sameAsRecentEditor = false;
        if (versions != null && approver != null && run.getPlaybookVersionId() != null) {
            Optional<PlaybookVersionEntity> sourceVersion = versions.findByTenantIdAndId(
                    tenant, run.getPlaybookVersionId());
            if (sourceVersion != null && sourceVersion.isPresent()) {
                String editor = sourceVersion.get().getCreatedBy();
                sameAsRecentEditor = editor != null && approver.equalsIgnoreCase(editor);
            }
        }
        if (sameAsRunRequester || sameAsApprovalRequester || sameAsRecentEditor) {
            throw error(HttpStatus.FORBIDDEN, "SOAR_SELF_APPROVAL_FORBIDDEN",
                    "the requester or recent playbook editor cannot approve their own high-risk run");
        }
        if (!approvalPolicyAllows(approval)) {
            throw error(HttpStatus.FORBIDDEN, "SOAR_APPROVER_POLICY_FORBIDDEN",
                    "the current operator is not in the approval policy role/group allow-list");
        }
        String safeDecisionReason = redactFreeText(required(decisionReason, "decisionReason", 2048), 2048);
        int requiredApprovals = Math.max(1, approval.getRequiredApprovals());
        if (requiredApprovals > 1 && approvalDecisions == null) {
            // Never silently downgrade a multi-vote policy when the durable
            // decision projection is unavailable (for example during a bad
            // deployment or an isolated test wiring mistake).
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_APPROVAL_DECISION_STORE_UNAVAILABLE",
                    "multi-approval decision store is unavailable");
        }
        if (approvalDecisions != null) {
            java.util.Optional<SoarApprovalDecisionEntity> priorVote =
                    approvalDecisions.findByTenantIdAndApprovalIdAndActorId(tenant, approval.getId(), approver);
            if (priorVote != null && priorVote.isPresent()) {
                // A retried browser request from the same approver is
                // idempotent.  A second vote cannot increase the quorum.
                return approvalView(approval);
            }
        }
        recordApprovalDecision(tenant, approval.getId(), approver,
                approve ? "APPROVE" : "REJECT", safeDecisionReason, now);
        // The V14 decision projection is optional only for isolated/legacy
        // wiring.  A single-vote approval must still be able to complete in
        // that mode; otherwise a perfectly valid APPROVE would remain
        // PENDING forever because there is no durable vote store to count.
        int approvedVotes = approvalDecisions == null
                ? (approve ? 1 : 0)
                : countApprovedVotes(tenant, approval.getId());
        if (approve && approvedVotes < requiredApprovals) {
            // The gate remains pending until the policy quorum is reached.
            // Do not enqueue a Temporal signal or release the pre-dispatch
            // outbox on an intermediate vote.
            approval.setApprover(approver);
            approval.setDecisionReason(safeDecisionReason);
            approvals.save(approval);
            appendEvent(run.getId(), "APPROVAL_VOTE_RECORDED", approver,
                    "Approval vote recorded; quorum not reached",
                    Map.of("approvalId", id, "approvedVotes", approvedVotes,
                            "requiredApprovals", requiredApprovals));
            run.setUpdatedAt(now);
            runs.save(run);
            return approvalView(approval);
        }
        approval.setStatus(approve ? "APPROVED" : "REJECTED");
        approval.setApprover(approver);
        approval.setDecisionReason(safeDecisionReason);
        approval.setDecidedAt(now);
        approvals.save(approval);

        SoarDispatchOutboxEntity outbox = dispatches.findByTenantIdAndRunId(tenant, run.getId()).orElse(null);
        boolean attachedWorkflow = run.getTemporalWorkflowId() != null
                && !run.getTemporalWorkflowId().isBlank();
        if (attachedWorkflow) {
            // A node-level gate is already inside the durable Workflow. Do
            // not put its run back in the dispatch queue (which would race a
            // duplicate Workflow start); the signal worker resumes it and
            // the Activity projects RUNNING/terminal state afterwards.
            appendEvent(run.getId(), approve ? "APPROVAL_GRANTED" : "APPROVAL_REJECTED", approver,
                    approve ? "Approval granted; workflow signal queued" : "Approval rejected; workflow signal queued",
                    Map.of("approvalId", id, "approvedVotes", approvedVotes,
                            "requiredApprovals", requiredApprovals));
        } else if (approve) {
            // Pre-dispatch high-risk approval: release the durable outbox.
            run.setStatus(SoarRunStatus.QUEUED.name());
            if (outbox != null) {
                outbox.setStatus("PENDING");
                outbox.setNextAttemptAt(now);
                outbox.setUpdatedAt(now);
                dispatches.save(outbox);
            }
            appendEvent(run.getId(), "APPROVAL_GRANTED", approver, "Approval granted",
                    Map.of("approvalId", id, "approvedVotes", approvedVotes,
                            "requiredApprovals", requiredApprovals));
        } else {
            // Pre-dispatch rejection has no Workflow to receive a signal.
            run.setStatus(SoarRunStatus.SUPPRESSED.name());
            run.setCompletedAt(now);
            if (outbox != null) {
                outbox.setStatus("CANCELLED");
                outbox.setUpdatedAt(now);
                dispatches.save(outbox);
            }
            appendEvent(run.getId(), "APPROVAL_REJECTED", approver, "Approval rejected",
                    Map.of("approvalId", id, "approvedVotes", approvedVotes,
                            "requiredApprovals", requiredApprovals));
        }
        run.setUpdatedAt(now);
        runs.save(run);
        if (run.getTemporalWorkflowId() != null && !run.getTemporalWorkflowId().isBlank()) {
            enqueueSignal(run, "APPROVAL", Map.of("approve", approve, "approvalId", id,
                    "approvalKey", nullSafe(approval.getApprovalKey())));
        }
        return approvalView(approval);
    }

    /**
     * Idempotent janitor entry point for approvals that expire while no
     * operator is attempting a decision.  The caller supplies no tenant; the
     * authenticated/system tenant context still scopes every repository read.
     */
    @Transactional
    @AuditOperation(action = "SOAR_V2_EXPIRE_APPROVAL", target = "t_soar_approval")
    public boolean expireApproval(String id, Instant now) {
        String tenant = tenant();
        Instant at = now == null ? Instant.now() : now;
        java.util.Optional<SoarApprovalEntity> locked = approvals.findByTenantIdAndIdForUpdate(tenant, id);
        if (locked == null) locked = approvals.findByTenantIdAndId(tenant, id);
        if (locked == null || locked.isEmpty()) return false;
        SoarApprovalEntity row = locked.get();
        if (!"PENDING".equals(row.getStatus()) || row.getExpiresAt() == null
                || row.getExpiresAt().isAfter(at)) return false;
        java.util.Optional<SoarRunEntity> run = runs.findByTenantIdAndIdForUpdate(tenant, row.getRunId());
        if (run == null) run = runs.findByTenantIdAndId(tenant, row.getRunId());
        if (run == null || run.isEmpty()) return false;
        expireApprovalLocked(row, run.get(), at);
        return true;
    }

    private void expireApprovalLocked(SoarApprovalEntity approval, SoarRunEntity run, Instant now) {
        if (!"PENDING".equals(approval.getStatus())) return;
        approval.setStatus("EXPIRED");
        approval.setDecidedAt(now);
        approval.setDecisionReason("approval expired by system");
        recordApprovalDecision(approval.getTenantId(), approval.getId(), "system",
                "EXPIRE", "approval expired by system", now);
        approvals.save(approval);
        boolean attachedWorkflow = run.getTemporalWorkflowId() != null
                && !run.getTemporalWorkflowId().isBlank();
        if (SoarRunStatus.WAITING_APPROVAL.name().equals(run.getStatus()) && attachedWorkflow) {
            // Temporal owns node-gate timeout semantics. Mark only the gate;
            // the Workflow will take the explicit expired/rejected edge and
            // write the final Run projection.
            enqueueSignal(run, "APPROVAL", Map.of("approve", false,
                    "approvalId", approval.getId(), "approvalKey", nullSafe(approval.getApprovalKey()),
                    "expired", true));
            appendEvent(run.getId(), "APPROVAL_EXPIRED", "system",
                    "Approval expired; workflow signal queued", Map.of("approvalId", approval.getId()));
        } else if (SoarRunStatus.WAITING_APPROVAL.name().equals(run.getStatus())) {
            run.setStatus(SoarRunStatus.SUPPRESSED.name());
            run.setErrorCode("APPROVAL_EXPIRED");
            run.setErrorMessage("approval expired before a decision was recorded");
            run.setCompletedAt(now);
            run.setUpdatedAt(now);
            runs.save(run);
            dispatches.findByTenantIdAndRunId(run.getTenantId(), run.getId()).ifPresent(outbox -> {
                if (!"DISPATCHED".equals(outbox.getStatus())) {
                    outbox.setStatus("CANCELLED");
                    outbox.setUpdatedAt(now);
                    dispatches.save(outbox);
                }
            });
            appendEvent(run.getId(), "APPROVAL_EXPIRED", "system",
                    "Approval expired; run suppressed", Map.of("approvalId", approval.getId()));
        }
    }

    private void enqueueSignal(SoarRunEntity run, String type, Map<String, Object> payload) {
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
                    created.setRowVersion(0L);
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
        boolean approvalRequired = sourceChecked.highRiskActionCount() > 0;
        clone.setStatus(approvalRequired ? SoarRunStatus.WAITING_APPROVAL.name() : SoarRunStatus.QUEUED.name());
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
        clone.setCreatedAt(now); clone.setUpdatedAt(now); clone.setRowVersion(0L);
        runs.save(clone);
        SoarDispatchOutboxEntity outbox = new SoarDispatchOutboxEntity();
        outbox.setId(UUID.randomUUID().toString()); outbox.setTenantId(tenant); outbox.setRunId(runId);
        outbox.setStatus(approvalRequired ? "HOLD" : "PENDING"); outbox.setAttempts(0); outbox.setNextAttemptAt(now);
        outbox.setCreatedAt(now); outbox.setUpdatedAt(now); outbox.setRowVersion(0L); dispatches.save(outbox);
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

    @SuppressWarnings("unchecked")
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

    private void validateConnections(String definitionJson, String tenant) {
        if (connectors == null || connectorRegistry == null) return;
        try {
            JsonNode nodesJson = mapper.readTree(definitionJson).path("nodes");
            if (!nodesJson.isArray()) return;
            for (JsonNode node : nodesJson) {
                if (!"ACTION".equalsIgnoreCase(node.path("type").asText())) continue;
                String actionRef = node.path("actionRef").asText("");
                var descriptor = connectorRegistry.descriptorForAction(actionRef).orElse(null);
                if (descriptor == null) throw error(HttpStatus.BAD_REQUEST, "SOAR_ACTION_NOT_FOUND", "unknown action: " + actionRef);
                if (runtimeProperties != null
                        && "production".equalsIgnoreCase(runtimeProperties.getMaturity())
                        && !descriptor.production()) {
                    throw error(HttpStatus.CONFLICT, "SOAR_CONNECTOR_NOT_PRODUCTION_READY",
                            "action connector is test-only until a production adapter is certified: " + actionRef);
                }
                String canonicalActionRef = connectorRegistry.canonicalActionRef(actionRef);
                String actionName = canonicalActionRef.substring(canonicalActionRef.indexOf('/') + 1).split("@")[0].toLowerCase();
                var action = descriptor.actions().stream().filter(item -> item.id().equals(actionName)).findFirst().orElse(null);
                String connectionId = node.path("connectionRef").asText("");
                if (action != null && action.requiresConnection() && connectionId.isBlank()) {
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE", "action requires connection: " + actionRef);
                }
                if (!connectionId.isBlank()) {
                    var connection = connectors.findByTenantIdAndId(tenant, connectionId)
                            .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE", "connection not found: " + connectionId));
                    if (!connection.isEnabled() || connection.getDeletedAt() != null) {
                        throw error(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE", "connection is disabled: " + connectionId);
                    }
                    String connectorType = connection.getConnectorType().toLowerCase();
                    if (!connectorType.equals(descriptor.id()) && !("net.firewall".equals(connectorType) && "firewall".equals(descriptor.id()))) {
                        throw error(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE", "connection type does not match action");
                    }
                }
            }
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) { throw error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID", "invalid definition"); }
    }

    private record ApprovalContext(String actionRef, String inputHash, String targetSnapshotJson) {
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
    private ApprovalContext buildApprovalContext(String definitionJson, String inputJson) {
        List<Map<String, Object>> risky = new ArrayList<>();
        try {
            JsonNode nodesJson = mapper.readTree(definitionJson == null ? "{}" : definitionJson).path("nodes");
            if (nodesJson.isArray()) for (JsonNode node : nodesJson) {
                if (!"ACTION".equalsIgnoreCase(node.path("type").asText(""))) continue;
                String actionRef = node.path("actionRef").asText("").trim();
                if (!isHighRiskActionRef(actionRef)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nodeId", limit(node.path("id").asText(""), 64));
                row.put("actionRef", limit(actionRef, 255));
                if (node.has("target")) row.put("target", redact(readMap(node.path("target").toString())));
                if (node.path("connectionRef").isTextual()
                        && !node.path("connectionRef").asText("").isBlank()) {
                    row.put("connectionRef", limit(node.path("connectionRef").asText(""), 255));
                }
                risky.add(row);
                if (risky.size() >= 64) break;
            }
        } catch (Exception ignored) {
            // The version was already validated before a run can be queued;
            // preserve a bounded evidence object if legacy data is malformed.
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("actions", risky);
        // A pre-dispatch gate has no concrete APPROVAL node to carry its
        // policy.  Allow the immutable definition root to declare the same
        // role/group allow-list; absent policy intentionally preserves the
        // legacy soar:approve + self-approval behavior.
        try {
            JsonNode root = mapper.readTree(definitionJson == null ? "{}" : definitionJson);
            JsonNode policy = root.path("approvalPolicy").isObject()
                    ? root.path("approvalPolicy") : root.path("policy");
            Map<String, Object> policySnapshot = approvalPolicySnapshot(policy);
            if (!policySnapshot.isEmpty()) snapshot.put("approvalPolicy", policySnapshot);
        } catch (Exception ignored) {
            // The version was already validated before admission; a malformed
            // optional policy cannot make the bounded approval evidence grow.
        }
        String snapshotJson = write(snapshot);
        int bytes = snapshotJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_APPROVAL_SNAPSHOT_BYTES) {
            snapshotJson = write(Map.of("truncated", true, "sha256", sha256(snapshotJson),
                    "originalBytes", bytes, "actionCount", risky.size()));
        }
        String actionRef = risky.isEmpty() ? "" : risky.size() == 1
                ? String.valueOf(risky.get(0).get("actionRef")) : "MULTIPLE";
        return new ApprovalContext(actionRef, sha256((inputJson == null ? "" : inputJson)
                + "\u0000" + snapshotJson), snapshotJson);
    }

    /** Copy only bounded, non-secret approval policy fields into evidence. */
    private Map<String, Object> approvalPolicySnapshot(JsonNode policy) {
        if (policy == null || !policy.isObject()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        copyApprovalPolicyList(policy, result, "allowedRoles", "approverRoles");
        copyApprovalPolicyList(policy, result, "allowedGroups", "approverGroups");
        JsonNode required = policy.has("approvalsRequired") ? policy.get("approvalsRequired")
                : policy.get("requiredApprovals");
        if (required != null && required.isIntegralNumber() && required.canConvertToInt()) {
            result.put("approvalsRequired", Math.max(1, Math.min(20, required.asInt())));
        }
        return result;
    }

    private void copyApprovalPolicyList(JsonNode policy, Map<String, Object> target,
                                        String canonical, String alias) {
        JsonNode values = policy.path(canonical).isArray() ? policy.path(canonical) : policy.path(alias);
        if (values == null || !values.isArray()) return;
        List<String> safe = new ArrayList<>();
        for (JsonNode value : values) {
            if (value != null && value.isTextual() && !value.asText().isBlank()
                    && value.asText().length() <= 128 && safe.size() < 64) {
                safe.add(value.asText().trim());
            }
        }
        if (!safe.isEmpty()) target.put(canonical, safe);
    }

    private String approvalPolicyJson(String targetSnapshotJson) {
        JsonNode snapshot = readTree(targetSnapshotJson);
        JsonNode policy = snapshot.path("approvalPolicy");
        return policy.isObject() ? write(policy) : null;
    }

    private boolean isHighRiskActionRef(String actionRef) {
        String value = actionRef == null ? "" : actionRef.toLowerCase(Locale.ROOT);
        if (value.contains("isolate") || value.contains("block") || value.contains("disable")
                || value.contains("delete") || value.contains("snapshot")) return true;
        if (connectorRegistry == null) return false;
        var descriptor = connectorRegistry.descriptorForAction(actionRef).orElse(null);
        if (descriptor == null) return false;
        String canonical = connectorRegistry.canonicalActionRef(actionRef);
        int slash = canonical.indexOf('/');
        String actionId = slash < 0 ? "" : canonical.substring(slash + 1).split("@")[0];
        return descriptor.actions().stream().filter(item -> item.id().equals(actionId))
                .anyMatch(item -> "HIGH".equalsIgnoreCase(item.riskLevel())
                        || "CRITICAL".equalsIgnoreCase(item.riskLevel()));
    }

    private Map<String, Object> attemptView(SoarActionAttemptEntity attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", attempt.getId()); result.put("nodeRunId", attempt.getNodeRunId());
        result.put("attemptNo", attempt.getAttemptNo()); result.put("status", attempt.getStatus());
        result.put("requestHash", attempt.getRequestHash());
        result.put("remoteOperationId", attempt.getRemoteOperationId());
        result.put("connectionId", nullSafe(attempt.getConnectionId()));
        result.put("connectionRevision", attempt.getConnectionRevision());
        result.put("remoteTime", attempt.getRemoteTime());
        result.put("receipt", redactedTree(attempt.getReceiptJson())); result.put("errorCode", attempt.getErrorCode());
        result.put("errorMessage", redactFreeText(attempt.getErrorMessage(), 2048)); result.put("retryable", attempt.isRetryable());
        result.put("startedAt", attempt.getStartedAt()); result.put("completedAt", attempt.getCompletedAt());
        return result;
    }

    private Map<String, Object> manualTaskView(SoarManualTaskEntity task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId()); result.put("runId", task.getRunId()); result.put("nodeId", task.getNodeId());
        result.put("formSchema", readTree(task.getFormSchemaJson())); result.put("input", redactedTree(task.getInputJson()));
        result.put("assignee", nullSafe(task.getAssignee())); result.put("status", task.getStatus());
        result.put("dueAt", task.getDueAt()); result.put("completedBy", nullSafe(task.getCompletedBy()));
        result.put("completedAt", task.getCompletedAt()); result.put("createdAt", task.getCreatedAt());
        return result;
    }

    private void validateManualInput(String schemaJson, Map<String, Object> input) {
        final JsonNode schema;
        try {
            schema = mapper.readTree(schemaJson == null || schemaJson.isBlank() ? "{}" : schemaJson);
        } catch (Exception invalidSchema) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "manual task form schema is invalid");
        }
        if (schema == null || (!schema.isObject() && !schema.isBoolean())) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "manual task form schema is invalid");
        }
        if (input == null) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID", "manual input is required");
        }
        // MANUAL_TASK completion is a trust boundary.  Checking only the
        // required list lets a caller submit the wrong type (for example a
        // string where a boolean approval was requested) and makes the form
        // schema decorative.  This bounded JSON-Schema subset covers the
        // fields used by SOAR forms without evaluating arbitrary schemas.
        validateManualValue(mapper.valueToTree(input), schema, "$", 0);
        if (write(input).getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_MANUAL_INPUT_TOO_LARGE", "manual input exceeds 64 KiB");
        }
    }

    private void validateManualValue(JsonNode value, JsonNode schema, String path, int depth) {
        if (depth > 20) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "manual input exceeds the maximum nesting depth");
        }
        if (schema == null || schema.isNull() || schema.isMissingNode()) return;
        if (schema.isBoolean()) {
            if (!schema.asBoolean()) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "value is rejected by the form schema at " + path);
            }
            return;
        }
        if (!schema.isObject()) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "manual task form schema is invalid");
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean matched = false;
            for (JsonNode allowed : enumValues) {
                if (allowed.equals(value)) { matched = true; break; }
            }
            if (!matched) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "value is not allowed at " + path);
            }
        }
        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "value does not match the required constant at " + path);
        }
        JsonNode declaredType = schema.get("type");
        boolean typeMatches = true;
        if (declaredType != null && declaredType.isTextual()) {
            String type = declaredType.asText("").trim().toLowerCase(Locale.ROOT);
            typeMatches = !type.isBlank() && manualTypeMatches(value, type);
        } else if (declaredType != null && declaredType.isArray()) {
            typeMatches = false;
            for (JsonNode candidate : declaredType) {
                if (candidate.isTextual() && manualTypeMatches(value,
                        candidate.asText("").trim().toLowerCase(Locale.ROOT))) {
                    typeMatches = true;
                    break;
                }
            }
        } else if (declaredType != null && !declaredType.isNull()) {
            typeMatches = false;
        }
        if (!typeMatches) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "value has the wrong type at " + path);
        }
        if (value == null || value.isNull()) return;
        if (value.isTextual()) {
            int length = value.textValue().length();
            int min = schema.path("minLength").isIntegralNumber() ? schema.path("minLength").asInt(0) : 0;
            int max = schema.path("maxLength").isIntegralNumber() ? schema.path("maxLength").asInt(64 * 1024) : 64 * 1024;
            if (length < Math.max(0, min) || length > Math.min(64 * 1024, Math.max(0, max))) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "string length is outside the allowed range at " + path);
            }
            String pattern = schema.path("pattern").asText("");
            if (!pattern.isBlank()) {
                try {
                    if (!java.util.regex.Pattern.matches(pattern, value.textValue())) {
                        throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                                "value does not match the required pattern at " + path);
                    }
                } catch (java.util.regex.PatternSyntaxException invalid) {
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                            "manual task form schema contains an invalid pattern");
                }
            }
        }
        if (value.isNumber()) {
            java.math.BigDecimal numeric = value.decimalValue();
            JsonNode minimum = schema.get("minimum");
            JsonNode maximum = schema.get("maximum");
            JsonNode exclusiveMinimum = schema.get("exclusiveMinimum");
            JsonNode exclusiveMaximum = schema.get("exclusiveMaximum");
            if (minimum != null && minimum.isNumber() && numeric.compareTo(minimum.decimalValue()) < 0) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "number is below the minimum at " + path);
            }
            if (maximum != null && maximum.isNumber() && numeric.compareTo(maximum.decimalValue()) > 0) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "number is above the maximum at " + path);
            }
            if (exclusiveMinimum != null && exclusiveMinimum.isNumber()
                    && numeric.compareTo(exclusiveMinimum.decimalValue()) <= 0) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "number is at or below the exclusive minimum at " + path);
            }
            if (exclusiveMaximum != null && exclusiveMaximum.isNumber()
                    && numeric.compareTo(exclusiveMaximum.decimalValue()) >= 0) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "number is at or above the exclusive maximum at " + path);
            }
        }
        if (value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode field : required) {
                    String name = field.asText("");
                    if (!name.isBlank() && !value.has(name)) {
                        throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                                "required field is missing: " + name);
                    }
                }
            }
            JsonNode properties = schema.get("properties");
            boolean rejectAdditional = schema.has("additionalProperties")
                    && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode property = properties != null && properties.isObject()
                        ? properties.get(field.getKey()) : null;
                if (property == null && rejectAdditional) {
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                            "unknown field at " + path + "." + field.getKey());
                }
                if (property != null) {
                    validateManualValue(field.getValue(), property,
                            path + "." + field.getKey(), depth + 1);
                }
            }
        } else if (value.isArray()) {
            int size = value.size();
            int min = schema.path("minItems").isIntegralNumber() ? schema.path("minItems").asInt(0) : 0;
            int max = schema.path("maxItems").isIntegralNumber() ? schema.path("maxItems").asInt(1000) : 1000;
            if (size < Math.max(0, min) || size > Math.min(1000, Math.max(0, max))) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "array length is outside the allowed range at " + path);
            }
            JsonNode items = schema.get("items");
            if (items != null && items.isObject()) {
                for (int index = 0; index < size; index++) {
                    validateManualValue(value.get(index), items, path + "[" + index + "]", depth + 1);
                }
            }
        }
    }

    private static boolean manualTypeMatches(JsonNode value, String type) {
        if (value == null || value.isNull()) return "null".equals(type);
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try { Map<String, Object> value = mapper.readValue(json == null ? "{}" : json, Map.class);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private Object redact(Object value) {
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
    private static String redactFreeText(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static Map<String, Object> castObjectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (int i = 0; i < 8; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(String.valueOf(value).hashCode()); }
    }

    private Map<String, Object> playbookView(SoarPlaybookEntity playbook) {
        List<PlaybookVersionEntity> history = versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(
                tenant(), playbook.getId());
        // Version history is ordered newest-first, but the newest version is
        // commonly PUBLISHED.  `draftVersion` must point to the editable
        // draft specifically; exposing a published number here made the
        // Workbench attempt to edit an immutable version after a release.
        PlaybookVersionEntity draft = history.stream()
                .filter(version -> SoarPlaybookVersionStatus.DRAFT.name().equals(version.getStatus()))
                .findFirst().orElse(null);
        return playbookView(playbook, draft);
    }

    private Map<String, Object> playbookView(SoarPlaybookEntity playbook, PlaybookVersionEntity draft) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", playbook.getId());
        result.put("name", playbook.getName());
        result.put("description", playbook.getDescription());
        result.put("owner", playbook.getOwner());
        result.put("tags", readList(playbook.getTagsJson()));
        result.put("status", playbook.getStatus());
        result.put("latestPublishedVersion", playbook.getLatestPublishedVersion());
        result.put("draftVersion", draft == null ? null : draft.getVersionNo());
        result.put("createdAt", playbook.getCreatedAt());
        result.put("updatedAt", playbook.getUpdatedAt());
        return result;
    }

    private Map<String, Object> versionView(PlaybookVersionEntity version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", version.getId());
        result.put("playbookId", version.getPlaybookId());
        result.put("version", version.getVersionNo());
        result.put("status", version.getStatus());
        result.put("playbookStatus", playbooks.findByTenantIdAndId(tenant(), version.getPlaybookId())
                .map(SoarPlaybookEntity::getStatus).orElse("UNKNOWN"));
        result.put("schemaVersion", version.getSchemaVersion());
        result.put("definition", readTree(version.getDefinitionJson()));
        result.put("layout", readTree(version.getLayoutJson()));
        result.put("definitionHash", version.getDefinitionHash());
        result.put("riskSummary", readTree(version.getRiskSummaryJson()));
        result.put("createdBy", version.getCreatedBy());
        result.put("publishedBy", version.getPublishedBy());
        result.put("rowVersion", version.getRowVersion());
        result.put("createdAt", version.getCreatedAt());
        result.put("publishedAt", version.getPublishedAt());
        result.put("updatedAt", version.getUpdatedAt());
        return result;
    }

    private Map<String, Object> runView(SoarRunEntity run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("requestId", run.getRequestId());
        result.put("executionSeriesId", run.getExecutionSeriesId());
        result.put("playbookId", run.getPlaybookId());
        result.put("playbookVersionId", run.getPlaybookVersionId());
        result.put("playbookVersion", run.getPlaybookVersionNo());
        result.put("definitionHash", run.getDefinitionHash());
        result.put("triggerType", run.getTriggerType());
        result.put("subject", Map.of("type", nullSafe(run.getSubjectType()), "id", nullSafe(run.getSubjectId())));
        result.put("status", run.getStatus());
        result.put("temporalWorkflowId", run.getTemporalWorkflowId());
        result.put("temporalRunId", run.getTemporalRunId());
        if (run.getErrorCode() != null) result.put("errorCode", run.getErrorCode());
        if (run.getErrorMessage() != null) result.put("errorMessage", redactFreeText(run.getErrorMessage(), 2048));
        result.put("requestedBy", run.getRequestedBy());
        result.put("createdAt", run.getCreatedAt());
        result.put("startedAt", run.getStartedAt());
        result.put("completedAt", run.getCompletedAt());
        result.put("updatedAt", run.getUpdatedAt());
        return result;
    }

    private Map<String, Object> nodeView(SoarNodeRunEntity node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", node.getId());
        result.put("runId", node.getRunId());
        result.put("nodeId", node.getNodeId());
        result.put("iterationPath", node.getIterationPath());
        result.put("nodeType", node.getNodeType());
        result.put("status", node.getStatus());
        result.put("input", redactedTree(node.getInputJson()));
        result.put("output", redactedTree(node.getOutputJson()));
        result.put("idempotencyKey", nullSafe(node.getIdempotencyKey()));
        result.put("connectionId", nullSafe(node.getConnectionId()));
        result.put("connectionRevision", node.getConnectionRevision());
        result.put("errorCode", nullSafe(node.getErrorCode()));
        result.put("errorMessage", redactFreeText(node.getErrorMessage(), 2048));
        result.put("startedAt", node.getStartedAt());
        result.put("completedAt", node.getCompletedAt());
        result.put("updatedAt", node.getUpdatedAt());
        return result;
    }

    private Map<String, Object> artifactView(SoarArtifactEntity artifact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", artifact.getId());
        result.put("runId", artifact.getRunId());
        result.put("nodeRunId", nullSafe(artifact.getNodeRunId()));
        result.put("mediaType", artifact.getMediaType());
        result.put("sizeBytes", artifact.getSizeBytes());
        result.put("sha256", artifact.getSha256());
        result.put("storageRef", artifact.getStorageRef());
        result.put("classification", artifact.getClassification());
        result.put("expiresAt", artifact.getExpiresAt());
        result.put("createdAt", artifact.getCreatedAt());
        return result;
    }

    private Map<String, Object> eventView(SoarRunEventEntity event) {
        return Map.of("id", event.getId(), "runId", event.getRunId(), "nodeRunId", nullSafe(event.getNodeRunId()),
                "sequence", event.getSequenceNo(), "eventType", event.getEventType(),
                "actor", nullSafe(event.getActor()), "summary", event.getSummary(),
                "detail", redactedTree(event.getDetailJson()), "traceId", nullSafe(event.getTraceId()),
                "createdAt", event.getCreatedAt());
    }

    private Map<String, Object> approvalView(SoarApprovalEntity approval) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", approval.getId());
        result.put("runId", approval.getRunId());
        result.put("approvalKey", nullSafe(approval.getApprovalKey()));
        result.put("nodeRunId", nullSafe(approval.getNodeRunId()));
        result.put("actionRef", nullSafe(approval.getActionRef()));
        result.put("inputHash", nullSafe(approval.getInputHash()));
        result.put("targetSnapshot", redactedTree(approval.getTargetSnapshotJson()));
        result.put("approvalPolicy", redactedTree(approval.getPolicyJson()));
        result.put("requiredApprovals", approval.getRequiredApprovals());
        List<Map<String, Object>> voteViews = approvalVotes(approval);
        long approvedVotes = voteViews.stream()
                .filter(vote -> "APPROVE".equals(vote.get("decision"))).count();
        // V7/V13 rows predate the immutable V14 vote projection. Preserve a
        // truthful read model for a legacy gate that is already APPROVED
        // instead of displaying zero votes against a completed one-vote
        // policy.
        if (approvedVotes == 0 && "APPROVED".equalsIgnoreCase(approval.getStatus())) {
            approvedVotes = Math.min(1, Math.max(1, approval.getRequiredApprovals()));
        }
        result.put("approvedVotes", approvedVotes);
        result.put("decisions", voteViews);
        result.put("status", approval.getStatus());
        result.put("requestedBy", approval.getRequestedBy());
        result.put("approver", nullSafe(approval.getApprover()));
        result.put("reason", redactFreeText(approval.getReason(), 2048));
        result.put("decisionReason", redactFreeText(approval.getDecisionReason(), 2048));
        result.put("createdAt", approval.getCreatedAt());
        result.put("expiresAt", approval.getExpiresAt());
        result.put("decidedAt", approval.getDecidedAt());
        return result;
    }

    private List<Map<String, Object>> approvalVotes(SoarApprovalEntity approval) {
        if (approvalDecisions == null || approval == null || approval.getId() == null) return List.of();
        List<SoarApprovalDecisionEntity> rows = approvalDecisions
                .findByTenantIdAndApprovalIdOrderByCreatedAtAsc(tenant(), approval.getId());
        if (rows == null || rows.isEmpty()) return List.of();
        return rows.stream().map(vote -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", vote.getId());
            view.put("actor", vote.getActorId());
            view.put("decision", vote.getDecision());
            view.put("reason", redactFreeText(vote.getReason(), 2048));
            view.put("createdAt", vote.getCreatedAt());
            return view;
        }).toList();
    }

    private int countApprovedVotes(String tenant, String approvalId) {
        if (approvalDecisions == null) return 0;
        List<SoarApprovalDecisionEntity> rows = approvalDecisions
                .findByTenantIdAndApprovalIdOrderByCreatedAtAsc(tenant, approvalId);
        if (rows == null) return 0;
        return (int) rows.stream().filter(vote -> "APPROVE".equalsIgnoreCase(vote.getDecision())).count();
    }

    private void recordApprovalDecision(String tenant, String approvalId, String actor,
                                        String decision, String reason, Instant createdAt) {
        if (approvalDecisions == null) return;
        SoarApprovalDecisionEntity vote = new SoarApprovalDecisionEntity();
        vote.setId(UUID.randomUUID().toString());
        vote.setTenantId(tenant);
        vote.setApprovalId(approvalId);
        vote.setActorId(limit(actor == null ? "operator" : actor, 128));
        vote.setDecision(decision);
        vote.setReason(redactFreeText(reason, 2048));
        vote.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        approvalDecisions.save(vote);
    }

    @Transactional
    protected void appendEvent(String runId, String type, String actor, String summary, Map<String, Object> detail) {
        String tenant = tenant();
        // Event sequence numbers are part of the public SSE cursor contract.
        // Lock the owning run before reading the current tail so concurrent
        // activity completions cannot allocate the same (tenant, run, seq).
        runs.findByTenantIdAndIdForUpdate(tenant, runId)
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

    private SoarPlaybookEntity playbook(String id) {
        return playbooks.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
    }

    private PlaybookVersionEntity version(String playbookId, int versionNo) {
        playbook(playbookId);
        return versions.findByTenantIdAndPlaybookIdAndVersionNo(tenant(), playbookId, versionNo)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "version not found"));
    }

    private SoarRunEntity run(String id) {
        return runs.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
    }

    private SoarArtifactEntity artifact(String id) {
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

    private String tenant() { return TenantContext.require(); }

    private static String actor() {
        try {
            // Keep soar-web's core module independent of spring-security-core;
            // the platform starter may provide it at runtime. Reflection also
            // keeps local unit tests usable without a security filter chain.
            Class<?> holder = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Object context = holder.getMethod("getContext").invoke(null);
            Object authentication = context.getClass().getMethod("getAuthentication").invoke(context);
            if (authentication != null) {
                Object authenticated = authentication.getClass().getMethod("isAuthenticated").invoke(authentication);
                Object name = authentication.getClass().getMethod("getName").invoke(authentication);
                if (Boolean.TRUE.equals(authenticated) && name != null && !String.valueOf(name).isBlank()) {
                    return limit(String.valueOf(name), 128);
                }
            }
        } catch (RuntimeException ignored) { }
        catch (ReflectiveOperationException ignored) { }
        return "operator";
    }

    /**
     * Enforce the immutable role/group allow-list captured with the gate.
     * Gateway permission checks are deliberately not enough: approval policy
     * is evaluated again in this service, and an absent security context fails
     * closed whenever a definition actually specifies an allow-list.
     */
    private boolean approvalPolicyAllows(SoarApprovalEntity approval) {
        JsonNode policy = readTree(approval == null ? null : approval.getPolicyJson());
        if (!policy.isObject()) return true;
        Set<String> roles = policyPrincipals(policy, "allowedRoles", "approverRoles");
        Set<String> groups = policyPrincipals(policy, "allowedGroups", "approverGroups");
        if (roles.isEmpty() && groups.isEmpty()) return true;
        Set<String> authorities = securityAuthorities();
        if (authorities.isEmpty()) return false;
        return matchesPrincipal(authorities, roles, false)
                || matchesPrincipal(authorities, groups, true);
    }

    private static Set<String> policyPrincipals(JsonNode policy, String canonical, String alias) {
        JsonNode values = policy.path(canonical).isArray() ? policy.path(canonical) : policy.path(alias);
        Set<String> result = new java.util.LinkedHashSet<>();
        if (values == null || !values.isArray()) return result;
        for (JsonNode value : values) {
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText().trim().toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static boolean matchesPrincipal(Set<String> authorities, Set<String> expected, boolean group) {
        if (expected.isEmpty()) return false;
        for (String wantedRaw : expected) {
            String wanted = principalWithoutPrefix(wantedRaw, group ? "GROUP_" : "ROLE_");
            boolean explicitlyOtherKind = group ? wantedRaw.startsWith("ROLE_") : wantedRaw.startsWith("GROUP_");
            if (explicitlyOtherKind) continue;
            for (String actualRaw : authorities) {
                String actual = actualRaw.toUpperCase(Locale.ROOT);
                if (group && actual.startsWith("ROLE_")) continue;
                if (!group && actual.startsWith("GROUP_")) continue;
                if (principalWithoutPrefix(actual, group ? "GROUP_" : "ROLE_").equals(wanted)) return true;
            }
        }
        return false;
    }

    private static String principalWithoutPrefix(String value, String prefix) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith(prefix) ? normalized.substring(prefix.length()) : normalized;
    }

    /** Read authorities and common OIDC/SAML group claims without coupling the
     * SOAR module's compile-time surface to spring-security-core. */
    private static Set<String> securityAuthorities() {
        Set<String> result = new java.util.LinkedHashSet<>();
        try {
            Class<?> holder = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Object context = holder.getMethod("getContext").invoke(null);
            Object authentication = context == null ? null
                    : context.getClass().getMethod("getAuthentication").invoke(context);
            if (authentication == null) return result;
            collectAuthorityObjects(authentication.getClass().getMethod("getAuthorities").invoke(authentication), result);
            Object principal = invokeNoArg(authentication, "getPrincipal");
            collectPrincipalClaims(principal, result);
            collectPrincipalClaims(invokeNoArg(authentication, "getTokenAttributes"), result);
        } catch (RuntimeException ignored) {
            // No security filter chain in an isolated/unit-test invocation.
        } catch (ReflectiveOperationException ignored) {
            // Keep the production contract fail-closed when the provider is absent.
        }
        return result;
    }

    private static void collectPrincipalClaims(Object principal, Set<String> target) {
        if (principal == null) return;
        collectAuthorityObjects(invokeNoArg(principal, "getGroups"), target);
        collectAuthorityObjects(invokeNoArg(principal, "getRoles"), target);
        Object attributes = principal instanceof Map<?, ?> ? principal : invokeNoArg(principal, "getAttributes");
        if (attributes == null) attributes = invokeNoArg(principal, "getClaims");
        if (!(attributes instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (Set.of("groups", "group", "roles", "role", "authorities", "authority").contains(key)) {
                collectAuthorityObjects(entry.getValue(), target);
            }
        }
    }

    private static void collectAuthorityObjects(Object value, Set<String> target) {
        if (value == null) return;
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) collectAuthorityObjects(item, target);
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                collectAuthorityObjects(java.lang.reflect.Array.get(value, index), target);
            }
            return;
        }
        String authority = value instanceof CharSequence
                ? value.toString() : String.valueOf(invokeNoArg(value, "getAuthority"));
        if (authority != null && !authority.isBlank() && !"null".equalsIgnoreCase(authority)) {
            target.add(authority.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static Object invokeNoArg(Object target, String method) {
        if (target == null) return null;
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (RuntimeException | ReflectiveOperationException ignored) { return null; }
    }

    private static String text(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) return null;
        String value = String.valueOf(map.get(key)).trim();
        return value.isBlank() ? null : limit(value, 255);
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("cannot serialize SOAR data", failure); }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format("%02x", item));
            return out.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private JsonNode readTree(String value) {
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

    private boolean hasTag(SoarPlaybookEntity playbook, String requested) {
        return readList(playbook.getTagsJson()).stream()
                .anyMatch(tag -> requested.equalsIgnoreCase(tag));
    }

    private boolean riskMatches(SoarPlaybookEntity playbook, String requested) {
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

    private static String normalizeFilter(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw error(HttpStatus.BAD_REQUEST, "SOAR_INPUT_INVALID", field + " is required");
        return limit(value.trim(), max);
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }

    private static ResponseStatusException error(HttpStatus status, String code, String message) {
        return new ResponseStatusException(status, code + ": " + message);
    }
}
