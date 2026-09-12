package com.socp.soar.web.temporal;

/** Durable activity result; unknown and failed are deliberately distinct. */
public record SoarNodeResult(
        String status,
        String outputJson,
        String errorCode,
        String errorMessage,
        boolean retryable
) {
    public SoarNodeResult(String status, String outputJson, String errorCode, String errorMessage) {
        this(status, outputJson, errorCode, errorMessage, false);
    }
}
