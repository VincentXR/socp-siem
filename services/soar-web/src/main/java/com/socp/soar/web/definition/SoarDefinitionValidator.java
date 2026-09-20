package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.soar.web.domain.DefinitionIssue;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.domain.SoarNodeType;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural validator for the safe SOAR graph subset. */
@Component
public class SoarDefinitionValidator {
    public static final String SCHEMA_VERSION = "soar.playbook";
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_NODES = 200;
    public static final int MAX_NODE_EXECUTIONS = 500;
    public static final int MAX_PARALLELISM = 10;
    /** Manual forms accept only a conservative, bounded regular-expression subset. */
    public static final int MAX_MANUAL_PATTERN_LENGTH = 256;

    private final ObjectMapper mapper;
    private final ObjectMapper canonicalMapper;
    private final SoarActionContractValidator actionContracts;
    private final SoarNodeValidator nodeValidator;

    public SoarDefinitionValidator(ObjectMapper mapper) {
        this(mapper, null);
    }

    /** Spring wiring uses the runtime registry as the source of action schema
     * and target policy; the one-argument constructor remains useful for
     * hermetic validator tests. */
    @Autowired
    public SoarDefinitionValidator(ObjectMapper mapper, SoarConnectorRegistry connectorRegistry) {
        this.mapper = mapper;
        this.actionContracts = new SoarActionContractValidator(mapper, connectorRegistry);
        this.nodeValidator = new SoarNodeValidator(connectorRegistry, actionContracts);
        this.canonicalMapper = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public DefinitionValidationResult validate(String definition) {
        List<DefinitionIssue> errors = new ArrayList<>();
        List<DefinitionIssue> warnings = new ArrayList<>();
        if (definition == null || definition.isBlank()) {
            errors.add(DefinitionIssue.error("DEFINITION_REQUIRED", null, "", "definition is required"));
            return result(errors, warnings, null, null, 0, 0, 0);
        }
        if (definition.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            errors.add(DefinitionIssue.error("DEFINITION_TOO_LARGE", null, "",
                    "definition exceeds 256 KiB"));
            return result(errors, warnings, null, null, 0, 0, 0);
        }

        JsonNode root;
        String hash;
        try {
            root = mapper.readTree(definition);
            hash = sha256(canonicalMapper.writeValueAsBytes(root));
        } catch (Exception failure) {
            errors.add(DefinitionIssue.error("DEFINITION_NOT_JSON", null, "",
                    "definition must be valid JSON"));
            return result(errors, warnings, null, null, 0, 0, 0);
        }
        if (root == null || !root.isObject()) {
            errors.add(DefinitionIssue.error("DEFINITION_NOT_OBJECT", null, "",
                    "definition root must be an object"));
            return result(errors, warnings, null, hash, 0, 0, 0);
        }
        String schemaVersion = text(root, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            errors.add(DefinitionIssue.error("UNSUPPORTED_SCHEMA_VERSION", null, "/schemaVersion",
                    "schemaVersion must be " + SCHEMA_VERSION));
        }
        String entry = text(root, "entryNodeId");
        if (entry.isBlank()) {
            errors.add(DefinitionIssue.error("ENTRY_REQUIRED", null, "/entryNodeId",
                    "entryNodeId is required"));
        }
        JsonNode nodes = root.get("nodes");
        JsonNode edges = root.get("edges");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            errors.add(DefinitionIssue.error("NODES_REQUIRED", null, "/nodes",
                    "nodes must be a non-empty array"));
            return result(errors, warnings, schemaVersion, hash, 0, 0, 0);
        }
        if (containsSensitiveKey(root)) {
            errors.add(DefinitionIssue.error("DEFINITION_SECRET_INLINE_FORBIDDEN", null, "/",
                    "playbook definitions cannot contain secret, token, password or authorization values; use a connection secretRef"));
        }
        if (root.has("limits") && !root.path("limits").isObject()) {
            errors.add(DefinitionIssue.error("DEFINITION_LIMITS_INVALID", null, "/limits",
                    "definition limits must be an object"));
        }
        SoarExecutionPolicyValidator.validateRootApprovalPolicy(root, errors);
        if (nodes.size() > MAX_NODES) {
            errors.add(DefinitionIssue.error("TOO_MANY_NODES", null, "/nodes",
                    "at most " + MAX_NODES + " nodes are allowed"));
        }
        if (edges == null || !edges.isArray()) {
            errors.add(DefinitionIssue.error("EDGES_REQUIRED", null, "/edges",
                    "edges must be an array"));
        }

        Map<String, SoarNodeType> types = new HashMap<>();
        Map<String, JsonNode> nodeDefinitions = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Integer> outgoing = new HashMap<>();
        Set<String> starts = new HashSet<>();
        Set<String> ends = new HashSet<>();
        int actionCount = 0;
        int highRisk = 0;
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            String path = "/nodes/" + i;
            if (node == null || !node.isObject()) {
                errors.add(DefinitionIssue.error("NODE_NOT_OBJECT", null, path, "node must be an object"));
                continue;
            }
            String id = text(node, "id");
            if (id.isBlank() || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
                errors.add(DefinitionIssue.error("NODE_ID_INVALID", id, path + "/id",
                        "node id must match [A-Za-z][A-Za-z0-9_-]{0,63}"));
                continue;
            }
            if (types.containsKey(id)) {
                errors.add(DefinitionIssue.error("NODE_ID_DUPLICATE", id, path + "/id",
                        "node id is duplicated"));
                continue;
            }
            SoarNodeType type = SoarNodeType.parse(text(node, "type"));
            if (type == null) {
                errors.add(DefinitionIssue.error("NODE_TYPE_INVALID", id, path + "/type",
                        "unsupported node type"));
                continue;
            }
            types.put(id, type);
            nodeDefinitions.put(id, node);
            graph.put(id, new ArrayList<>());
            incoming.put(id, 0);
            outgoing.put(id, 0);
            SoarNodeValidator.Counts counts = nodeValidator.validate(node, id, path, type, errors);
            actionCount += counts.actions();
            highRisk += counts.highRisk();
            if (type == SoarNodeType.START) starts.add(id);
            if (type == SoarNodeType.END) ends.add(id);
        }
        if (starts.size() != 1) {
            errors.add(DefinitionIssue.error("START_COUNT_INVALID", null, "/nodes",
                    "definition must contain exactly one START node"));
        }
        if (ends.isEmpty()) {
            errors.add(DefinitionIssue.error("END_REQUIRED", null, "/nodes",
                    "definition must contain at least one END node"));
        }
        if (!entry.isBlank() && !types.containsKey(entry)) {
            errors.add(DefinitionIssue.error("ENTRY_NOT_FOUND", entry, "/entryNodeId",
                    "entryNodeId does not reference a node"));
        } else if (!entry.isBlank() && types.containsKey(entry) && types.get(entry) != SoarNodeType.START) {
            errors.add(DefinitionIssue.error("ENTRY_NOT_START", entry, "/entryNodeId",
                    "entryNodeId must reference START"));
        }

