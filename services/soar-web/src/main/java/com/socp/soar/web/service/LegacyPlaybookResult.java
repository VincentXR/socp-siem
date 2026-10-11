package com.socp.soar.web.service;

import com.socp.soar.web.domain.PlaybookActionStatus;
import com.socp.soar.web.domain.PlaybookActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic result reducer shared by the two historical linear runners.
 * No clocks, UUIDs, activities or persistence belong here. Preserve historical
 * transitions, including skipped-action retry counts and compensation reset,
 * until a separately versioned migration changes their stored wire semantics.
 */
public final class LegacyPlaybookResult {

    private final List<Map<String, Object>> results = new ArrayList<>();
    private boolean previousFailed;
    private int retryCount;
    private String firstError;
    private boolean compensating;

    public boolean previousFailed() {
        return previousFailed;
    }

    public void accept(String action, Map<String, Object> result) {
        results.add(result);
        if (PlaybookActionType.compensation(action)) {
            previousFailed = false;
            compensating = true;
        } else if (!PlaybookActionStatus.isSuccessful(String.valueOf(result.get("status")))) {
            previousFailed = true;
            Object attempts = result.get("attempts");
            retryCount += attempts instanceof Number number ? number.intValue() : 1;
            if (firstError == null) {
                firstError = String.valueOf(result.getOrDefault("error", "action failed"));
            }
        } else {
            previousFailed = false;
        }
    }

    /** Append in the historical wire-field order without changing result payloads. */
    public void appendTo(Map<String, Object> execution) {
        boolean anyFailed = results.stream()
                .anyMatch(result -> PlaybookActionStatus.isFailed(String.valueOf(result.get("status"))));
        boolean anySimulated = results.stream()
                .anyMatch(result -> PlaybookActionStatus.SIMULATED.wireValue().equals(result.get("status")));
        String status = anyFailed
                ? (compensating ? "COMPENSATING" : "FAILED")
                : (anySimulated ? "SIMULATED" : "SUCCESS");
        execution.put("status", status);
        execution.put("retryCount", retryCount);
        if (firstError != null) execution.put("error", firstError);
        execution.put("results", results);
    }
}
