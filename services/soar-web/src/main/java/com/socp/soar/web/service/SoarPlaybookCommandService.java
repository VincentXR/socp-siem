package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.domain.SoarPlaybookVersionStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Playbook and immutable-version command boundary for {@link SoarService}.
 *
 * <p>The public application service remains the transaction/audit façade used
 * by the controller.  This collaborator owns only playbook lifecycle rules,
 * so those rules do not get mixed with run control, approvals, outbox and
 * artifact code.</p>
 */
final class SoarPlaybookCommandService {

    private static final String DEFAULT_DEFINITION = "{\"schemaVersion\":\"soar.playbook\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

    private final SoarService service;

    SoarPlaybookCommandService(SoarService service) {
        this.service = service;
    }

    Map<String, Object> createPlaybook(String name, String description, List<String> tags) {
        service.requireControlPlane();
        String tenant = service.tenant();
        String actor = service.actor();
        Instant now = Instant.now();
        SoarPlaybookEntity playbook = new SoarPlaybookEntity();
        playbook.setId(UUID.randomUUID().toString());
        playbook.setTenantId(tenant);
        playbook.setName(service.required(name, "name", 128));
        playbook.setDescription(service.limit(description, 2048));
        playbook.setOwner(actor);
        playbook.setTagsJson(service.write(tags == null ? List.of() : tags));
        playbook.setStatus("ACTIVE");
        playbook.setCreatedAt(now);
        playbook.setUpdatedAt(now);
        service.playbooks.save(playbook);

        PlaybookVersionEntity draft = new PlaybookVersionEntity();
        draft.setId(UUID.randomUUID().toString());
        draft.setTenantId(tenant);
        draft.setPlaybookId(playbook.getId());
        draft.setVersionNo(1);
        draft.setStatus(SoarPlaybookVersionStatus.DRAFT.name());
        draft.setSchemaVersion(SoarDefinitionValidator.SCHEMA_VERSION);
        draft.setDefinitionJson(DEFAULT_DEFINITION);
        draft.setLayoutJson("{}");
        draft.setDefinitionHash(service.validator.canonicalHash(DEFAULT_DEFINITION));
        draft.setRiskSummaryJson("{\"highRiskActionCount\":0,\"actionCount\":0}");
        draft.setCreatedBy(actor);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        service.versions.save(draft);
        return service.playbookView(playbook, draft);
    }

