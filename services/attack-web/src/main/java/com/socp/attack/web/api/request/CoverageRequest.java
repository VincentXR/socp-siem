package com.socp.attack.web.api.request;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CoverageRequest(
        @NotNull @Size(max = 1000) List<@Size(max = 32) String> ruleTechniques) {
}
