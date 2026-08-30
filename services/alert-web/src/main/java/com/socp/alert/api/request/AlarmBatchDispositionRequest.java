package com.socp.alert.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Bounded, idempotent-in-effect alarm triage update.  The operation is a
 * state replacement (not an event replay), so repeating the same request is
 * safe even when the caller does not have an idempotency key.
 */
public record AlarmBatchDispositionRequest(
        @NotEmpty(message = "alarmIds must not be empty")
        @Size(max = 500, message = "at most 500 alarms may be updated at once")
        List<@Valid @NotBlank(message = "alarm id must not be blank")
                @Size(max = 255, message = "alarm id is too long") String> alarmIds,
        @Pattern(regexp = "OPEN|INVESTIGATING|RESOLVED|CLOSED",
                message = "status must be OPEN, INVESTIGATING, RESOLVED or CLOSED")
        String status,
        @Size(max = 128, message = "assignee is too long")
        String assignee,
        @Size(max = 4096, message = "reason is too long")
        String reason
) {
}
