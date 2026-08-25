package com.socp.search.config.api.request;
import com.socp.search.config.domain.LogCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** API input for a log category. */
public record LogCategoryRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 32) String defaultSeverity,
        boolean enabled) {

    public LogCategory toDomain() {
        return LogCategory.create(code, name, description, defaultSeverity, enabled);
    }
}
