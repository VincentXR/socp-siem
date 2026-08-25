package com.socp.gateway.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ServiceTokenRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String service,
        @NotBlank @Size(max = 512) String secret) {
}
