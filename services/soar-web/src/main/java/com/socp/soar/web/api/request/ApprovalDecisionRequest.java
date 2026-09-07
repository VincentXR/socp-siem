package com.socp.soar.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Explicit approval decision; actor identity is always taken from the auth context. */
public record ApprovalDecisionRequest(
        @NotBlank @Size(max = 16) String decision,
        @NotBlank @Size(max = 2048) String reason) {
}
