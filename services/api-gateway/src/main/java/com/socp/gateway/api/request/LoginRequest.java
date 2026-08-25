package com.socp.gateway.api.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 128) String username,
        @NotBlank @Size(max = 256) String password) {
}
