package com.socp.soar.web.connector;

import java.util.List;
import java.util.Map;

/** Action schema and safety metadata; schemas are intentionally JSON trees. */
public record ActionDescriptor(String id, int majorVersion, String displayName,
                               String description, String riskLevel, String sideEffect,
                               String idempotency, boolean requiresConnection,
                               List<String> allowedTargetTypes, Map<String, Object> inputSchema,
                               Map<String, Object> outputSchema,
                               List<String> requiredPermissions,
                               Integer requestTimeoutSeconds, Integer retryCap,
                               Long payloadCapBytes, List<String> sensitiveOutputFields,
                               boolean supportsReconcile, boolean supportsCompensate) {
    /** Source-compatible constructor for connector implementations compiled
     * against the original eleven-field descriptor. */
    public ActionDescriptor(String id, int majorVersion, String displayName,
                            String description, String riskLevel, String sideEffect,
                            String idempotency, boolean requiresConnection,
                            List<String> allowedTargetTypes, Map<String, Object> inputSchema,
                            Map<String, Object> outputSchema) {
        this(id, majorVersion, displayName, description, riskLevel, sideEffect,
                idempotency, requiresConnection, allowedTargetTypes, inputSchema,
                outputSchema, List.of("soar:execute"));
    }

    /** Source-compatible constructor for the twelve-field descriptor. */
    public ActionDescriptor(String id, int majorVersion, String displayName,
                            String description, String riskLevel, String sideEffect,
                            String idempotency, boolean requiresConnection,
                            List<String> allowedTargetTypes, Map<String, Object> inputSchema,
                            Map<String, Object> outputSchema,
                            List<String> requiredPermissions) {
        this(id, majorVersion, displayName, description, riskLevel, sideEffect,
                idempotency, requiresConnection, allowedTargetTypes, inputSchema,
                outputSchema, requiredPermissions, 60, 3, 10L * 1024 * 1024,
                List.of(), false, false);
    }

    public ActionDescriptor {
        allowedTargetTypes = allowedTargetTypes == null ? List.of() : List.copyOf(allowedTargetTypes);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        requiredPermissions = requiredPermissions == null ? List.of("soar:execute") : List.copyOf(requiredPermissions);
        sensitiveOutputFields = sensitiveOutputFields == null ? List.of() : List.copyOf(sensitiveOutputFields);
        requestTimeoutSeconds = requestTimeoutSeconds == null ? 60
                : Math.max(1, Math.min(300, requestTimeoutSeconds));
        retryCap = retryCap == null ? 3 : Math.max(0, Math.min(10, retryCap));
        payloadCapBytes = payloadCapBytes == null ? 10L * 1024 * 1024
                : Math.max(1L, Math.min(100L * 1024 * 1024, payloadCapBytes));
    }

    public String ref(String connectorId) {
        return connectorId + "/" + id + "@" + majorVersion;
    }
}