        if (edges != null && edges.isArray()) {
            for (int i = 0; i < edges.size(); i++) {
                JsonNode edge = edges.get(i);
                String path = "/edges/" + i;
                String from = text(edge, "from");
                String to = text(edge, "to");
                if (!types.containsKey(from)) {
                    errors.add(DefinitionIssue.error("EDGE_SOURCE_NOT_FOUND", from, path + "/from",
                            "edge source does not reference a node"));
                }
                if (!types.containsKey(to)) {
                    errors.add(DefinitionIssue.error("EDGE_TARGET_NOT_FOUND", to, path + "/to",
                            "edge target does not reference a node"));
                }
                if (types.containsKey(from) && types.containsKey(to)) {
                    graph.get(from).add(to);
                    outgoing.computeIfPresent(from, (key, value) -> value + 1);
                    incoming.computeIfPresent(to, (key, value) -> value + 1);
                }
                if (text(edge, "from").isBlank() || text(edge, "to").isBlank()) {
                    errors.add(DefinitionIssue.error("EDGE_ENDPOINT_REQUIRED", null, path,
                            "edge requires from and to"));
                }
                String port = text(edge, "port");
                if (port.isBlank()) port = text(edge, "when");
                if (!port.isBlank() && !port.matches("[A-Za-z][A-Za-z0-9_.-]{0,31}")) {
                    errors.add(DefinitionIssue.error("EDGE_PORT_INVALID", from,
                            path + "/port", "edge port is invalid"));
                }
                if (types.containsKey(from)) {
                    SoarGraphValidator.validateEdgePort(types.get(from), nodeDefinitions.get(from), port,
                            from, path, errors);
                }
            }
        }
        if (edges != null && edges.isArray()) {
            Set<String> edgeKeys = new HashSet<>();
            for (int i = 0; i < edges.size(); i++) {
                JsonNode edge = edges.get(i);
                String key = text(edge, "from") + "\u0000" + text(edge, "to") + "\u0000"
                        + (text(edge, "port").isBlank() ? text(edge, "when") : text(edge, "port"));
                if (!edgeKeys.add(key)) {
                    errors.add(DefinitionIssue.error("EDGE_DUPLICATE", null, "/edges/" + i,
                            "duplicate edge with the same source, target and port"));
                }
            }
        }
        for (Map.Entry<String, SoarNodeType> node : types.entrySet()) {
            int in = incoming.getOrDefault(node.getKey(), 0);
            int out = outgoing.getOrDefault(node.getKey(), 0);
            if (node.getValue() == SoarNodeType.START && in > 0) {
                errors.add(DefinitionIssue.error("START_HAS_INCOMING_EDGE", node.getKey(), "/edges",
                        "START must not have incoming edges"));
            }
            if (node.getValue() == SoarNodeType.END && out > 0) {
                errors.add(DefinitionIssue.error("END_HAS_OUTGOING_EDGE", node.getKey(), "/edges",
                        "END must not have outgoing edges"));
            }
            if (node.getValue() != SoarNodeType.END && out == 0) {
                errors.add(DefinitionIssue.error("NODE_OUTGOING_EDGE_REQUIRED", node.getKey(), "/edges",
                        "non-END node must have at least one outgoing edge"));
            }
            if (node.getValue() == SoarNodeType.START && out != 1) {
                errors.add(DefinitionIssue.error("START_OUTGOING_EDGE_INVALID", node.getKey(), "/edges",
                        "START must have exactly one outgoing edge"));
            }
            if (node.getValue() == SoarNodeType.CONDITION) {
                // A condition without both terminal ports can silently drop
                // alerts, so fail publication rather than guessing a route.
                boolean hasTrue = false;
                boolean hasFalse = false;
                if (edges != null && edges.isArray()) for (JsonNode edge : edges) {
                    if (node.getKey().equals(text(edge, "from"))) {
                        String port = text(edge, "port");
                        if (port.isBlank()) port = text(edge, "when");
                        hasTrue |= "true".equalsIgnoreCase(port);
                        hasFalse |= "false".equalsIgnoreCase(port);
                    }
                }
                if (!hasTrue || !hasFalse) errors.add(DefinitionIssue.error("CONDITION_PORTS_REQUIRED",
                        node.getKey(), "/edges", "CONDITION requires true and false outgoing ports"));
            }
            if (node.getValue() == SoarNodeType.PARALLEL && outgoing.getOrDefault(node.getKey(), 0) < 2) {
                errors.add(DefinitionIssue.error("PARALLEL_BRANCHES_REQUIRED", node.getKey(), "/edges",
                        "PARALLEL requires at least two outgoing branches"));
            }
            if (node.getValue() == SoarNodeType.JOIN && incoming.getOrDefault(node.getKey(), 0) < 2) {
                errors.add(DefinitionIssue.error("JOIN_BRANCHES_REQUIRED", node.getKey(), "/edges",
                        "JOIN requires at least two incoming branches"));
            }
            if (node.getValue() == SoarNodeType.FOREACH && edges != null && edges.isArray()) {
                boolean body = false, done = false;
                for (JsonNode edge : edges) if (node.getKey().equals(text(edge, "from"))) {
                    String port = text(edge, "port"); if (port.isBlank()) port = text(edge, "when");
                    body |= "body".equalsIgnoreCase(port) || "each".equalsIgnoreCase(port);
                    done |= "done".equalsIgnoreCase(port) || "success".equalsIgnoreCase(port);
                }
                if (!body || !done) errors.add(DefinitionIssue.error("FOREACH_PORTS_REQUIRED", node.getKey(), "/edges",
                        "FOREACH requires body/each and done/success ports"));
            }
            if (node.getValue() == SoarNodeType.SWITCH) {
                boolean hasDefault = false;
                if (edges != null && edges.isArray()) for (JsonNode edge : edges) {
                    if (node.getKey().equals(text(edge, "from"))) {
                        String port = text(edge, "port");
                        if (port.isBlank()) port = text(edge, "when");
                        hasDefault |= "default".equalsIgnoreCase(port) || port.isBlank();
                    }
                }
                if (!hasDefault) errors.add(DefinitionIssue.error("SWITCH_DEFAULT_REQUIRED",
                        node.getKey(), "/edges", "SWITCH requires a default outgoing port"));
            }
            if (edges != null && edges.isArray()) {
                Set<String> primaryPorts = switch (node.getValue()) {
                    case ACTION -> Set.of("success", "default", "");
                    case DELAY, SET_VARIABLE -> Set.of("success", "default", "");
                    case JOIN -> Set.of("success", "default", "");
                    case MANUAL_TASK -> Set.of("completed", "success", "default", "");
                    case SUB_PLAYBOOK -> Set.of("success", "default", "");
                    default -> Set.of();
                };
                if (!primaryPorts.isEmpty() && !SoarGraphValidator.hasOutgoingPort(edges, node.getKey(), primaryPorts)) {
                    errors.add(DefinitionIssue.error("PRIMARY_PORT_REQUIRED", node.getKey(), "/edges",
                            node.getValue().name() + " requires a success/default outgoing port"));
                }
                if (node.getValue() == SoarNodeType.APPROVAL) {
                    if (!SoarGraphValidator.hasOutgoingPort(edges, node.getKey(), Set.of("approved"))) {
                        errors.add(DefinitionIssue.error("APPROVAL_APPROVED_PORT_REQUIRED", node.getKey(), "/edges",
                                "APPROVAL requires an approved outgoing port"));
                    }
                    if (!SoarGraphValidator.hasOutgoingPort(edges, node.getKey(), Set.of("rejected"))) {
                        errors.add(DefinitionIssue.error("APPROVAL_REJECTED_PORT_REQUIRED", node.getKey(), "/edges",
                                "APPROVAL requires an explicit rejected/expired outgoing port"));
                    }
                }
            }
        }

