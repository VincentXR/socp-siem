package com.socp.soar.web.connector;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record ActionQuery(String tenantId, String runId, String nodeRunId,
                          String actionRef, String idempotencyKey,
                          Map<String, Object> target,
                          Map<String, Object> parameters) {
    public ActionQuery(String tenantId, String runId, String nodeRunId,
                       String actionRef, String idempotencyKey,
                       Map<String, Object> target) {
        this(tenantId, runId, nodeRunId, actionRef, idempotencyKey, target, Map.of());
    }

    public ActionQuery {
        target = target == null || target.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(target));
        parameters = parameters == null || parameters.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
