package com.socp.incident.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** A versioned, replay-safe analyst command. Null assignee preserves ownership; empty releases it. */
public record CaseChangeRequest(
        @NotBlank @Size(max = 32) String status,
        @Size(max = 255) String assignee,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Size(max = 32) String classification,
        @Size(max = 2000) String result,
        @Size(max = 4000) String reason,
        @Size(max = 8000) String evidence,
        @Size(max = 4000) String remainingActions) { }
