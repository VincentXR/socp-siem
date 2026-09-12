package com.socp.soar.web.temporal;

/** Workflow lifecycle update persisted by an activity in the service process. */
public record SoarRunUpdate(
        String tenantId,
        String runId,
        String status,
        String outputJson,
        String errorCode,
        String errorMessage
) { }
