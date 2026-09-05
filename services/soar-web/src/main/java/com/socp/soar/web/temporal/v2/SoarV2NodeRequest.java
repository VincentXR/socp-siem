package com.socp.soar.web.temporal.v2;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Activity request for one graph node. */
public record SoarV2NodeRequest(
        String tenantId,
        String runId,
        String nodeId,
        String nodeType,
        String actionRef,
        String iterationPath,
        String inputJson,
        String idempotencyKey,
        String connectionRef,
        Map<String, Object> target,
        int attemptNo
) {
    public SoarV2NodeRequest {
        target = target == null || target.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(target));
    }

    /** Compatibility constructor for pre-connector workflow histories/tests. */
    public SoarV2NodeRequest(String tenantId, String runId, String nodeId, String nodeType,
                             String actionRef, String iterationPath, String inputJson,
                             String idempotencyKey) {
        this(tenantId, runId, nodeId, nodeType, actionRef, iterationPath, inputJson,
                idempotencyKey, "", Map.of(), 0);
    }

    /** Compatibility constructor for callers that already provide connector context. */
    public SoarV2NodeRequest(String tenantId, String runId, String nodeId, String nodeType,
                             String actionRef, String iterationPath, String inputJson,
                             String idempotencyKey, String connectionRef,
                             Map<String, Object> target) {
        this(tenantId, runId, nodeId, nodeType, actionRef, iterationPath, inputJson,
                idempotencyKey, connectionRef, target, 0);
    }
}
