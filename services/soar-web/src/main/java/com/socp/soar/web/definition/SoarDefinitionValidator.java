package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.soar.web.domain.DefinitionIssue;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.domain.SoarNodeType;
import com.socp.soar.web.service.SoarActionCatalog;
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
    private final SoarConnectorRegistry connectorRegistry;
    private final SoarActionContractValidator actionContracts;

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
        this.actionContracts = new SoarActionContractValidator(mapper, connectorRegistry);
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
                actionContracts.validateActionContract(node, id, path, actionRef, errors);
                String actionRisk = actionContracts.actionRiskLevel(actionRef);
                if ("CRITICAL".equalsIgnoreCase(actionRisk)) {
                    // Design 10.1: CRITICAL auto-execute is forbidden and P0
                    // has no execution path for it at all (P1 adds a two
                    // non-initiator approval policy).  Fail publication rather
                    // than letting a version ship that the runtime would run
                    // with a single approver.
                    errors.add(DefinitionIssue.error("ACTION_RISK_CRITICAL_FORBIDDEN", id,
                            path + "/actionRef",
                            "CRITICAL actions are disabled in this release; no P0 execution path exists"));
                } else if ("HIGH".equalsIgnoreCase(actionRisk)) {
                    highRisk++;
                }
                JsonNode retry = node.has("retry") ? node.get("retry")
                        : (node.has("retryPolicy") ? node.get("retryPolicy") : node.path("config").get("retry"));
                if (containsSensitiveKey(node.get("parameters")) || containsSensitiveKey(node.get("target"))) {
                    errors.add(DefinitionIssue.error("ACTION_SECRET_INLINE_FORBIDDEN", id,
                            path, "action parameters/target cannot contain secret, token, password or authorization values; use a connection secretRef"));
                }
                if (SoarActionContractValidator.containsCredentialValue(node.get("parameters"))
                        || SoarActionContractValidator.containsCredentialValue(node.get("target"))) {
                    errors.add(DefinitionIssue.error("ACTION_EMBEDDED_CREDENTIAL_FORBIDDEN", id,
                            path, "action parameters/target cannot embed credentials (user:pass@ URLs, private keys or bearer/token values)" +
                                    " beyond a secretRef reference"));
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
                    if (maxAttempts > 1 && actionContracts.hasNonIdempotentSideEffect(actionRef)) {
                        errors.add(DefinitionIssue.error("ACTION_RETRY_REQUIRES_IDEMPOTENCY", id,
                                path + "/retry/maxAttempts",
                                "side-effecting actions with idempotency NONE cannot be retried"));
                    }
                }
            }
            if (type == SoarNodeType.ACTION || type == SoarNodeType.JOIN || type == SoarNodeType.FOREACH) {
                String onError = text(node, "onError");
                if (onError.isBlank()) onError = text(node.path("config"), "onError");
                // Mirror the engine exactly: the workflow honours every value on
                // ACTION, but FOREACH only reacts to CONTINUE (SoarWorkflowImpl:415)
                // and JOIN has no compensation step (SoarWorkflowImpl:364-381).
                Set<String> allowedOnError = switch (type) {
                    case ACTION -> Set.of("FAIL_RUN", "CONTINUE", "GOTO_ERROR_PORT", "COMPENSATE_THEN_FAIL");
                    case JOIN -> Set.of("FAIL_RUN", "CONTINUE", "GOTO_ERROR_PORT");
                    default -> Set.of("FAIL_RUN", "CONTINUE");
                };
                if (!onError.isBlank() && !allowedOnError.contains(onError.toUpperCase(java.util.Locale.ROOT))) {
                    errors.add(DefinitionIssue.error("NODE_ON_ERROR_INVALID", id,
                            path + "/onError", "onError for " + type.name() + " must be one of "
                                    + String.join(", ", allowedOnError)));
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
                    && !SoarActionContractValidator.safeExpression(text(node, "expression"))) {
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
                    SoarManualFormValidator.validate(form, path + "/formSchema", errors, 0);
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
            String subPlaybookVersionId = text(node, "playbookVersionId");
            if (subPlaybookVersionId.isBlank()) {
                subPlaybookVersionId = text(node.path("config"), "playbookVersionId");
            }
            if (type == SoarNodeType.SUB_PLAYBOOK
                    && subPlaybookVersionId.isBlank()
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

        String start = starts.stream().findFirst().orElse(entry);
        if (start != null && types.containsKey(start)) {
            Set<String> reachable = SoarGraphValidator.reachable(graph, start);
            for (String id : types.keySet()) {
                if (!reachable.contains(id)) {
                    errors.add(DefinitionIssue.error("NODE_UNREACHABLE", id, "/nodes",
                            "node is not reachable from START"));
                }
            }
            Set<String> canReachEnd = SoarGraphValidator.reverseReachable(graph, ends);
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
            if (SoarGraphValidator.hasUnboundedCycle(graph, types)) {
                errors.add(DefinitionIssue.error("GRAPH_CYCLE_NOT_ALLOWED", null, "/edges",
                        "cycles are only supported by a bounded FOREACH construct"));
            }
        }
        // The engine executes PARALLEL branches and FOREACH bodies as child
        // workflows (SoarWorkflowBranchExecutor.runBranches), so a human gate
        // inside them can never receive its approval signal and the run fails
        // (CHILD_HUMAN_GATE_UNSUPPORTED) instead of pausing. Mirror that here so
        // publication fails with an actionable message.
        for (Map.Entry<String, SoarNodeType> node : types.entrySet()) {
            if (node.getValue() == SoarNodeType.PARALLEL) {
                List<String> branchStarts = graph.getOrDefault(node.getKey(), List.of());
                if (branchStarts.size() < 2) continue; // PARALLEL_BRANCHES_REQUIRED already reported
                String join = commonJoin(types, graph, branchStarts);
                if (join == null) {
                    errors.add(DefinitionIssue.error("PARALLEL_JOIN_REQUIRED", node.getKey(), "/edges",
                            "PARALLEL branches must converge on a JOIN before continuing"));
                    continue;
                }
                for (String gate : humanGatesInside(types, graph, branchStarts, Set.of(join))) {
                    errors.add(DefinitionIssue.error("HUMAN_GATE_IN_CHILD_WORKFLOW", gate, "/nodes",
                            "APPROVAL/MANUAL_TASK cannot wait inside a PARALLEL branch; move it after the JOIN"));
                }
            }
            if (node.getValue() == SoarNodeType.FOREACH && edges != null && edges.isArray()) {
                List<String> bodyStarts = new ArrayList<>();
                Set<String> doneNodes = new HashSet<>();
                for (JsonNode edge : edges) {
                    if (!node.getKey().equals(text(edge, "from"))) continue;
                    String port = text(edge, "port");
                    if (port.isBlank()) port = text(edge, "when");
                    String to = text(edge, "to");
                    if (to.isBlank()) continue;
                    if ("body".equalsIgnoreCase(port) || "each".equalsIgnoreCase(port)) bodyStarts.add(to);
                    if ("done".equalsIgnoreCase(port) || "success".equalsIgnoreCase(port)) doneNodes.add(to);
                }
                for (String gate : humanGatesInside(types, graph, bodyStarts, doneNodes)) {
                    errors.add(DefinitionIssue.error("HUMAN_GATE_IN_CHILD_WORKFLOW", gate, "/nodes",
                            "APPROVAL/MANUAL_TASK cannot wait inside a FOREACH body; move it after the loop"));
                }
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
        actionContracts.validateApprovalCoverage(types, nodeDefinitions, edges, warnings);
        actionContracts.validateCompensationRisk(types, nodeDefinitions, warnings);
        return result(errors, warnings, schemaVersion, hash, types.size(), actionCount, highRisk);
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
            errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", node.path("id").asText(),
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

    private static void validateManualFormSchema(JsonNode schema, String path,
                                                 List<DefinitionIssue> errors, int depth) {
        SoarManualFormValidator.validate(schema, path, errors, depth);
    }

    public static boolean safeManualPattern(String regex) {
        return SoarManualFormValidator.safePattern(regex);
    }

    /**
     * JOIN ids every PARALLEL branch can reach before leaving its own walk —
     * the same traversal as {@code SoarWorkflowGraphSupport.commonJoin}, which
     * the engine runs before spawning branch workflows. An empty result is what
     * makes the engine fail with PARALLEL_JOIN_REQUIRED.
     */
    private static String commonJoin(Map<String, SoarNodeType> types,
                                     Map<String, List<String>> graph, List<String> starts) {
        Set<String> candidates = null;
        for (String start : starts) {
            Set<String> reachable = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                if (!reachable.add(id)) continue;
                if (types.get(id) == SoarNodeType.JOIN) continue;
                queue.addAll(graph.getOrDefault(id, List.of()));
            }
            if (candidates == null) candidates = reachable;
            else candidates.retainAll(reachable);
        }
        return candidates == null ? null : candidates.stream()
                .filter(id -> types.get(id) == SoarNodeType.JOIN)
                .findFirst()
                .orElse(null);
    }

    /**
     * APPROVAL/MANUAL_TASK nodes reachable from child-workflow entry points
     * before reaching the node where the child stops. Such gates pause a child
     * workflow whose approval signal is only ever delivered to the root run, so
     * the engine fails them instead of waiting.
     */
    private static Set<String> humanGatesInside(Map<String, SoarNodeType> types,
                                                Map<String, List<String>> graph,
                                                List<String> starts, Set<String> stopAt) {
        Set<String> gates = new HashSet<>();
        for (String start : starts) {
            Set<String> visited = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                if (!visited.add(id) || stopAt.contains(id)) continue;
                SoarNodeType type = types.get(id);
                if (type == SoarNodeType.APPROVAL || type == SoarNodeType.MANUAL_TASK) gates.add(id);
                queue.addAll(graph.getOrDefault(id, List.of()));
            }
        }
        return gates;
    }

    /**
     * DELAY reads its duration exclusively from {@code config.durationSeconds}
     * (SoarWorkflowImpl DELAY branch), so a missing value must fail validation
     * instead of silently executing a zero-second delay. The top-level spelling
     * the old validator accepted is not read by the engine and is rejected too.
     */
    private static void validateDurationSeconds(JsonNode node, String path,
                                                List<DefinitionIssue> errors) {
        JsonNode config = node.path("config");
        JsonNode duration = config.isObject() && config.has("durationSeconds")
                ? config.get("durationSeconds") : null;
        if (duration == null) {
            errors.add(DefinitionIssue.error("DELAY_DURATION_REQUIRED", node.path("id").asText(),
                    path + "/config/durationSeconds", "DELAY requires config.durationSeconds"));
            return;
        }
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
