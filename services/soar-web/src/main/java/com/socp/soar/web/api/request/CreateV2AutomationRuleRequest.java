package com.socp.soar.web.api.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateV2AutomationRuleRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String triggerType,
        @Min(0) @Max(10000) int priority,
        boolean enabled,
        @NotNull JsonNode conditions,
        @NotNull JsonNode actions,
        JsonNode suppression,
        Long rowVersion
) { }