        SoarGraphValidator.validatePostBuild(types, graph, edges, starts, ends, entry, errors);
        SoarExecutionPolicyValidator.validateRootLimits(root, errors);
        if (highRisk > 0) {
            warnings.add(DefinitionIssue.warning("HIGH_RISK_ACTIONS_PRESENT", null, "/nodes",
                    highRisk + " high-risk action(s) require runtime approval policy"));
        }
        actionContracts.validateApprovalCoverage(types, nodeDefinitions, edges, warnings);
        actionContracts.validateCompensationRisk(types, nodeDefinitions, warnings);
        return result(errors, warnings, schemaVersion, hash, types.size(), actionCount, highRisk);
    }

    public static boolean safeManualPattern(String regex) {
        return SoarManualFormValidator.safePattern(regex);
    }

    public String canonicalHash(String definition) {
        try {
            return sha256(canonicalMapper.writeValueAsBytes(mapper.readTree(definition)));
        } catch (Exception failure) {
            return sha256(String.valueOf(definition).getBytes(StandardCharsets.UTF_8));
        }
    }

    private DefinitionValidationResult result(List<DefinitionIssue> errors, List<DefinitionIssue> warnings,
                                               String schema, String hash, int nodes, int actions, int highRisk) {
        return new DefinitionValidationResult(errors.isEmpty(), errors, warnings, schema, hash,
                nodes, actions, highRisk);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return "";
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static boolean containsSensitiveKey(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.contains("secret") || key.contains("token") || key.contains("password")
                        || key.contains("authorization") || key.equals("cookie")) return true;
                if (containsSensitiveKey(field.getValue())) return true;
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) if (containsSensitiveKey(item)) return true;
        }
        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
