package com.socp.soar.web.api.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record SaveVersionRequest(@NotNull JsonNode definition, JsonNode layout, Long rowVersion) {
}
