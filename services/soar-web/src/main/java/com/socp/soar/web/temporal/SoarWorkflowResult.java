package com.socp.soar.web.temporal;

/** Typed workflow result; JSON maps remain confined to the definition boundary. */
public record SoarWorkflowResult(
        String runId,
        String versionId,
        String status,
        String nodesJson,
        String errorCode,
        String errorMessage,
        String variablesJson
) {
    /** Compatibility constructor for callers that only need the old result fields. */
    public SoarWorkflowResult(String runId, String versionId, String status,
                                String nodesJson, String errorCode, String errorMessage) {
        this(runId, versionId, status, nodesJson, errorCode, errorMessage, "{}");
    }
}
