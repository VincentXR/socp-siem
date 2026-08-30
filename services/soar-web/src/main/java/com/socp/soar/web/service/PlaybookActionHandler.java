package com.socp.soar.web.service;

import com.socp.soar.web.domain.PlaybookActionType;

import java.util.Map;

/** Executes exactly one typed SOAR action family. */
public interface PlaybookActionHandler {

    PlaybookActionType type();

    /**
     * Validate the action before any connector side effect is attempted.
     *
     * <p>The default keeps existing handlers source compatible while making
     * the lifecycle explicit for connectors that need stronger validation.
     * A returned {@code status=failed} (or {@code errorCode}) stops the
     * action before {@link #prepare(PlaybookActionContext)} is called.</p>
     */
    default Map<String, Object> validate(PlaybookActionContext context) {
        return Map.of("status", "validated");
    }

    /** Prepare a connector request without executing it. */
    default Map<String, Object> prepare(PlaybookActionContext context) {
        return Map.of("status", "prepared");
    }

    /**
     * Connector-local approval hook. High-risk approval is enforced by the
     * SOAR service policy; this hook is for connector-specific gates.
     */
    default Map<String, Object> requestApproval(PlaybookActionContext context) {
        return Map.of("status", "approval_not_required");
    }

    /** Execute the prepared action. Existing handlers implement {@link #handle}. */
    default Map<String, Object> execute(PlaybookActionContext context) {
        return handle(context);
    }

    /** Poll an asynchronous connector operation. */
    default Map<String, Object> pollStatus(PlaybookActionContext context, String operationId) {
        return Map.of("status", "unknown", "operationId", operationId == null ? "" : operationId);
    }

    /** Cancel an asynchronous connector operation. */
    default Map<String, Object> cancel(PlaybookActionContext context, String operationId) {
        return Map.of("status", "unknown", "operationId", operationId == null ? "" : operationId);
    }

    Map<String, Object> handle(PlaybookActionContext context);
}
