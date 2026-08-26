package com.socp.ai.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Starts a bounded investigation from one tenant-scoped alert fact. */
public record InvestigationRequest(
        @NotBlank @Size(max = 128) String alertId
) {
}
