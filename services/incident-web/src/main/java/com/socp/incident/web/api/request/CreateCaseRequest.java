package com.socp.incident.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCaseRequest(
        @NotBlank @Size(max = 256) String title,
        @Size(max = 256) String entity,
        @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO") @Size(max = 32) String severity,
        @Size(max = 128) String assignee) {
}
