package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.domain.SoarPlaybookVersionStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Publication and run-admission policy for immutable playbook definitions.
 * Keeps connector readiness, sub-playbook graph checks, and approval evidence
 * derived from the same definition snapshot.
 */
final class SoarDefinitionPolicy {

    private static final int MAX_APPROVAL_SNAPSHOT_BYTES = 64 * 1024;
    private static final int MAX_SUB_PLAYBOOK_DEPTH = 5;

    private final SoarService service;
    private final ObjectMapper mapper;
    private final SoarConnectorRepository connectors;
    private final SoarConnectorRegistry connectorRegistry;
    private final PlaybookVersionRepository versions;
    private final SoarPlaybookRepository playbooks;

    SoarDefinitionPolicy(SoarService service) {
        this.service = service;
        this.mapper = service.mapper;
        this.connectors = service.connectors;
        this.connectorRegistry = service.connectorRegistry;
        this.versions = service.versions;
        this.playbooks = service.playbooks;
    }

    void validateConnections(String definitionJson, String tenant) {
        if (connectors == null || connectorRegistry == null) {
            return;
        }
        try {
            JsonNode nodesJson = mapper.readTree(definitionJson).path("nodes");
            if (!nodesJson.isArray()) {
                return;
            }
            for (JsonNode node : nodesJson) {
                if (!"ACTION".equalsIgnoreCase(node.path("type").asText())) {
                    continue;
                }
                String actionRef = node.path("actionRef").asText("");
                var descriptor = connectorRegistry.descriptorForAction(actionRef).orElse(null);
                if (descriptor == null) {
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_ACTION_NOT_FOUND", "unknown action: " + actionRef);
                }
                if (service.runtimeProperties != null
                        && "production".equalsIgnoreCase(service.runtimeProperties.getMaturity())
                        && !descriptor.production()) {
                    throw error(HttpStatus.CONFLICT, "SOAR_CONNECTOR_NOT_PRODUCTION_READY",
                            "action connector is test-only until a production adapter is certified: " + actionRef);
                }
                String canonicalActionRef = connectorRegistry.canonicalActionRef(actionRef);
                String actionName = canonicalActionRef.substring(canonicalActionRef.indexOf('/') + 1)
                        .split("@")[0].toLowerCase(Locale.ROOT);
                var action = descriptor.actions().stream()
                        .filter(item -> item.id().equals(actionName)).findFirst().orElse(null);
                String connectionId = node.path("connectionRef").asText("");
                if (action != null && action.requiresConnection() && connectionId.isBlank()) {
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE",
                            "action requires connection: " + actionRef);
                }
                if (!connectionId.isBlank()) {
                    var connection = connectors.findByTenantIdAndId(tenant, connectionId)
                            .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE",
                                    "connection not found: " + connectionId));
                    if (!connection.isEnabled() || connection.getDeletedAt() != null) {
                        throw error(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE",
                                "connection is disabled: " + connectionId);
                    }
                    String connectorType = connection.getConnectorType().toLowerCase(Locale.ROOT);
                    boolean typeMatches = connectorType.equals(descriptor.id())
                            || ("net.firewall".equals(connectorType) && "firewall".equals(descriptor.id()));
                    if (!typeMatches) {
                        throw error(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE",
                                "connection type does not match action");
                    }
                }
            }
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID", "invalid definition");
        }
    }

    /** Readiness snapshot for referenced connections; this is not a live connectivity test. */
    List<Map<String, Object>> connectionHealth(String definitionJson, String tenant) {
        List<Map<String, Object>> health = new ArrayList<>();
        if (connectors == null || definitionJson == null || definitionJson.isBlank()) {
            return health;
        }
        try {
            JsonNode nodesJson = mapper.readTree(definitionJson).path("nodes");
            if (!nodesJson.isArray()) {
                return health;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode node : nodesJson) {
                if (!"ACTION".equalsIgnoreCase(node.path("type").asText(""))) {
                    continue;
                }
                String connectionId = node.path("connectionRef").asText("").trim();
                if (connectionId.isBlank() || !seen.add(connectionId)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("connectionRef", connectionId);
                // validateConnections has already checked existence and type.
                connectors.findByTenantIdAndId(tenant, connectionId).ifPresent(row -> {
                    entry.put("name", nullSafe(row.getName()));
                    entry.put("connectorType", nullSafe(row.getConnectorType()));
                    entry.put("enabled", row.isEnabled());
                    entry.put("deleted", row.getDeletedAt() != null);
                    entry.put("status", row.getStatus() == null
                            ? (row.isEnabled() ? "HEALTHY_UNKNOWN" : "DISABLED") : row.getStatus());
                    entry.put("lastTestAt", row.getLastTestAt());
                    entry.put("lastTestStatus", nullSafe(row.getLastTestStatus()));
                    entry.put("ready", row.isEnabled() && row.getDeletedAt() == null);
                    health.add(entry);
                });
            }
        } catch (Exception ignored) {
            // The definition was already validated before publish. Malformed
            // legacy JSON must not hide the publish result with a failure.
        }
        return health;
    }

    SoarService.ApprovalContext buildApprovalContext(String definitionJson, String inputJson) {
        List<Map<String, Object>> risky = new ArrayList<>();
        try {
            JsonNode nodesJson = mapper.readTree(definitionJson == null ? "{}" : definitionJson).path("nodes");
            if (nodesJson.isArray()) {
                for (JsonNode node : nodesJson) {
                    if (!"ACTION".equalsIgnoreCase(node.path("type").asText(""))) {
                        continue;
                    }
                    String actionRef = node.path("actionRef").asText("").trim();
                    if (!isHighRiskActionRef(actionRef)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("nodeId", SoarService.limit(node.path("id").asText(""), 64));
                    row.put("actionRef", SoarService.limit(actionRef, 255));
                    if (node.has("target")) {
                        row.put("target", service.redact(service.readMap(node.path("target").toString())));
                    }
                    if (node.path("connectionRef").isTextual()
                            && !node.path("connectionRef").asText("").isBlank()) {
                        row.put("connectionRef", SoarService.limit(node.path("connectionRef").asText(""), 255));
                    }
                    risky.add(row);
                    if (risky.size() >= 64) {
                        break;
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            // Admission validated the version already; keep bounded evidence
            // even if a legacy definition is malformed.
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("actions", risky);
        try {
            JsonNode root = mapper.readTree(definitionJson == null ? "{}" : definitionJson);
            JsonNode policy = root.path("approvalPolicy").isObject()
                    ? root.path("approvalPolicy") : root.path("policy");
            Map<String, Object> policySnapshot = approvalPolicySnapshot(policy);
            if (!policySnapshot.isEmpty()) {
                snapshot.put("approvalPolicy", policySnapshot);
            }
        } catch (JsonProcessingException ignored) {
            // The definition was already validated before admission.
        }
        String snapshotJson = service.write(snapshot);
        int bytes = snapshotJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_APPROVAL_SNAPSHOT_BYTES) {
            snapshotJson = service.write(Map.of("truncated", true, "sha256", SoarService.sha256(snapshotJson),
                    "originalBytes", bytes, "actionCount", risky.size()));
        }
        String actionRef = risky.isEmpty() ? "" : risky.size() == 1
                ? String.valueOf(risky.get(0).get("actionRef")) : "MULTIPLE";
        return new SoarService.ApprovalContext(actionRef,
                SoarService.sha256((inputJson == null ? "" : inputJson) + "\u0000" + snapshotJson), snapshotJson);
    }

    String approvalPolicyJson(String targetSnapshotJson) {
        JsonNode snapshot = service.readTree(targetSnapshotJson);
        JsonNode policy = snapshot.path("approvalPolicy");
        return policy.isObject() ? service.write(policy) : null;
    }

    void validateSubPlaybookGraph(String tenant, PlaybookVersionEntity rootVersion) {
        if (rootVersion == null || rootVersion.getDefinitionJson() == null) {
            return;
        }
        Set<String> visiting = new LinkedHashSet<>();
        validateSubPlaybookVersion(tenant, rootVersion, 0, visiting);
    }

    private void validateSubPlaybookVersion(String tenant, PlaybookVersionEntity version,
                                            int depth, Set<String> visiting) {
        String versionId = version.getId();
        String pathId = versionId == null || versionId.isBlank()
                ? version.getPlaybookId() + ":" + version.getVersionNo() : versionId;
        if (!visiting.add(pathId)) {
            throw error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_CYCLE",
                    "sub-playbook call graph contains a cycle at " + pathId);
        }
        JsonNode definition;
        try {
            definition = mapper.readTree(version.getDefinitionJson());
        } catch (Exception invalid) {
            visiting.remove(pathId);
            throw error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_DEFINITION_INVALID",
                    "sub-playbook definition is not valid JSON");
        }
        if (definition == null || !definition.isObject()) {
            visiting.remove(pathId);
            throw error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_DEFINITION_INVALID",
                    "sub-playbook definition must be an object");
        }
        JsonNode nodes = definition.path("nodes");
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                if (!"SUB_PLAYBOOK".equalsIgnoreCase(node.path("type").asText(""))) {
                    continue;
                }
                String nodeId = node.path("id").asText("sub-playbook");
                if (node.path("definition").isObject()) {
                    visiting.remove(pathId);
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_SUB_PLAYBOOK_INLINE_FORBIDDEN",
                            "SUB_PLAYBOOK " + nodeId + " must reference a published version");
                }
                String targetId = node.path("playbookVersionId").asText("").trim();
                if (targetId.isBlank()) {
                    targetId = node.path("config").path("playbookVersionId").asText("").trim();
                }
                if (targetId.isBlank()) {
                    visiting.remove(pathId);
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_SUB_PLAYBOOK_REFERENCE_REQUIRED",
                            "SUB_PLAYBOOK " + nodeId + " requires playbookVersionId");
                }
                if (depth >= MAX_SUB_PLAYBOOK_DEPTH) {
                    visiting.remove(pathId);
                    throw error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_DEPTH_EXCEEDED",
                            "sub-playbook call graph exceeds depth " + MAX_SUB_PLAYBOOK_DEPTH);
                }
                if (visiting.contains(targetId)) {
                    visiting.remove(pathId);
                    throw error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_CYCLE",
                            "sub-playbook call graph contains a cycle through " + targetId);
                }
                String referencedId = targetId;
                PlaybookVersionEntity target = versions.findByTenantIdAndId(tenant, referencedId)
                        .orElseThrow(() -> error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_NOT_FOUND",
                                "referenced playbook version does not exist: " + referencedId));
                if (!SoarPlaybookVersionStatus.PUBLISHED.name().equals(target.getStatus())) {
                    visiting.remove(pathId);
                    throw error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_NOT_PUBLISHED",
                            "referenced playbook version is not published: " + targetId);
                }
                SoarPlaybookEntity targetPlaybook = playbooks.findByTenantIdAndId(tenant, target.getPlaybookId())
                        .orElseThrow(() -> error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_NOT_FOUND",
                                "owning playbook does not exist for referenced version: " + referencedId));
                if (!"ACTIVE".equalsIgnoreCase(targetPlaybook.getStatus())) {
                    visiting.remove(pathId);
                    throw error(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_ARCHIVED",
                            "referenced playbook is archived: " + target.getPlaybookId());
                }
                validateSubPlaybookVersion(tenant, target, depth + 1, visiting);
            }
        }
        visiting.remove(pathId);
    }

    private Map<String, Object> approvalPolicySnapshot(JsonNode policy) {
        if (policy == null || !policy.isObject()) {
            return Map.of();
        }
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
        if (values == null || !values.isArray()) {
            return;
        }
        List<String> safe = new ArrayList<>();
        for (JsonNode value : values) {
            if (value != null && value.isTextual() && !value.asText().isBlank()
                    && value.asText().length() <= 128 && safe.size() < 64) {
                safe.add(value.asText().trim());
            }
        }
        if (!safe.isEmpty()) {
            target.put(canonical, safe);
        }
    }

    private boolean isHighRiskActionRef(String actionRef) {
        String value = actionRef == null ? "" : actionRef.toLowerCase(Locale.ROOT);
        if (value.contains("isolate") || value.contains("block") || value.contains("disable")
                || value.contains("delete") || value.contains("snapshot")) {
            return true;
        }
        if (connectorRegistry == null) {
            return false;
        }
        var descriptor = connectorRegistry.descriptorForAction(actionRef).orElse(null);
        if (descriptor == null) {
            return false;
        }
        String canonical = connectorRegistry.canonicalActionRef(actionRef);
        int slash = canonical.indexOf('/');
        String actionId = slash < 0 ? "" : canonical.substring(slash + 1).split("@")[0];
        return descriptor.actions().stream().filter(item -> item.id().equals(actionId))
                .anyMatch(item -> "HIGH".equalsIgnoreCase(item.riskLevel())
                        || "CRITICAL".equalsIgnoreCase(item.riskLevel()));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static ResponseStatusException error(HttpStatus status, String code, String message) {
        return SoarService.error(status, code, message);
    }
}
