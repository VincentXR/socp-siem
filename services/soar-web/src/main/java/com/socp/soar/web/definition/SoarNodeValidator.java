package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.domain.DefinitionIssue;
import com.socp.soar.web.domain.SoarNodeType;
import com.socp.soar.web.service.SoarActionCatalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Intrinsic node validation that does not depend on graph connectivity. */
final class SoarNodeValidator {

    record Counts(int actions, int highRisk) {
    }

    private final SoarConnectorRegistry connectorRegistry;
    private final SoarActionContractValidator actionContracts;

    SoarNodeValidator(SoarConnectorRegistry connectorRegistry,
                      SoarActionContractValidator actionContracts) {
        this.connectorRegistry = connectorRegistry;
        this.actionContracts = actionContracts;
    }

    Counts validate(JsonNode node, String id, String path, SoarNodeType type,
                    List<DefinitionIssue> errors) {
        int actions = 0;
        int highRisk = 0;

        if (node.has("config") && !node.path("config").isObject()) {
            errors.add(DefinitionIssue.error("NODE_CONFIG_INVALID", id, path + "/config",
                    "node config must be an object"));
        }
        if (node.has("limits") && !node.path("limits").isObject()) {
            errors.add(DefinitionIssue.error("NODE_LIMITS_INVALID", id, path + "/limits",
                    "node limits must be an object"));
        }

        if (type == SoarNodeType.ACTION) {
            actions++;
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
                errors.add(DefinitionIssue.error("ACTION_RISK_CRITICAL_FORBIDDEN", id,
                        path + "/actionRef",
                        "CRITICAL actions are disabled in this release; no P0 execution path exists"));
            } else if ("HIGH".equalsIgnoreCase(actionRisk)) {
                highRisk++;
            }
            validateActionSecurityAndRetry(node, id, path, actionRef, errors);
        }

        if (type == SoarNodeType.ACTION || type == SoarNodeType.JOIN || type == SoarNodeType.FOREACH) {
            String onError = text(node, "onError");
            if (onError.isBlank()) onError = text(node.path("config"), "onError");
            Set<String> allowedOnError = switch (type) {
                case ACTION -> Set.of("FAIL_RUN", "CONTINUE", "GOTO_ERROR_PORT", "COMPENSATE_THEN_FAIL");
                case JOIN -> Set.of("FAIL_RUN", "CONTINUE", "GOTO_ERROR_PORT");
                default -> Set.of("FAIL_RUN", "CONTINUE");
            };
            if (!onError.isBlank() && !allowedOnError.contains(onError.toUpperCase(Locale.ROOT))) {
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
                "PARTIALLY_SUCCEEDED").contains(text(node, "outcome").toUpperCase(Locale.ROOT))) {
            errors.add(DefinitionIssue.error("END_OUTCOME_INVALID", id, path + "/outcome",
                    "END outcome is not supported"));
        }
        if ((type == SoarNodeType.CONDITION || type == SoarNodeType.SWITCH)
                && !SoarActionContractValidator.safeExpression(text(node, "expression"))) {
            errors.add(DefinitionIssue.error("EXPRESSION_NOT_ALLOWED", id,
                    path + "/expression", "expression contains unsupported or unsafe syntax"));
        }

        validateControlNode(type, node, id, path, errors);
        SoarExecutionPolicyValidator.validateNodePolicy(type, node, id, path, errors);
        validateSetVariable(type, node, id, path, errors);
        validateSubPlaybook(type, node, id, path, errors);

