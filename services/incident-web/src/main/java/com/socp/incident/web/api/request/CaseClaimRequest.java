package com.socp.incident.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CaseClaimRequest(@NotNull @PositiveOrZero Long expectedVersion,
                               @NotBlank @Size(max = 128) String idempotencyKey) { }
