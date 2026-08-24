package com.socp.soc.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Bounded rule identifiers used for compliance coverage calculation. */
public record CoverageRequest(
        @NotNull @Size(max = 1000) List<@Size(max = 128) String> ruleIds) {
}
