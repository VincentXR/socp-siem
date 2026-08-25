package com.socp.search.config.api.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReferenceEntryRequest(@NotBlank @Size(max = 256) String value) {
}
