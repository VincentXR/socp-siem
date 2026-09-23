package com.socp.alert.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TechniqueCountsRequest(
        @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 32) String> techniqueIds) { }
