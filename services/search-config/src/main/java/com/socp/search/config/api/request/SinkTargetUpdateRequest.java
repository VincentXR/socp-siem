package com.socp.search.config.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Secret is write-only: KEEP never takes a credential from a redacted read response. */
public record SinkTargetUpdateRequest(@NotNull @Valid SinkTargetRequest target,
                                      @NotNull CredentialAction credentialAction) {
    public enum CredentialAction { KEEP, REPLACE, CLEAR }
}
