package com.socp.detect.web.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Bounded sample sequence; event tenant is always taken from authentication. */
public record RuleDryRunRequest(
        @NotEmpty @Size(max = 20) List<@NotNull @Valid RuleSpecRequest> rules,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid DetectionIngestRequest> events) {
}
