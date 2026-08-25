package com.socp.soar.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreatePlaybookRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 256) String trigger,
        @NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 512) String> actions,
        boolean enabled
) {
}
