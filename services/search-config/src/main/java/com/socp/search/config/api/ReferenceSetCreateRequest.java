package com.socp.search.config.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReferenceSetCreateRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @NotNull @Size(max = 10000) List<@NotBlank @Size(max = 256) String> entries) {
}
