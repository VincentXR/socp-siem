package com.socp.soc.api.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request body for creating a tenant in the platform directory. */
public record TenantCreateRequest(
        @NotBlank(message = "name is required")
        @Size(max = 128, message = "name must be at most 128 characters")
        String name,

        @NotBlank(message = "code is required")
        @Size(max = 64, message = "code must be at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}",
                message = "code must start with a letter or digit and contain only letters, digits, '_' or '-'")
        String code
) {
}
