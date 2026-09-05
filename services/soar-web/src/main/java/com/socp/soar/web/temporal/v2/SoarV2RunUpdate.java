package com.socp.soar.web.temporal.v2;

/** Workflow lifecycle update persisted by an activity in the service process. */
public record SoarV2RunUpdate(
        String tenantId,
        String runId,
        String status,
        String outputJson,
        String errorCode,
        String errorMessage
) { }