    Map<String, Object> importDraft(String name, String description, List<String> tags,
                                     JsonNode definition, JsonNode layout) {
        if (definition == null || definition.isNull() || !definition.isObject()) {
            throw SoarService.error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID",
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

    Map<String, Object> updatePlaybook(String id, String name, String description,
                                        List<String> tags, String status, Long expectedRowVersion) {
        service.requireControlPlane();
        String tenant = service.tenant();
        SoarPlaybookEntity playbook = service.playbooks.findByTenantIdAndIdForUpdate(tenant, id)
                .or(() -> service.playbooks.findByTenantIdAndId(tenant, id))
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND",
                        "playbook not found"));
        if (expectedRowVersion != null && !expectedRowVersion.equals(playbook.getRowVersion())) {
            throw SoarService.error(HttpStatus.CONFLICT, "SOAR_PLAYBOOK_CONFLICT",
                    "playbook was changed by another editor");
        }
        if (name != null) playbook.setName(service.required(name, "name", 128));
        if (description != null) playbook.setDescription(service.limit(description.trim(), 2048));
        if (tags != null) {
            if (tags.size() > 32) {
                throw SoarService.error(HttpStatus.BAD_REQUEST, "SOAR_INPUT_INVALID",
                        "tags must contain at most 32 values");
            }
            List<String> normalized = tags.stream()
                    .map(value -> service.required(value, "tag", 64)).distinct().toList();
            playbook.setTagsJson(service.write(normalized));
        }
        if (status != null) {
            String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("ACTIVE", "ARCHIVED").contains(normalized)) {
                throw SoarService.error(HttpStatus.BAD_REQUEST, "SOAR_INPUT_INVALID",
                        "status must be ACTIVE or ARCHIVED");
            }
            playbook.setStatus(normalized);
        }
        playbook.setUpdatedAt(Instant.now());
        service.playbooks.save(playbook);
        return service.playbookView(playbook);
    }

    Map<String, Object> createVersion(String playbookId) {
        service.requireControlPlane();
        String tenant = service.tenant();
        service.playbooks.findByTenantIdAndIdForUpdate(tenant, playbookId)
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND",
                        "playbook not found"));
        List<PlaybookVersionEntity> history = service.versions
                .findByTenantIdAndPlaybookIdOrderByVersionNoDesc(tenant, playbookId);
        if (service.versions.findFirstByTenantIdAndPlaybookIdAndStatusOrderByVersionNoDesc(
                tenant, playbookId, SoarPlaybookVersionStatus.DRAFT.name()).isPresent()) {
            throw SoarService.error(HttpStatus.CONFLICT, "SOAR_DRAFT_ALREADY_EXISTS",
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
        draft.setDefinitionHash(service.validator.canonicalHash(draft.getDefinitionJson()));
        draft.setRiskSummaryJson(base == null ? "{\"highRiskActionCount\":0,\"actionCount\":0}"
                : base.getRiskSummaryJson());
        draft.setCreatedBy(service.actor());
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        service.versions.save(draft);
        return service.versionView(draft);
    }

    void validatePublishedVersionForAutomation(String versionId) {
        String tenant = service.tenant();
        PlaybookVersionEntity version = service.versions.findByTenantIdAndId(tenant, versionId)
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND",
                        "version not found"));
        if (!SoarPlaybookVersionStatus.PUBLISHED.name().equals(version.getStatus())) {
            throw SoarService.error(HttpStatus.CONFLICT, "SOAR_VERSION_NOT_PUBLISHED",
                    "automation rule can only reference a published version");
        }
        SoarPlaybookEntity playbook = service.playbooks.findByTenantIdAndId(tenant, version.getPlaybookId())
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND",
                        "playbook not found"));
        if (!"ACTIVE".equalsIgnoreCase(playbook.getStatus())) {
            throw SoarService.error(HttpStatus.CONFLICT, "SOAR_PLAYBOOK_ARCHIVED",
                    "archived playbooks cannot be enabled for automation");
        }
        DefinitionValidationResult checked = service.validator.validate(version.getDefinitionJson());
        if (!checked.valid()) {
            throw SoarService.error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID",
                    "published definition is no longer valid");
        }
        service.validateConnections(version.getDefinitionJson(), tenant);
        service.validateSubPlaybookGraph(tenant, version);
    }

    Map<String, Object> saveDraft(String playbookId, int versionNo, String definition,
                                  String layout, Long expectedRowVersion) {
        service.requireControlPlane();
        String tenant = service.tenant();
        service.playbooks.findByTenantIdAndIdForUpdate(tenant, playbookId)
                .or(() -> service.playbooks.findByTenantIdAndId(tenant, playbookId))
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND",
                        "playbook not found"));
        PlaybookVersionEntity version = service.versions.findByTenantIdAndPlaybookIdAndVersionNoForUpdate(
                        tenant, playbookId, versionNo)
                .or(() -> service.versions.findByTenantIdAndPlaybookIdAndVersionNo(tenant, playbookId, versionNo))
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND",
                        "version not found"));
        if (!SoarPlaybookVersionStatus.DRAFT.name().equals(version.getStatus())) {
            throw SoarService.error(HttpStatus.CONFLICT, "SOAR_VERSION_IMMUTABLE",
                    "only a draft can be edited");
        }
        if (expectedRowVersion != null && !expectedRowVersion.equals(version.getRowVersion())) {
            throw SoarService.error(HttpStatus.CONFLICT, "SOAR_VERSION_CONFLICT",
                    "draft was changed by another editor");
        }
        if (definition == null || definition.isBlank()) {
            throw SoarService.error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID",
                    "definition is required");
        }
        DefinitionValidationResult checked = service.validator.validate(definition);
        if (checked.errors().stream().anyMatch(issue ->
                "DEFINITION_SECRET_INLINE_FORBIDDEN".equals(issue.code())
                        || "ACTION_SECRET_INLINE_FORBIDDEN".equals(issue.code()))) {
            throw SoarService.error(HttpStatus.BAD_REQUEST, "SOAR_SECRET_INLINE_FORBIDDEN",
                    "playbook definitions cannot persist inline secrets; use a connection secretRef");
        }
        version.setDefinitionJson(definition);
        version.setLayoutJson(layout == null ? "{}" : service.limit(layout, SoarDefinitionValidator.MAX_BYTES));
        version.setDefinitionHash(checked.definitionHash() == null
                ? service.validator.canonicalHash(definition) : checked.definitionHash());
        version.setSchemaVersion(checked.schemaVersion() == null ? SoarDefinitionValidator.SCHEMA_VERSION
                : checked.schemaVersion());
        version.setRiskSummaryJson(service.write(Map.of(
                "highRiskActionCount", checked.highRiskActionCount(),
                "actionCount", checked.actionCount(),
                "valid", checked.valid())));
        version.setUpdatedAt(Instant.now());
        service.versions.save(version);
        return service.versionView(version);
    }

    DefinitionValidationResult validateVersion(String playbookId, int versionNo) {
        PlaybookVersionEntity version = service.version(playbookId, versionNo);
        DefinitionValidationResult checked = service.validator.validate(version.getDefinitionJson());
        if (checked.valid()) service.validateConnections(version.getDefinitionJson(), service.tenant());
        return checked;
    }

    Map<String, Object> dryRun(String playbookId, int versionNo,
                               Map<String, Object> subject, Map<String, Object> inputs) {
        PlaybookVersionEntity version = service.version(playbookId, versionNo);
        try {
            return new SoarDryRunEngine(service.mapper, service.validator)
                    .run(version.getDefinitionJson(), inputs, subject);
        } catch (IllegalArgumentException failure) {
            throw SoarService.error(HttpStatus.BAD_REQUEST, "SOAR_DRY_RUN_INVALID",
                    "dry-run input or definition is invalid");
        }
    }

    JsonNode definitionSchema() {
        return service.readTree("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                + "\"$id\":\"https://socp.local/schema/soar.playbook\","
                + "\"type\":\"object\",\"required\":[\"schemaVersion\",\"entryNodeId\",\"nodes\",\"edges\"],"
                + "\"properties\":{\"schemaVersion\":{\"const\":\"soar.playbook\"},"
                + "\"entryNodeId\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_-]{0,63}$\"},"
                + "\"nodes\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":200},"
                + "\"edges\":{\"type\":\"array\",\"maxItems\":600},"
                + "\"limits\":{\"type\":\"object\",\"properties\":{\"executionTimeout\":{\"type\":\"string\"},\"maxNodeExecutions\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500},\"maxParallelism\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}}}}}");
    }

    Map<String, Object> publish(String playbookId, int versionNo) {
        service.requireControlPlane();
        String tenant = service.tenant();
        SoarPlaybookEntity playbook = service.playbooks.findByTenantIdAndIdForUpdate(tenant, playbookId)
                .or(() -> service.playbooks.findByTenantIdAndId(tenant, playbookId))
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND",
                        "playbook not found"));
        PlaybookVersionEntity version = service.versions.findByTenantIdAndPlaybookIdAndVersionNoForUpdate(
                        tenant, playbookId, versionNo)
                .or(() -> service.versions.findByTenantIdAndPlaybookIdAndVersionNo(tenant, playbookId, versionNo))
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND",
                        "version not found"));
        if (!SoarPlaybookVersionStatus.DRAFT.name().equals(version.getStatus())) {
            throw SoarService.error(HttpStatus.CONFLICT, "SOAR_VERSION_IMMUTABLE",
                    "only a draft can be published");
        }
        DefinitionValidationResult checked = service.validator.validate(version.getDefinitionJson());
        if (!checked.valid()) {
            throw SoarService.error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID",
                    "definition has " + checked.errors().size() + " validation error(s)");
        }
        service.validateConnections(version.getDefinitionJson(), tenant);
        service.validateSubPlaybookGraph(tenant, version);
        Instant now = Instant.now();
        version.setStatus(SoarPlaybookVersionStatus.PUBLISHED.name());
        version.setPublishedBy(service.actor());
        version.setPublishedAt(now);
        version.setUpdatedAt(now);
        version.setDefinitionHash(checked.definitionHash());
        version.setRiskSummaryJson(service.write(Map.of(
                "highRiskActionCount", checked.highRiskActionCount(),
                "actionCount", checked.actionCount(), "valid", true)));
        service.versions.save(version);
        playbook.setLatestPublishedVersion(versionNo);
        playbook.setUpdatedAt(now);
        service.playbooks.save(playbook);
        Map<String, Object> view = service.versionView(version);
        view.put("connectionHealth", service.connectionHealth(version.getDefinitionJson(), tenant));
        return view;
    }

    Map<String, Object> deprecate(String playbookId, int versionNo) {
        service.requireControlPlane();
        String tenant = service.tenant();
        SoarPlaybookEntity playbook = service.playbooks.findByTenantIdAndIdForUpdate(tenant, playbookId)
                .or(() -> service.playbooks.findByTenantIdAndId(tenant, playbookId))
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND",
                        "playbook not found"));
        PlaybookVersionEntity version = service.versions.findByTenantIdAndPlaybookIdAndVersionNoForUpdate(
                        tenant, playbookId, versionNo)
                .or(() -> service.versions.findByTenantIdAndPlaybookIdAndVersionNo(tenant, playbookId, versionNo))
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND",
                        "version not found"));
        if (!SoarPlaybookVersionStatus.PUBLISHED.name().equals(version.getStatus())) {
            throw SoarService.error(HttpStatus.CONFLICT, "SOAR_VERSION_IMMUTABLE",
                    "only a published version can be deprecated");
        }
        version.setStatus(SoarPlaybookVersionStatus.DEPRECATED.name());
        version.setUpdatedAt(Instant.now());
        service.versions.save(version);
        if (Integer.valueOf(versionNo).equals(playbook.getLatestPublishedVersion())) {
            Integer replacement = service.versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(tenant, playbookId)
                    .stream()
                    .filter(candidate -> SoarPlaybookVersionStatus.PUBLISHED.name().equals(candidate.getStatus()))
                    .map(PlaybookVersionEntity::getVersionNo)
                    .findFirst().orElse(null);
            playbook.setLatestPublishedVersion(replacement);
            playbook.setUpdatedAt(Instant.now());
            service.playbooks.save(playbook);
        }
        return service.versionView(version);
    }
}
