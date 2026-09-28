package com.socp.ai.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Starts a bounded investigation from one tenant-scoped alert fact. */
public record InvestigationRequest(
        @NotBlank @Size(max = 128) String alertId,
        Boolean newVersion,
        @Min(1) Integer baseRevision
) {
    public InvestigationRequest(String alertId) {
        this(alertId, false, null);
    }

    public boolean newVersionOrDefault() {
        return Boolean.TRUE.equals(newVersion);
    }

    /** A retry can only be idempotent when it identifies the revision it observed. */
    @AssertTrue(message = "baseRevision is required when newVersion is true")
    public boolean isRevisionRequestValid() {
        return !newVersionOrDefault() || baseRevision != null;
    }
}
