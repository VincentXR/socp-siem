package com.socp.soar.web.temporal;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the immutable, bounded evidence attached to an approval gate. */
final class SoarWorkflowApprovalSupport {
    private static final int MAX_SNAPSHOT_BYTES = 256 * 1024;
    private final SoarWorkflowImpl workflow;

    SoarWorkflowApprovalSupport(SoarWorkflowImpl workflow) {
        this.workflow = workflow;
    }

    /** Immutable context displayed to an approver and bound to the gate. */
    record ApprovalGateContext(String actionRef, String inputHash,
                               String targetSnapshotJson) { }

    /**
     * Resolve the action controlled by an APPROVAL node and capture only a
     * bounded, redacted target snapshot. The hash is over sanitized input so
     * a later decision can be proven to match the original parameters.
     */
    ApprovalGateContext approvalGateContext(String nodeId, JsonNode approvalNode) {
        JsonNode controlled = approvalNode;
        String actionRef = approvalNode == null ? ""
                : approvalNode.path("actionRef").asText("").trim();
        if (actionRef.isBlank() && approvalNode != null) {
            String next = workflow.nextNode(nodeId, "approved");
            JsonNode candidate = workflow.findNode(workflow.rootNode().path("nodes"), next);
            if (candidate != null && "ACTION".equalsIgnoreCase(candidate.path("type").asText(""))) {
                controlled = candidate;
                actionRef = candidate.path("actionRef").asText("").trim();
            }
        }
        Map<String, Object> input = controlled == null ? Map.of() : workflow.actionInput(controlled);
        String inputJson = workflow.writeJson(workflow.redactForConnector("input", input));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (!actionRef.isBlank()) snapshot.put("actionRef", actionRef);
        if (controlled != null && controlled.path("connectionRef").isTextual()
                && !controlled.path("connectionRef").asText("").isBlank()) {
            snapshot.put("connectionRef", controlled.path("connectionRef").asText(""));
        }
        if (controlled != null && controlled.has("target")) {
            Map<String, Object> target = workflow.readObject(controlled.path("target").toString());
            snapshot.put("target", workflow.redactForConnector("target", target));
        }
        Map<String, Object> policy = approvalPolicySnapshot(approvalNode);
        if (!policy.isEmpty()) snapshot.put("approvalPolicy", policy);
        String snapshotJson = workflow.writeJson(snapshot);
        byte[] snapshotBytes = snapshotJson.getBytes(StandardCharsets.UTF_8);
        if (snapshotBytes.length > MAX_SNAPSHOT_BYTES) {
            snapshotJson = workflow.writeJson(Map.of("truncated", true,
                    "sha256", sha256Hex(snapshotJson), "originalBytes", snapshotBytes.length));
        }
        return new ApprovalGateContext(actionRef, sha256Hex(inputJson), snapshotJson);
    }

    private Map<String, Object> approvalPolicySnapshot(JsonNode approvalNode) {
        if (approvalNode == null) return Map.of();
        JsonNode policy = approvalNode.path("policy").isObject()
                ? approvalNode.path("policy") : approvalNode.path("config");
        if (!policy.isObject()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        copyPolicyList(policy, result, "allowedRoles", "approverRoles");
        copyPolicyList(policy, result, "allowedGroups", "approverGroups");
        if (policy.has("approvalsRequired") && policy.path("approvalsRequired").isIntegralNumber()) {
            result.put("approvalsRequired", Math.max(1,
                    Math.min(20, policy.path("approvalsRequired").asInt())));
        } else if (policy.has("requiredApprovals") && policy.path("requiredApprovals").isIntegralNumber()) {
            result.put("approvalsRequired", Math.max(1,
                    Math.min(20, policy.path("requiredApprovals").asInt())));
        }
        return result;
    }

    private void copyPolicyList(JsonNode policy, Map<String, Object> target,
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

    private static String sha256Hex(String value) {
        return SoarWorkflowGraphSupport.sha256Hex(value == null ? "" : value);
    }
}
