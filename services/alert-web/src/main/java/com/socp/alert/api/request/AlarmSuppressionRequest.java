package com.socp.alert.api.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Creates or extends one suppression window for a detection scope. */
public record AlarmSuppressionRequest(
        @Size(max = 255) String ruleId,
        @Size(max = 255) String entity,
        @Size(max = 32) String origin,
        @Size(max = 4096) String reason,
        @Size(max = 255) String alarmId,
        @Size(max = 128) String actor,
        @Positive Long windowSeconds,
        boolean ruleWide) {
}
