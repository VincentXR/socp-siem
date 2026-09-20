package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.soar.web.domain.DefinitionIssue;
import com.socp.soar.web.domain.SoarNodeType;

import java.util.List;

/** Approval, human-task timeout, delay, and workflow execution-limit validation. */
final class SoarExecutionPolicyValidator {

    private SoarExecutionPolicyValidator() {
    }

    static void validateRootApprovalPolicy(JsonNode root, List<DefinitionIssue> errors) {
        JsonNode policy = root.has("approvalPolicy") ? root.get("approvalPolicy") : root.get("policy");
        String path = root.has("approvalPolicy") ? "/approvalPolicy" : "/policy";
        if (policy == null) return;
        if (!policy.isObject()) {
            errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", null, path,
                    "approval policy must be an object"));
            return;
        }

        JsonNode required = policy.has("approvalsRequired")
                ? policy.get("approvalsRequired")
                : policy.get("requiredApprovals");
        if (required != null && (!isIntegerValue(required) || required.asInt() < 1 || required.asInt() > 20)) {
            errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", null,
                    path + "/approvalsRequired",
                    "approvalsRequired must be an integer from 1 to 20"));
        }
        for (String key : List.of("allowedRoles", "allowedGroups", "approverRoles", "approverGroups")) {
            validateApprovalPrincipalListAt(policy, key, path + "/" + key, null, errors);
        }
    }

    static void validateNodePolicy(SoarNodeType type, JsonNode node, String id, String path,
                                   List<DefinitionIssue> errors) {
        if (type == SoarNodeType.MANUAL_TASK) {
            JsonNode form = node.get("formSchema");
            if (form != null && !form.isObject()) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", id,
                        path + "/formSchema", "MANUAL_TASK formSchema must be an object"));
            }
            if (form != null && form.isObject()) {
                SoarManualFormValidator.validate(form, path + "/formSchema", errors, 0);
            }
            validateTimeout(node, path, "MANUAL_TASK", 30L * 24 * 3600, errors);
        } else if (type == SoarNodeType.APPROVAL) {
            if (node.has("policy") && !node.path("policy").isObject()) {
                errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", id,
                        path + "/policy", "approval policy must be an object"));
            }
            validateTimeout(node, path, "APPROVAL", 7L * 24 * 3600, errors);
            validateApprovalPolicy(node, path, errors);
        } else if (type == SoarNodeType.DELAY) {
            validateDurationSeconds(node, path, errors);
        }
    }

    static void validateRootLimits(JsonNode root, List<DefinitionIssue> errors) {
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
                        "/limits/executionTimeout",
                        "executionTimeout must be an ISO-8601 duration between PT1S and P30D"));
            }
        }
        int maxExec = intValue(limits.path("maxNodeExecutions"), SoarDefinitionValidator.MAX_NODE_EXECUTIONS);
        int parallelism = intValue(limits.path("maxParallelism"), SoarDefinitionValidator.MAX_PARALLELISM);
        if (maxExec < 1 || maxExec > SoarDefinitionValidator.MAX_NODE_EXECUTIONS) {
            errors.add(DefinitionIssue.error("NODE_EXECUTION_LIMIT_INVALID", null,
                    "/limits/maxNodeExecutions",
                    "maxNodeExecutions must be 1.." + SoarDefinitionValidator.MAX_NODE_EXECUTIONS));
        }
        if (parallelism < 1 || parallelism > SoarDefinitionValidator.MAX_PARALLELISM) {
            errors.add(DefinitionIssue.error("PARALLELISM_LIMIT_INVALID", null,
                    "/limits/maxParallelism",
                    "maxParallelism must be 1.." + SoarDefinitionValidator.MAX_PARALLELISM));
        }
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
        JsonNode required = policy.has("approvalsRequired")
                ? policy.get("approvalsRequired") : policy.get("requiredApprovals");
        if (required != null && (!isIntegerValue(required) || required.asInt() < 1 || required.asInt() > 20)) {
            errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID", node.path("id").asText(),
                    path + "/policy/approvalsRequired",
                    "approvalsRequired must be an integer from 1 to 20"));
        }
        for (String key : List.of("allowedRoles", "allowedGroups", "approverRoles", "approverGroups")) {
            validateApprovalPrincipalListAt(policy, key, path + "/policy/" + key, node, errors);
        }
    }

    private static void validateApprovalPrincipalListAt(JsonNode policy, String key, String fieldPath,
                                                        JsonNode node, List<DefinitionIssue> errors) {
        if (!policy.has(key)) return;
        JsonNode values = policy.get(key);
        if (!values.isArray() || values.isEmpty() || values.size() > 64) {
            errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID",
                    node == null ? null : node.path("id").asText(), fieldPath,
                    key + " must be a non-empty array with at most 64 values"));
            return;
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 128
                    || value.asText().contains("\u0000")) {
                errors.add(DefinitionIssue.error("APPROVAL_POLICY_INVALID",
                        node == null ? null : node.path("id").asText(), fieldPath,
                        key + " values must be non-blank strings of at most 128 characters"));
                break;
            }
        }
    }

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

    private static boolean validExecutionTimeout(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            java.time.Duration duration = java.time.Duration.parse(value);
            return !duration.isNegative() && !duration.isZero()
                    && duration.compareTo(java.time.Duration.ofDays(30)) <= 0;
        } catch (Exception ignored) {
            return false;
        }
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
}
