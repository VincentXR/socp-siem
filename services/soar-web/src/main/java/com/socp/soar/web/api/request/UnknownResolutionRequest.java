package com.socp.soar.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Evidence-backed operator resolution for an action whose remote outcome is unknown. */
public record UnknownResolutionRequest(
        @NotBlank @Size(max = 32) String resolution,
        @NotBlank @Size(max = 4096) String evidence,
        @NotBlank @Size(max = 2048) String reason) {
}
