package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.soar.web.domain.v2.DefinitionIssue;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.domain.v2.SoarNodeType;
import com.socp.soar.web.service.SoarActionCatalog;
import com.socp.soar.web.connector.ActionDescriptor;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural validator for the safe V2 graph subset. */
@Component
public class SoarDefinitionValidator {
    public static final String SCHEMA_VERSION = "soar.playbook/v2";
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_NODES = 200;
    public static final int MAX_NODE_EXECUTIONS = 500;
    public static final int MAX_PARALLELISM = 10;

    private final ObjectMapper mapper;
    private final ObjectMapper canonicalMapper;
    private final SoarConnectorRegistry connectorRegistry;

    public SoarDefinitionValidator(ObjectMapper mapper) {
        this(mapper, null);
    }

    /** Spring wiring uses the runtime registry as the source of action schema
     * and target policy; the one-argument constructor remains useful for
     * hermetic validator tests. */
    @Autowired
    public SoarDefinitionValidator(ObjectMapper mapper, SoarConnectorRegistry connectorRegistry) {
        this.mapper = mapper;
        this.connectorRegistry = connectorRegistry;
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
        JsonNode rootApprovalPolicy = root.has("approvalPolicy") ? root.get("approvalPolicy")
                : root.get("policy");
        String rootApprovalPolicyPath = root.has("approvalPolicy") ? "/approvalPolicy" : "/policy";
        if (rootApprovalPolicy != null && !rootApprovalPolicy.isObject()) {
            errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", null, rootApprovalPolicyPath,
                    "approval policy must be an object"));
        } else if (rootApprovalPolicy != null && rootApprovalPolicy.isObject()) {
            JsonNode required = rootApprovalPolicy.has("approvalsRequired")
                    ? rootApprovalPolicy.get("approvalsRequired")
                    : rootApprovalPolicy.get("requiredApprovals");
            if (required != null && (!isIntegerValue(required) || required.asInt() < 1 || required.asInt() > 20)) {
                errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", null,
                        rootApprovalPolicyPath + "/approvalsRequired",
                        "approvalsRequired must be an integer from 1 to 20"));
            }
            for (String key : List.of("allowedRoles", "allowedGroups", "approverRoles", "approverGroups")) {
                validateApprovalPrincipalListAt(rootApprovalPolicy, key,
                        rootApprovalPolicyPath + "/" + key, null, errors);
            }
        }
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
            if (node.has("config") && !node.path("config").isObject()) {
                errors.add(DefinitionIssue.error("NODE_CONFIG_INVALID", id, path + "/config",
                        "node config must be an object"));
            }
            if (node.has("limits") && !node.path("limits").isObject()) {
                errors.add(DefinitionIssue.error("NODE_LIMITS_INVALID", id, path + "/limits",
                        "node limits must be an object"));
            }
            if (type == SoarNodeType.START) starts.add(id);
            if (type == SoarNodeType.END) ends.add(id);
            if (type == SoarNodeType.ACTION) {
                actionCount++;
                String actionRef = text(node, "actionRef");
                if (actionRef.isBlank()) {
                    errors.add(DefinitionIssue.error("ACTION_REF_REQUIRED", id, path + "/actionRef",
                            "ACTION node requires actionRef"));
                }
                if (!actionRef.isBlank() && !SoarActionCatalog.isNamespaced(actionRef)) {
                    errors.add(DefinitionIssue.error("ACTION_REF_FORMAT_INVALID", id,
                            path + "/actionRef", "actionRef must use namespace/name[@version]"));
                }
                boolean registeredAction = SoarActionCatalog.isKnown(actionRef)
                        || (connectorRegistry != null
                        && connectorRegistry.descriptorForAction(actionRef).isPresent());
                if (!actionRef.isBlank() && SoarActionCatalog.isNamespaced(actionRef)
                        && !registeredAction) {
                    errors.add(DefinitionIssue.error("ACTION_REF_UNKNOWN", id, path + "/actionRef",
                            "actionRef is not registered in the SOAR action catalog"));
                }
                validateActionContract(node, id, path, actionRef, errors);
                if (isHighRiskAction(actionRef)) highRisk++;
                JsonNode retry = node.has("retry") ? node.get("retry")
                        : (node.has("retryPolicy") ? node.get("retryPolicy") : node.path("config").get("retry"));
                if (containsSensitiveKey(node.get("parameters")) || containsSensitiveKey(node.get("target"))) {
                    errors.add(DefinitionIssue.error("ACTION_SECRET_INLINE_FORBIDDEN", id,
                            path, "action parameters/target cannot contain secret, token, password or authorization values; use a connection secretRef"));
                }
                if (retry != null && !retry.isObject()) {
                    errors.add(DefinitionIssue.error("ACTION_RETRY_POLICY_INVALID", id,
                            path + "/retry", "retry policy must be an object"));
                }
                if (retry != null && retry.isObject()) {
                    if (retry.has("maxAttempts") && !isIntegerValue(retry.get("maxAttempts"))) {
                        errors.add(DefinitionIssue.error("ACTION_RETRY_LIMIT_INVALID", id,
                                path + "/retry/maxAttempts", "maxAttempts must be an integer"));
                    }
                    if (retry.has("maximumAttempts") && !isIntegerValue(retry.get("maximumAttempts"))) {
                        errors.add(DefinitionIssue.error("ACTION_RETRY_LIMIT_INVALID", id,
                                path + "/retry/maximumAttempts", "maximumAttempts must be an integer"));
                    }
                    int maxAttempts = retry.has("maxAttempts")
                            ? intValue(retry.path("maxAttempts"), 1)
                            : intValue(retry.path("maximumAttempts"), 1);
                    if (retry.has("backoffSeconds") && !isLongValue(retry.get("backoffSeconds"))) {
                        errors.add(DefinitionIssue.error("ACTION_RETRY_BACKOFF_INVALID", id,
                                path + "/retry/backoffSeconds", "backoffSeconds must be an integer"));
                    }
                    if (retry.has("initialInterval") && !validDuration(retry.path("initialInterval").asText(""))) {
                        errors.add(DefinitionIssue.error("ACTION_RETRY_BACKOFF_INVALID", id,
                                path + "/retry/initialInterval", "initialInterval must be an ISO-8601 duration"));
                    }
                    long backoffSeconds = retry.path("backoffSeconds").isNumber()
                            ? retry.path("backoffSeconds").asLong() : durationSeconds(retry.path("initialInterval").asText(""), 0);
                    if (maxAttempts < 1 || maxAttempts > 10) {
                        errors.add(DefinitionIssue.error("ACTION_RETRY_LIMIT_INVALID", id,
                                path + "/retry/maxAttempts", "action maxAttempts must be 1..10"));
                    }
                    if (backoffSeconds < 0 || backoffSeconds > 300) {
                        errors.add(DefinitionIssue.error("ACTION_RETRY_BACKOFF_INVALID", id,
                                path + "/retry/backoffSeconds", "action backoffSeconds must be 0..300"));
                    }
                    if (maxAttempts > 1 && hasNonIdempotentSideEffect(actionRef)) {
                        errors.add(DefinitionIssue.error("ACTION_RETRY_REQUIRES_IDEMPOTENCY", id,
                                path + "/retry/maxAttempts",
                                "side-effecting actions with idempotency NONE cannot be retried"));
                    }
                }
            }
            if (type == SoarNodeType.ACTION || type == SoarNodeType.JOIN || type == SoarNodeType.FOREACH) {
                String onError = text(node, "onError");
                if (onError.isBlank()) onError = text(node.path("config"), "onError");
                if (!onError.isBlank() && !Set.of("FAIL_RUN", "CONTINUE", "GOTO_ERROR_PORT",
                        "COMPENSATE_THEN_FAIL").contains(onError.toUpperCase(java.util.Locale.ROOT))) {
                    errors.add(DefinitionIssue.error("NODE_ON_ERROR_INVALID", id,
                            path + "/onError", "onError must be FAIL_RUN, CONTINUE, GOTO_ERROR_PORT or COMPENSATE_THEN_FAIL"));
                }
            }
            if (type == SoarNodeType.CONDITION && text(node, "expression").isBlank()) {
                errors.add(DefinitionIssue.error("CONDITION_EXPRESSION_REQUIRED", id,
                        path + "/expression", "CONDITION node requires expression"));
            }
            if (type == SoarNodeType.SWITCH && text(node, "expression").isBlank()) {
                errors.add(DefinitionIssue.error("SWITCH_EXPRESSION_REQUIRED", id,
                        path + "/expression", "SWITCH node requires expression"));
            }
            if (type == SoarNodeType.END && text(node, "outcome").isBlank()) {
                errors.add(DefinitionIssue.error("END_OUTCOME_REQUIRED", id,
                        path + "/outcome", "END node requires an outcome"));
            }
            if (type == SoarNodeType.END && !text(node, "outcome").isBlank()
                    && !Set.of("SUCCEEDED", "FAILED", "SUPPRESSED", "TIMED_OUT", "CANCELLED",
                    "PARTIALLY_SUCCEEDED").contains(text(node, "outcome").toUpperCase(java.util.Locale.ROOT))) {
                errors.add(DefinitionIssue.error("END_OUTCOME_INVALID", id, path + "/outcome",
                        "END outcome is not supported"));
            }
            if ((type == SoarNodeType.CONDITION || type == SoarNodeType.SWITCH)
                    && !safeExpression(text(node, "expression"))) {
                errors.add(DefinitionIssue.error("EXPRESSION_NOT_ALLOWED", id,
                        path + "/expression", "expression contains unsupported or unsafe syntax"));
            }
            if (type == SoarNodeType.FOREACH) {
                if (node.path("limits").isObject() && node.path("limits").has("maxItems")
                        && !isIntegerValue(node.path("limits").get("maxItems"))) {
                    errors.add(DefinitionIssue.error("FOREACH_LIMIT_INVALID", id,
                            path + "/limits/maxItems", "maxItems must be an integer"));
                }
                int maxItems = intValue(node.path("limits").path("maxItems"), 100);
                if (maxItems < 1 || maxItems > 100) {
                    errors.add(DefinitionIssue.error("FOREACH_LIMIT_INVALID", id,
                            path + "/limits/maxItems", "FOREACH maxItems must be 1..100"));
                }
                if (node.path("limits").isObject() && node.path("limits").has("concurrency")
                        && !isIntegerValue(node.path("limits").get("concurrency"))) {
                    errors.add(DefinitionIssue.error("FOREACH_CONCURRENCY_INVALID", id,
                            path + "/limits/concurrency", "concurrency must be an integer"));
                }
                int concurrency = intValue(node.path("limits").path("concurrency"), 1);
                if (concurrency < 1 || concurrency > MAX_PARALLELISM) {
                    errors.add(DefinitionIssue.error("FOREACH_CONCURRENCY_INVALID", id,
                            path + "/limits/concurrency", "FOREACH concurrency must be 1.." + MAX_PARALLELISM));
                }
                if (text(node.path("config"), "itemsPath").isBlank()) {
                    errors.add(DefinitionIssue.error("FOREACH_ITEMS_REQUIRED", id,
                            path + "/config/itemsPath", "FOREACH requires config.itemsPath"));
                }
                String itemVariable = text(node.path("config"), "itemVariable");
                if (!itemVariable.isBlank() && !itemVariable.startsWith("vars.")) {
                    errors.add(DefinitionIssue.error("FOREACH_VARIABLE_SCOPE_INVALID", id,
                            path + "/config/itemVariable", "FOREACH itemVariable must be vars.*"));
                }
            }
            if (type == SoarNodeType.PARALLEL) {
                if (node.path("limits").isObject() && node.path("limits").has("maxParallelism")
                        && !isIntegerValue(node.path("limits").get("maxParallelism"))) {
                    errors.add(DefinitionIssue.error("PARALLELISM_LIMIT_INVALID", id,
                            path + "/limits/maxParallelism", "maxParallelism must be an integer"));
                }
                int maxParallelism = intValue(node.path("limits").path("maxParallelism"), 2);
                if (maxParallelism < 1 || maxParallelism > MAX_PARALLELISM) {
                    errors.add(DefinitionIssue.error("PARALLELISM_LIMIT_INVALID", id,
                            path + "/limits/maxParallelism", "PARALLEL maxParallelism must be 1.." + MAX_PARALLELISM));
                }
            }
            if (type == SoarNodeType.JOIN) {
                String strategy = text(node, "strategy");
                if (!strategy.isBlank() && !Set.of("ALL_SUCCESS", "ALL_DONE", "ANY_SUCCESS").contains(strategy.toUpperCase())) {
                    errors.add(DefinitionIssue.error("JOIN_STRATEGY_INVALID", id, path + "/strategy",
                            "JOIN strategy must be ALL_SUCCESS, ALL_DONE or ANY_SUCCESS"));
                }
            }
            if (type == SoarNodeType.MANUAL_TASK) {
                JsonNode form = node.get("formSchema");
                if (form != null && !form.isObject()) errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", id,
                        path + "/formSchema", "MANUAL_TASK formSchema must be an object"));
                if (form != null && form.isObject()) {
                    validateManualFormSchema(form, path + "/formSchema", errors, 0);
                }
                validateTimeout(node, path, "MANUAL_TASK", 30L * 24 * 3600, errors);
            }
            if (type == SoarNodeType.APPROVAL) {
                if (node.has("policy") && !node.path("policy").isObject()) {
                    errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", id,
                            path + "/policy", "approval policy must be an object"));
                }
                validateTimeout(node, path, "APPROVAL", 7L * 24 * 3600, errors);
                validateApprovalPolicy(node, path, errors);
            }
            if (type == SoarNodeType.DELAY) {
                validateDurationSeconds(node, path, errors);
            }
            if (type == SoarNodeType.SET_VARIABLE) {
                String name = text(node.path("config"), "name");
                if (name.isBlank()) {
                    errors.add(DefinitionIssue.error("VARIABLE_NAME_REQUIRED", id,
                            path + "/config/name", "SET_VARIABLE requires config.name"));
                } else if (!name.equals("vars") && !name.startsWith("vars.")) {
                    errors.add(DefinitionIssue.error("VARIABLE_SCOPE_INVALID", id,
                            path + "/config/name", "SET_VARIABLE can only write vars.*"));
                }
                if (!node.path("config").isObject() || !node.path("config").has("value")) {
                    errors.add(DefinitionIssue.error("VARIABLE_VALUE_REQUIRED", id,
                            path + "/config/value", "SET_VARIABLE requires config.value"));
                }
            }
            if (type == SoarNodeType.SUB_PLAYBOOK
                    && text(node, "playbookVersionId").isBlank()
                    && (!node.has("definition") || !node.path("definition").isObject())) {
                errors.add(DefinitionIssue.error("SUB_PLAYBOOK_REFERENCE_REQUIRED", id,
                        path, "SUB_PLAYBOOK requires a published playbookVersionId or resolved definition"));
            }
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
                    validateEdgePort(types.get(from), nodeDefinitions.get(from), port, from,
                            path, errors);
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
                if (!primaryPorts.isEmpty() && !hasOutgoingPort(edges, node.getKey(), primaryPorts)) {
                    errors.add(DefinitionIssue.error("PRIMARY_PORT_REQUIRED", node.getKey(), "/edges",
                            node.getValue().name() + " requires a success/default outgoing port"));
                }
                if (node.getValue() == SoarNodeType.APPROVAL) {
                    if (!hasOutgoingPort(edges, node.getKey(), Set.of("approved"))) {
                        errors.add(DefinitionIssue.error("APPROVAL_APPROVED_PORT_REQUIRED", node.getKey(), "/edges",
                                "APPROVAL requires an approved outgoing port"));
                    }
                    if (!hasOutgoingPort(edges, node.getKey(), Set.of("rejected"))) {
                        errors.add(DefinitionIssue.error("APPROVAL_REJECTED_PORT_REQUIRED", node.getKey(), "/edges",
                                "APPROVAL requires an explicit rejected/expired outgoing port"));
                    }
                }
            }
        }

        String start = starts.stream().findFirst().orElse(entry);
        if (start != null && types.containsKey(start)) {
            Set<String> reachable = reachable(graph, start);
            for (String id : types.keySet()) {
                if (!reachable.contains(id)) {
                    errors.add(DefinitionIssue.error("NODE_UNREACHABLE", id, "/nodes",
                            "node is not reachable from START"));
                }
            }
            Set<String> canReachEnd = reverseReachable(graph, ends);
            for (String id : types.keySet()) {
                if (types.get(id) != SoarNodeType.END && !canReachEnd.contains(id)) {
                    errors.add(DefinitionIssue.error("NODE_CANNOT_REACH_END", id, "/nodes",
                            "node must eventually reach an END node"));
                }
            }
            // A FOREACH node only bounds the loop that it owns.  The old check
            // looked for a FOREACH anywhere in the document, which meant an
            // unrelated cycle could be smuggled through by adding a dormant
            // loop elsewhere.  Inspect each back-edge and reject cycles whose
            // actual cycle segment has no bounded FOREACH node.
            if (hasUnboundedCycle(graph, types)) {
                errors.add(DefinitionIssue.error("GRAPH_CYCLE_NOT_ALLOWED", null, "/edges",
                        "cycles are only supported by a bounded FOREACH construct"));
            }
        }
        JsonNode limits = root.path("limits");
        if (!limits.isMissingNode() && !limits.isObject()) {
            errors.add(DefinitionIssue.error("LIMITS_INVALID", null, "/limits",
                    "limits must be an object"));
        }
        if (limits.isObject() && limits.has("maxNodeExecutions")
                && !isIntegerValue(limits.get("maxNodeExecutions"))) {
            errors.add(DefinitionIssue.error("NODE_EXECUTION_LIMIT_INVALID", null,
                    "/limits/maxNodeExecutions", "maxNodeExecutions must be an integer"));
        }
        if (limits.isObject() && limits.has("maxParallelism")
                && !isIntegerValue(limits.get("maxParallelism"))) {
            errors.add(DefinitionIssue.error("PARALLELISM_LIMIT_INVALID", null,
                    "/limits/maxParallelism", "maxParallelism must be an integer"));
        }
        if (limits.isObject() && limits.has("executionTimeout")) {
            JsonNode timeout = limits.get("executionTimeout");
            if (!timeout.isTextual() || !validExecutionTimeout(timeout.asText())) {
                errors.add(DefinitionIssue.error("EXECUTION_TIMEOUT_INVALID", null,
                        "/limits/executionTimeout", "executionTimeout must be an ISO-8601 duration between PT1S and P30D"));
            }
        }
        int maxExec = intValue(limits.path("maxNodeExecutions"), MAX_NODE_EXECUTIONS);
        int parallelism = intValue(limits.path("maxParallelism"), MAX_PARALLELISM);
        if (maxExec < 1 || maxExec > MAX_NODE_EXECUTIONS) {
            errors.add(DefinitionIssue.error("NODE_EXECUTION_LIMIT_INVALID", null,
                    "/limits/maxNodeExecutions", "maxNodeExecutions must be 1.." + MAX_NODE_EXECUTIONS));
        }
        if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
            errors.add(DefinitionIssue.error("PARALLELISM_LIMIT_INVALID", null,
                    "/limits/maxParallelism", "maxParallelism must be 1.." + MAX_PARALLELISM));
        }
        if (highRisk > 0) {
            warnings.add(DefinitionIssue.warning("HIGH_RISK_ACTIONS_PRESENT", null, "/nodes",
                    highRisk + " high-risk action(s) require runtime approval policy"));
        }
        return result(errors, warnings, schemaVersion, hash, types.size(), actionCount, highRisk);
    }

    private static boolean hasOutgoingPort(JsonNode edges, String nodeId, Set<String> expected) {
        if (edges == null || !edges.isArray()) return false;
        for (JsonNode edge : edges) {
            if (!nodeId.equals(text(edge, "from"))) continue;
            String port = text(edge, "port");
            if (port.isBlank()) port = text(edge, "when");
            if (expected.contains(port.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Keep edge labels aligned with the branch names understood by the
     * deterministic workflow.  The runtime intentionally has a default-edge
     * fallback for backwards compatibility; publication must nevertheless
     * reject misspelled labels so a definition cannot silently take the wrong
     * branch.
     */
    private static void validateEdgePort(SoarNodeType type, JsonNode node,
                                         String port, String nodeId, String edgePath,
                                         List<DefinitionIssue> errors) {
        String normalized = port == null ? "" : port.trim().toLowerCase(java.util.Locale.ROOT);
        boolean valid;
        switch (type) {
            case START, END -> valid = normalized.isBlank();
            case ACTION -> valid = normalized.isBlank() || Set.of(
                    "default", "success", "failure", "error", "unknown").contains(normalized);
            case CONDITION -> valid = Set.of("true", "false").contains(normalized);
            case SWITCH -> valid = switchPortDeclared(node, normalized);
            case PARALLEL -> valid = normalized.isBlank() || normalized.equals("default")
                    || normalized.matches("[a-z][a-z0-9_.-]{0,31}");
            case JOIN -> valid = normalized.isBlank() || Set.of(
                    "default", "success", "failure", "error").contains(normalized);
            case FOREACH -> valid = normalized.isBlank() || Set.of(
                    "default", "body", "each", "done", "success").contains(normalized);
            case DELAY, SET_VARIABLE -> valid = normalized.isBlank()
                    || Set.of("default", "success").contains(normalized);
            case APPROVAL -> valid = Set.of("approved", "rejected").contains(normalized);
            case MANUAL_TASK -> valid = normalized.isBlank() || Set.of(
                    "default", "completed", "success", "timeout").contains(normalized);
            case SUB_PLAYBOOK -> valid = normalized.isBlank() || Set.of(
                    "default", "success", "failure").contains(normalized);
            default -> valid = false;
        }
        if (!valid) {
            errors.add(DefinitionIssue.error("EDGE_PORT_NOT_ALLOWED", nodeId,
                    edgePath + "/port", "edge port '" + (port == null ? "" : port)
                            + "' is not valid for " + type.name()));
        }
    }

    private static boolean switchPortDeclared(JsonNode node, String port) {
        if (port.isBlank() || "default".equals(port)) return true;
        JsonNode cases = node == null ? null : node.get("cases");
        if (cases == null || !cases.isArray()) {
            cases = node == null ? null : node.path("config").get("cases");
        }
        if (cases == null || !cases.isArray()) return false;
        for (JsonNode item : cases) {
            if (item == null || !item.isObject()) continue;
            String declared = text(item, "port");
            if (declared.isBlank()) declared = text(item, "toPort");
            if (port.equalsIgnoreCase(declared)) return true;
        }
        return false;
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

    private static Set<String> reachable(Map<String, List<String>> graph, String start) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (String next : graph.getOrDefault(current, List.of())) queue.addLast(next);
        }
        return visited;
    }

    private static Set<String> reverseReachable(Map<String, List<String>> graph, Set<String> ends) {
        Map<String, List<String>> reverse = new HashMap<>();
        graph.forEach((from, targets) -> targets.forEach(to ->
                reverse.computeIfAbsent(to, ignored -> new ArrayList<>()).add(from)));
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(ends);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (String previous : reverse.getOrDefault(current, List.of())) queue.addLast(previous);
        }
        return visited;
    }

    private static boolean hasUnboundedCycle(Map<String, List<String>> graph,
                                             Map<String, SoarNodeType> types) {
        Map<String, Integer> stackIndex = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (String node : types.keySet()) {
            if (unboundedCycle(graph, types, node, stackIndex, stack)) return true;
        }
        return false;
    }

    private static boolean unboundedCycle(Map<String, List<String>> graph,
                                          Map<String, SoarNodeType> types,
                                          String node,
                                          Map<String, Integer> stackIndex,
                                          List<String> stack) {
        Integer existing = stackIndex.get(node);
        if (existing != null) {
            for (int index = existing; index < stack.size(); index++) {
                if (types.get(stack.get(index)) == SoarNodeType.FOREACH) return false;
            }
            return true;
        }
        stackIndex.put(node, stack.size());
        stack.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            if (unboundedCycle(graph, types, next, stackIndex, stack)) return true;
        }
        stack.remove(stack.size() - 1);
        stackIndex.remove(node);
        return false;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return "";
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static int intValue(JsonNode node, int fallback) {
        return isIntegerValue(node) ? node.intValue() : fallback;
    }

    private static boolean isIntegerValue(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt();
    }

    private static boolean isLongValue(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong();
    }

    private static void validateTimeout(JsonNode node, String path, String type, long max,
                                        List<DefinitionIssue> errors) {
        JsonNode config = node.path("config");
        JsonNode timeout = config.isObject() && config.has("timeoutSeconds")
                ? config.get("timeoutSeconds") : node.get("timeoutSeconds");
        if (timeout == null) return;
        if (!isLongValue(timeout)) {
            errors.add(DefinitionIssue.error(type + "_TIMEOUT_INVALID", node.path("id").asText(),
                    path + "/config/timeoutSeconds", "timeoutSeconds must be an integer"));
            return;
        }
        long value = timeout.asLong();
        if (value < 0 || value > max) {
            errors.add(DefinitionIssue.error(type + "_TIMEOUT_INVALID", node.path("id").asText(),
                    path + "/config/timeoutSeconds", "timeoutSeconds must be 0.." + max));
        }
    }

    private static void validateApprovalPolicy(JsonNode node, String path,
                                                List<DefinitionIssue> errors) {
        JsonNode policy = node.path("policy").isObject() ? node.path("policy") : node.path("config");
        if (!policy.isObject()) return;
        JsonNode required = policy.has("approvalsRequired") ? policy.get("approvalsRequired")
                : policy.get("requiredApprovals");
        if (required != null && (!isIntegerValue(required) || required.asInt() < 1 || required.asInt() > 20)) {
            errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", node == null ? null : node.path("id").asText(),
                    path + "/policy/approvalsRequired", "approvalsRequired must be an integer from 1 to 20"));
        }
        validateApprovalPrincipalList(policy, "allowedRoles", path, node, errors);
        validateApprovalPrincipalList(policy, "allowedGroups", path, node, errors);
        // Accept the terminology used by common IAM integrations while
        // normalizing it to the same durable policy projection at runtime.
        validateApprovalPrincipalList(policy, "approverRoles", path, node, errors);
        validateApprovalPrincipalList(policy, "approverGroups", path, node, errors);
    }

    private static void validateApprovalPrincipalList(JsonNode policy, String key, String path,
                                                      JsonNode node, List<DefinitionIssue> errors) {
        validateApprovalPrincipalListAt(policy, key, path + "/policy/" + key, node, errors);
    }

    private static void validateApprovalPrincipalListAt(JsonNode policy, String key, String fieldPath,
                                                        JsonNode node, List<DefinitionIssue> errors) {
        if (!policy.has(key)) return;
        JsonNode values = policy.get(key);
        if (!values.isArray() || values.isEmpty() || values.size() > 64) {
            errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", node == null ? null : node.path("id").asText(),
                    fieldPath,
                    key + " must be a non-empty array with at most 64 values"));
            return;
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 128
                    || value.asText().contains("\u0000")) {
                errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", node == null ? null : node.path("id").asText(),
                        fieldPath,
                        key + " values must be non-blank strings of at most 128 characters"));
                break;
            }
        }
    }

    /**
     * Validate the bounded JSON-Schema subset understood by the MANUAL_TASK
     * completion endpoint.  A published workflow must not advertise a form
     * that the runtime can only reject after an analyst has been asked to fill
     * it in.  Full JSON-Schema (refs, scripts, unevaluated properties) is
     * intentionally outside the SOAR trust boundary.
     */
    private static void validateManualFormSchema(JsonNode schema, String path,
                                                 List<DefinitionIssue> errors, int depth) {
        if (depth > 20) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path,
                    "manual form schema exceeds the maximum nesting depth"));
            return;
        }
        if (schema == null || !schema.isObject()) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path,
                    "manual form schema must be an object"));
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !type.isTextual() && !type.isArray()) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/type",
                    "manual form type must be a string or an array of strings"));
        }
        if (type != null && type.isTextual() && !validManualType(type.asText())) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/type",
                    "manual form type is not supported"));
        }
        if (type != null && type.isArray()) {
            if (type.isEmpty() || type.size() > 8) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/type",
                        "manual form type array must contain 1..8 values"));
            }
            for (JsonNode item : type) if (!item.isTextual() || !validManualType(item.asText())) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/type",
                        "manual form type array contains an unsupported value"));
                break;
            }
        }
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray() || required.size() > 64) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/required",
                        "required must be an array of at most 64 field names"));
            } else for (JsonNode item : required) if (!item.isTextual()
                    || item.asText().isBlank() || item.asText().length() > 128) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/required",
                        "required contains an invalid field name"));
                break;
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            if (!properties.isObject() || properties.size() > 64) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/properties",
                        "properties must be an object with at most 64 fields"));
            } else {
                properties.fields().forEachRemaining(field -> validateManualFormSchema(
                        field.getValue(), path + "/properties/" + field.getKey(), errors, depth + 1));
            }
        }
        JsonNode items = schema.get("items");
        if (items != null && !items.isObject()) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/items",
                    "items must be an object schema"));
        } else if (items != null) {
            validateManualFormSchema(items, path + "/items", errors, depth + 1);
        }
        validateManualBound(schema, "minLength", 0, 64 * 1024, path, errors);
        validateManualBound(schema, "maxLength", 0, 64 * 1024, path, errors);
        validateManualBound(schema, "minItems", 0, 1000, path, errors);
        validateManualBound(schema, "maxItems", 0, 1000, path, errors);
        for (String field : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
            if (schema.has(field) && !schema.get(field).isNumber()) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/" + field,
                        field + " must be a number"));
            }
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            if (!pattern.isTextual() || pattern.asText().length() > 1024) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/pattern",
                        "pattern must be a regular expression of at most 1024 characters"));
            } else {
                try { java.util.regex.Pattern.compile(pattern.asText()); }
                catch (java.util.regex.PatternSyntaxException invalid) {
                    errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/pattern",
                            "pattern is not a valid regular expression"));
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && !additional.isBoolean() && !additional.isObject()) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/additionalProperties",
                    "additionalProperties must be a boolean or schema object"));
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && (!enumValues.isArray() || enumValues.size() > 100)) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/enum",
                    "enum must contain at most 100 values"));
        }
    }

    private static void validateManualBound(JsonNode schema, String field, int min, int max,
                                             String path, List<DefinitionIssue> errors) {
        JsonNode value = schema.get(field);
        if (value == null) return;
        if (!isIntegerValue(value) || value.asInt() < min || value.asInt() > max) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/" + field,
                    field + " must be an integer from " + min + " to " + max));
        }
    }

    private static boolean validManualType(String value) {
        return value != null && Set.of("object", "array", "string", "integer", "number",
                "boolean", "null").contains(value.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static void validateDurationSeconds(JsonNode node, String path,
                                                List<DefinitionIssue> errors) {
        JsonNode config = node.path("config");
        JsonNode duration = config.isObject() && config.has("durationSeconds")
                ? config.get("durationSeconds") : node.get("durationSeconds");
        if (duration == null) return;
        if (!isLongValue(duration)) {
            errors.add(DefinitionIssue.error("DELAY_DURATION_INVALID", node.path("id").asText(),
                    path + "/config/durationSeconds", "durationSeconds must be an integer"));
            return;
        }
        long value = duration.asLong();
        if (value < 0 || value > 86400) {
            errors.add(DefinitionIssue.error("DELAY_DURATION_INVALID", node.path("id").asText(),
                    path + "/config/durationSeconds", "durationSeconds must be 0..86400"));
        }
    }

    private static boolean validDuration(String value) {
        if (value == null || value.isBlank()) return true;
        try { return java.time.Duration.parse(value).compareTo(java.time.Duration.ZERO) >= 0; }
        catch (Exception ignored) { return false; }
    }

    private static boolean validExecutionTimeout(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            java.time.Duration duration = java.time.Duration.parse(value);
            return !duration.isNegative() && !duration.isZero()
                    && duration.compareTo(java.time.Duration.ofDays(30)) <= 0;
        } catch (Exception ignored) { return false; }
    }

    private static long durationSeconds(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Math.max(0, java.time.Duration.parse(value).toSeconds()); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean looksHighRisk(String actionRef) {
        String value = actionRef == null ? "" : actionRef.toLowerCase(java.util.Locale.ROOT);
        return value.contains("isolate") || value.contains("block") || value.contains("disable")
                || value.contains("delete") || value.contains("snapshot");
    }

    private static boolean safeExpression(String expression) {
        return SoarExpressionEngine.isSafe(expression);
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

    private void validateActionContract(JsonNode node, String nodeId, String path,
                                        String actionRef, List<DefinitionIssue> errors) {
        if (connectorRegistry == null || actionRef == null || actionRef.isBlank()) return;
        var descriptor = connectorRegistry.descriptorForAction(actionRef).orElse(null);
        if (descriptor == null) return; // the catalog error above explains it
        String canonical = connectorRegistry.canonicalActionRef(actionRef);
        int slash = canonical.indexOf('/');
        String actionId = slash < 0 ? "" : canonical.substring(slash + 1).split("@")[0];
        ActionDescriptor action = descriptor.actions().stream()
                .filter(candidate -> candidate.id().equals(actionId)).findFirst().orElse(null);
        if (action == null) return;
        String connectionRef = text(node, "connectionRef");
        if (action.requiresConnection() && connectionRef.isBlank()) {
            errors.add(DefinitionIssue.error("ACTION_CONNECTION_REQUIRED", nodeId,
                    path + "/connectionRef", "action requires a connectionRef"));
        }
        JsonNode target = node.get("target");
        if (target != null && target.isObject() && target.has("type")
                && !target.path("type").asText("").isBlank()
                && !action.allowedTargetTypes().isEmpty()
                && action.allowedTargetTypes().stream().noneMatch(type ->
                type.equalsIgnoreCase(target.path("type").asText()))) {
            errors.add(DefinitionIssue.error("ACTION_TARGET_TYPE_INVALID", nodeId,
                    path + "/target/type", "target type is not supported by " + canonical));
        }
        JsonNode parameters = node.get("parameters");
        if (parameters != null && !parameters.isObject()) {
            errors.add(DefinitionIssue.error("ACTION_PARAMETERS_INVALID", nodeId,
                    path + "/parameters", "ACTION parameters must be an object"));
        }
    }

    private boolean hasNonIdempotentSideEffect(String actionRef) {
        if (connectorRegistry == null || actionRef == null || actionRef.isBlank()) return false;
        var descriptor = connectorRegistry.descriptorForAction(actionRef).orElse(null);
        if (descriptor == null) return false;
        String canonical = connectorRegistry.canonicalActionRef(actionRef);
        int slash = canonical.indexOf('/');
        String actionId = slash < 0 ? "" : canonical.substring(slash + 1).split("@")[0];
        return descriptor.actions().stream().filter(action -> action.id().equals(actionId))
                .anyMatch(action -> !"NONE".equalsIgnoreCase(action.sideEffect())
                        && "NONE".equalsIgnoreCase(action.idempotency()));
    }

    private boolean isHighRiskAction(String actionRef) {
        if (looksHighRisk(actionRef)) return true;
        if (connectorRegistry == null) return false;
        var descriptor = connectorRegistry.descriptorForAction(actionRef).orElse(null);
        if (descriptor == null) return false;
        String canonical = connectorRegistry.canonicalActionRef(actionRef);
        int slash = canonical.indexOf('/');
        String actionId = slash < 0 ? "" : canonical.substring(slash + 1).split("@")[0];
        return descriptor.actions().stream()
                .filter(action -> action.id().equals(actionId))
                .anyMatch(action -> "HIGH".equalsIgnoreCase(action.riskLevel())
                        || "CRITICAL".equalsIgnoreCase(action.riskLevel()));
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
