package com.socp.soar.web.temporal.v2;

/** Typed workflow result; JSON maps remain confined to the definition boundary. */
public record SoarV2WorkflowResult(
        String runId,
        String versionId,
        String status,
        String nodesJson,
        String errorCode,
        String errorMessage,
        String variablesJson
) {
    /** Compatibility constructor for callers that only need the old result fields. */
    public SoarV2WorkflowResult(String runId, String versionId, String status,
                                String nodesJson, String errorCode, String errorMessage) {
        this(runId, versionId, status, nodesJson, errorCode, errorMessage, "{}");
    }
}
