package com.socp.soar.web.temporal.v2;

/** Durable activity result; unknown and failed are deliberately distinct. */
public record SoarV2NodeResult(
        String status,
        String outputJson,
        String errorCode,
        String errorMessage,
        boolean retryable
) {
    public SoarV2NodeResult(String status, String outputJson, String errorCode, String errorMessage) {
        this(status, outputJson, errorCode, errorMessage, false);
    }
}