        return new Counts(actions, highRisk);
    }

    private void validateActionSecurityAndRetry(JsonNode node, String id, String path,
                                                String actionRef,
                                                List<DefinitionIssue> errors) {
        JsonNode retry = node.has("retry") ? node.get("retry")
                : (node.has("retryPolicy") ? node.get("retryPolicy") : node.path("config").get("retry"));
        if (containsSensitiveKey(node.get("parameters")) || containsSensitiveKey(node.get("target"))) {
            errors.add(DefinitionIssue.error("ACTION_SECRET_INLINE_FORBIDDEN", id,
                    path, "action parameters/target cannot contain secret, token, password or authorization values; use a connection secretRef"));
        }
        if (SoarActionContractValidator.containsCredentialValue(node.get("parameters"))
                || SoarActionContractValidator.containsCredentialValue(node.get("target"))) {
            errors.add(DefinitionIssue.error("ACTION_EMBEDDED_CREDENTIAL_FORBIDDEN", id,
                    path, "action parameters/target cannot embed credentials (user:pass@ URLs, private keys or bearer/token values)"
                            + " beyond a secretRef reference"));
        }
        if (retry != null && !retry.isObject()) {
            errors.add(DefinitionIssue.error("ACTION_RETRY_POLICY_INVALID", id,
                    path + "/retry", "retry policy must be an object"));
        }
        if (retry == null || !retry.isObject()) return;

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
                ? retry.path("backoffSeconds").asLong()
                : durationSeconds(retry.path("initialInterval").asText(""), 0);
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

    private static void validateControlNode(SoarNodeType type, JsonNode node, String id, String path,
                                            List<DefinitionIssue> errors) {
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
            if (concurrency < 1 || concurrency > SoarDefinitionValidator.MAX_PARALLELISM) {
                errors.add(DefinitionIssue.error("FOREACH_CONCURRENCY_INVALID", id,
                        path + "/limits/concurrency",
                        "FOREACH concurrency must be 1.." + SoarDefinitionValidator.MAX_PARALLELISM));
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
            if (maxParallelism < 1 || maxParallelism > SoarDefinitionValidator.MAX_PARALLELISM) {
                errors.add(DefinitionIssue.error("PARALLELISM_LIMIT_INVALID", id,
                        path + "/limits/maxParallelism",
                        "PARALLEL maxParallelism must be 1.." + SoarDefinitionValidator.MAX_PARALLELISM));
            }
        }
        if (type == SoarNodeType.JOIN) {
            String strategy = text(node, "strategy");
            if (!strategy.isBlank()
                    && !Set.of("ALL_SUCCESS", "ALL_DONE", "ANY_SUCCESS")
                    .contains(strategy.toUpperCase(Locale.ROOT))) {
                errors.add(DefinitionIssue.error("JOIN_STRATEGY_INVALID", id, path + "/strategy",
                        "JOIN strategy must be ALL_SUCCESS, ALL_DONE or ANY_SUCCESS"));
            }
        }
    }

    private static void validateSetVariable(SoarNodeType type, JsonNode node, String id, String path,
                                            List<DefinitionIssue> errors) {
        if (type != SoarNodeType.SET_VARIABLE) return;
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

    private static void validateSubPlaybook(SoarNodeType type, JsonNode node, String id, String path,
                                            List<DefinitionIssue> errors) {
        if (type != SoarNodeType.SUB_PLAYBOOK) return;
        String versionId = text(node, "playbookVersionId");
        if (versionId.isBlank()) versionId = text(node.path("config"), "playbookVersionId");
        if (versionId.isBlank() && (!node.has("definition") || !node.path("definition").isObject())) {
            errors.add(DefinitionIssue.error("SUB_PLAYBOOK_REFERENCE_REQUIRED", id,
                    path, "SUB_PLAYBOOK requires a published playbookVersionId or resolved definition"));
        }
    }

    private static boolean containsSensitiveKey(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                if (key.contains("secret") || key.contains("token") || key.contains("password")
                        || key.contains("authorization") || key.equals("cookie")) return true;
                if (containsSensitiveKey(field.getValue())) return true;
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) if (containsSensitiveKey(item)) return true;
        }
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

    private static boolean validDuration(String value) {
        if (value == null || value.isBlank()) return true;
        try {
            return java.time.Duration.parse(value).compareTo(java.time.Duration.ZERO) >= 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long durationSeconds(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Math.max(0, java.time.Duration.parse(value).toSeconds());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
