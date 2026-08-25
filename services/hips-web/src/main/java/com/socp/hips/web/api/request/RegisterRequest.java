package com.socp.hips.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 128) String hostname,
        @NotBlank @Size(max = 64) String ip,
        @Size(max = 128) String os,
        @Size(max = 64) String agentVersion) {
}
