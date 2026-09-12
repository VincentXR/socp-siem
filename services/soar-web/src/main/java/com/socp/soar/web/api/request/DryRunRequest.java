package com.socp.soar.web.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Bounded input envelope for side-effect-free playbook simulation. */
public record DryRunRequest(
        @Valid @Size(max = 8) Map<String, Object> subject,
        @Valid @Size(max = 128) Map<String, Object> inputs) {
}
