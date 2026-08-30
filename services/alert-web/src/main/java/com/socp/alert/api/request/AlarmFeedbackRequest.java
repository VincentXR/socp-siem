package com.socp.alert.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Analyst feedback used for false-positive review and time-bounded rule exceptions. */
public record AlarmFeedbackRequest(
        @NotBlank
        @Pattern(regexp = "FALSE_POSITIVE|RULE_EXCEPTION",
                message = "kind must be FALSE_POSITIVE or RULE_EXCEPTION")
        String kind,
        @NotBlank @Size(max = 4096) String reason,
        Instant expiresAt,
        @Size(max = 128) String actor
) {
}
