package com.socp.alert.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OutboxDiscardRequest(
        @NotBlank @Size(max = 512) String reason
) {
}
