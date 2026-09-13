package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.connector.ActionDescriptor;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.domain.DefinitionIssue;
import com.socp.soar.web.domain.SoarNodeType;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates connector-backed action contracts independently from graph
 * structure.  Keeping schema and risk policy here prevents the structural
 * validator from becoming a second action catalog implementation.
 */
final class SoarActionContractValidator {

    private final ObjectMapper mapper;
    private final SoarConnectorRegistry connectorRegistry;

    SoarActionContractValidator(ObjectMapper mapper, SoarConnectorRegistry connectorRegistry) {
        this.mapper = mapper;
        this.connectorRegistry = connectorRegistry;
    }

    void validateActionContract(JsonNode node, String nodeId, String path,
                                String actionRef, List<DefinitionIssue> errors) {
        if (connectorRegistry == null || actionRef == null || actionRef.isBlank()) return;
        var descriptor = connectorRegistry.descriptorForAction(actionRef).orElse(null);
        if (descriptor == null) return;
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
        } else {
            validateActionParameters(action.inputSchema(),
                    parameters == null ? mapper.createObjectNode() : parameters,
                    nodeId, path, errors);
        }
    }

    String actionRiskLevel(String actionRef) {
        if (actionRef != null && !actionRef.isBlank() && connectorRegistry != null) {
            ActionDescriptor action = connectorRegistry.actionDescriptor(actionRef).orElse(null);
            if (action != null && action.riskLevel() != null && !action.riskLevel().isBlank()) {
                return action.riskLevel();
            }
        }
        return looksHighRisk(actionRef) ? "HIGH" : "";
    }

    boolean hasNonIdempotentSideEffect(String actionRef) {
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

    void validateApprovalCoverage(Map<String, SoarNodeType> types,
                                  Map<String, JsonNode> nodes, JsonNode edges,
                                  List<DefinitionIssue> warnings) {
        if (edges == null || !edges.isArray()) return;
        Set<String> controlledActionRefs = new HashSet<>();
        Set<String> controlledTargets = new HashSet<>();
        for (JsonNode edge : edges) {
            String from = text(edge, "from");
            String to = text(edge, "to");
            if (!types.containsKey(from) || !types.containsKey(to)) continue;
            SoarNodeType type = types.get(from);
            String port = text(edge, "port");
            if (port.isBlank()) port = text(edge, "when");
            if (type == SoarNodeType.APPROVAL && ("approved".equalsIgnoreCase(port)
                    || "success".equalsIgnoreCase(port) || port.isBlank())) {
                controlledTargets.add(to);
                JsonNode targetNode = nodes.get(to);
                if (targetNode != null && types.get(to) == SoarNodeType.ACTION) {
                    String actionRef = text(targetNode, "actionRef");
                    if (!actionRef.isBlank()) controlledActionRefs.add(actionRef);
                }
            }
        }
        for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
            JsonNode node = entry.getValue();
            if (types.get(entry.getKey()) != SoarNodeType.ACTION) continue;
            String actionRef = text(node, "actionRef");
            if (actionRef.isBlank() || !"HIGH".equalsIgnoreCase(actionRiskLevel(actionRef))) continue;
            boolean covered = controlledActionRefs.contains(actionRef)
                    || controlledTargets.contains(entry.getKey());
            if (!covered) {
                warnings.add(DefinitionIssue.warning(
                        "HIGH_RISK_APPROVAL_GATE_RECOMMENDED", entry.getKey(),
                        "/nodes/" + entry.getKey(),
                        "high-risk action is not reachable through an explicit APPROVAL gate; it relies on the run-level pre-approval policy"));
            }
        }
    }

    void validateCompensationRisk(Map<String, SoarNodeType> types,
                                  Map<String, JsonNode> nodes,
                                  List<DefinitionIssue> warnings) {
        if (connectorRegistry == null) return;
        for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
            JsonNode node = entry.getValue();
            if (types.get(entry.getKey()) != SoarNodeType.ACTION) continue;
            String compensationRef = text(node, "compensationRef");
            if (compensationRef.isBlank()) continue;
            String risk = actionRiskLevel(compensationRef);
            if ("HIGH".equalsIgnoreCase(risk) || "CRITICAL".equalsIgnoreCase(risk)) {
                warnings.add(DefinitionIssue.warning("COMPENSATION_HIGH_RISK", entry.getKey(),
                        "/nodes/" + entry.getKey() + "/compensationRef",
                        "compensation action " + compensationRef + " is " + risk
                                + "; ensure the run-level approval policy covers it before relying on COMPENSATE_THEN_FAIL"));
            }
        }
    }

    static boolean containsCredentialValue(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (containsCredentialValue(field.getValue())) return true;
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) if (containsCredentialValue(item)) return true;
        } else if (value.isTextual()) {
            String text = value.asText();
            if (text == null || text.length() > 4096) return false;
            if (text.matches("(?i)^[a-z][a-z0-9+.-]*://[^\\s:/?#]+:[^\\s@/]+@.*")) return true;
            if (text.contains("-----BEGIN") && text.contains("PRIVATE KEY")) return true;
            if (text.matches("(?i)^(bearer|token|apikey|api[-_]?key|authorization|secret)\\s*[:=]\\s*\\S+$")) return true;
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            if (text.matches("(?i)^[A-Za-z0-9_\\-]{40,}$")
                    && (lower.startsWith("ghp_") || lower.startsWith("sk-"))) return true;
        }
        return false;
    }

    static boolean safeExpression(String expression) {
        return SoarExpressionEngine.isSafe(expression);
    }

    private static boolean looksHighRisk(String actionRef) {
        String value = actionRef == null ? "" : actionRef.toLowerCase(java.util.Locale.ROOT);
        return value.contains("isolate") || value.contains("block") || value.contains("disable")
                || value.contains("delete") || value.contains("snapshot");
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return "";
        return node.path(field).asText("").trim();
    }

    private void validateActionParameters(Map<String, Object> schemaMap, JsonNode parameters,
                                          String nodeId, String nodePath,
                                          List<DefinitionIssue> errors) {
        if (schemaMap == null || schemaMap.isEmpty()) return;
        JsonNode schema = mapper.valueToTree(schemaMap);
        if (schema == null || !schema.isObject()) return;
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode item : required) {
                if (item != null && item.isTextual() && !parameters.has(item.asText())) {
                    errors.add(DefinitionIssue.error("ACTION_PARAMETER_REQUIRED", nodeId,
                            nodePath + "/parameters/" + item.asText(),
                            "required action parameter is missing"));
                }
            }
        }
        JsonNode properties = schema.get("properties");
        boolean rejectAdditional = schema.has("additionalProperties")
                && schema.get("additionalProperties").isBoolean()
                && !schema.get("additionalProperties").asBoolean();
        if (properties == null || !properties.isObject()) {
            if (rejectAdditional) {
                var fields = parameters.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    errors.add(DefinitionIssue.error("ACTION_PARAMETER_UNKNOWN", nodeId,
                            nodePath + "/parameters/" + field.getKey(),
                            "parameter is not declared by the action schema"));
                }
            }
            return;
        }
        var fields = parameters.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String name = field.getKey();
            JsonNode propertySchema = properties.get(name);
            if (propertySchema == null || propertySchema.isNull()) {
                if (rejectAdditional) {
                    errors.add(DefinitionIssue.error("ACTION_PARAMETER_UNKNOWN", nodeId,
                            nodePath + "/parameters/" + name,
                            "parameter is not declared by the action schema"));
                }
                continue;
            }
            JsonNode value = field.getValue();
            if (isExpressionReference(value)) {
                String expression = value.path("$expr").asText("");
                if (expression.length() > 4096 || !safeExpression(expression)) {
                    errors.add(DefinitionIssue.error("ACTION_PARAMETER_EXPRESSION_INVALID", nodeId,
                            nodePath + "/parameters/" + name + "/$expr",
                            "parameter expression is unsafe or exceeds 4 KiB"));
                }
                continue;
            }
            validateActionValue(value, propertySchema, nodeId,
                    nodePath + "/parameters/" + name, errors, 0);
        }
    }

    private static boolean isExpressionReference(JsonNode value) {
        return value != null && value.isObject() && value.size() == 1
                && value.has("$expr") && value.path("$expr").isTextual();
    }

    private void validateActionValue(JsonNode value, JsonNode schema, String nodeId,
                                     String path, List<DefinitionIssue> errors, int depth) {
        if (depth > 8 || schema == null || !schema.isObject()) return;
        JsonNode declared = schema.get("type");
        boolean typeMatches = declared == null || declared.isNull();
        if (declared != null && declared.isTextual()) {
            typeMatches = schemaTypeMatches(value, declared.asText(""));
        } else if (declared != null && declared.isArray()) {
            for (JsonNode candidate : declared) {
                if (candidate.isTextual() && schemaTypeMatches(value, candidate.asText(""))) {
                    typeMatches = true;
                    break;
                }
            }
        }
        if (!typeMatches) {
            errors.add(DefinitionIssue.error("ACTION_PARAMETER_TYPE_INVALID", nodeId, path,
                    "parameter does not match its action schema type"));
            return;
        }
        if (value != null && value.isTextual()) {
            JsonNode maxLength = schema.get("maxLength");
            if (maxLength != null && maxLength.isIntegralNumber()
                    && value.asText().length() > maxLength.asInt()) {
                errors.add(DefinitionIssue.error("ACTION_PARAMETER_SIZE_INVALID", nodeId, path,
                        "string parameter exceeds its action schema maxLength"));
            }
        }
        if (value != null && value.isArray()) {
            JsonNode maxItems = schema.get("maxItems");
            if (maxItems != null && maxItems.isIntegralNumber()
                    && value.size() > maxItems.asInt()) {
                errors.add(DefinitionIssue.error("ACTION_PARAMETER_SIZE_INVALID", nodeId, path,
                        "array parameter exceeds its action schema maxItems"));
            }
            JsonNode itemSchema = schema.get("items");
            if (itemSchema != null && itemSchema.isObject()) {
                for (int index = 0; index < value.size(); index++) {
                    validateActionValue(value.get(index), itemSchema, nodeId,
                            path + "/" + index, errors, depth + 1);
                }
            }
        } else if (value != null && value.isObject()) {
            JsonNode maxProperties = schema.get("maxProperties");
            if (maxProperties != null && maxProperties.isIntegralNumber()
                    && value.size() > maxProperties.asInt()) {
                errors.add(DefinitionIssue.error("ACTION_PARAMETER_SIZE_INVALID", nodeId, path,
                        "object parameter exceeds its action schema maxProperties"));
            }
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode item : required) {
                    if (item != null && item.isTextual() && !value.has(item.asText())) {
                        errors.add(DefinitionIssue.error("ACTION_PARAMETER_REQUIRED", nodeId,
                                path + "/" + item.asText(),
                                "required action parameter is missing"));
                    }
                }
            }
            JsonNode properties = schema.get("properties");
            boolean rejectAdditional = schema.has("additionalProperties")
                    && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean();
            if (properties != null && properties.isObject()) {
                var fields = value.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    JsonNode propertySchema = properties.get(field.getKey());
                    if (propertySchema == null || propertySchema.isNull()) {
                        if (rejectAdditional) {
                            errors.add(DefinitionIssue.error("ACTION_PARAMETER_UNKNOWN", nodeId,
                                    path + "/" + field.getKey(),
                                    "parameter is not declared by the action schema"));
                        }
                        continue;
                    }
                    JsonNode child = field.getValue();
                    if (isExpressionReference(child)) {
                        String expression = child.path("$expr").asText("");
                        if (expression.length() > 4096 || !safeExpression(expression)) {
                            errors.add(DefinitionIssue.error("ACTION_PARAMETER_EXPRESSION_INVALID", nodeId,
                                    path + "/" + field.getKey() + "/$expr",
                                    "parameter expression is unsafe or exceeds 4 KiB"));
                        }
                        continue;
                    }
                    validateActionValue(child, propertySchema, nodeId,
                            path + "/" + field.getKey(), errors, depth + 1);
                }
            } else if (rejectAdditional) {
                var fields = value.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    errors.add(DefinitionIssue.error("ACTION_PARAMETER_UNKNOWN", nodeId,
                            path + "/" + field.getKey(),
                            "parameter is not declared by the action schema"));
                }
            }
        }
    }

    private static boolean schemaTypeMatches(JsonNode value, String type) {
        if (value == null || value.isMissingNode()) return false;
        return switch (type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT)) {
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
}
