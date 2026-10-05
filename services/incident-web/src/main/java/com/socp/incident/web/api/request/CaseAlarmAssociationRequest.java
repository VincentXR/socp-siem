package com.socp.incident.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** A versioned command for one alarm's single authoritative case association. */
public record CaseAlarmAssociationRequest(
        @NotBlank @Size(max = 255) String alarmId,
        @NotBlank @Pattern(regexp = "ATTACH|DETACH|MOVE") String operation,
        @Size(max = 255) String targetCaseId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @PositiveOrZero Long targetExpectedVersion,
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotBlank @Size(max = 2000) String reason) { }
