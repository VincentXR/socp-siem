package com.socp.soar.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateV2ConnectorRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String connectorType,
        @NotBlank @Size(max = 2048) String endpoint,
        @Size(max = 255) String authSecretRef,
        @NotNull List<@Size(max = 255) String> allowedHosts,
        boolean enabled,
        Long rowVersion
) { }
