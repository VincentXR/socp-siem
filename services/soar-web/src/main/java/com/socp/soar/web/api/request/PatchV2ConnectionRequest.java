package com.socp.soar.web.api.request;

import jakarta.validation.constraints.Size;

import java.util.List;

/** Partial connection update. Secret references are accepted, secret values are not. */
public record PatchV2ConnectionRequest(
        @Size(max = 128) String name,
        @Size(max = 64) String connectorType,
        @Size(max = 2048) String endpoint,
        @Size(max = 255) String authSecretRef,
        @Size(max = 128) List<@Size(max = 255) String> allowedHosts,
        Boolean enabled,
        Long rowVersion) {
}
