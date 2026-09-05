package com.socp.soar.web.connector;

import java.util.List;
import java.util.Map;

/** Action schema and safety metadata; schemas are intentionally JSON trees. */
public record ActionDescriptor(String id, int majorVersion, String displayName,
                               String description, String riskLevel, String sideEffect,
                               String idempotency, boolean requiresConnection,
                               List<String> allowedTargetTypes, Map<String, Object> inputSchema,
                               Map<String, Object> outputSchema,
                               List<String> requiredPermissions) {
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

    public ActionDescriptor {
        allowedTargetTypes = allowedTargetTypes == null ? List.of() : List.copyOf(allowedTargetTypes);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        requiredPermissions = requiredPermissions == null ? List.of("soar:execute") : List.copyOf(requiredPermissions);
    }

    public String ref(String connectorId) {
        return connectorId + "/" + id + "@" + majorVersion;
    }
}
