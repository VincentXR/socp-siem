package com.socp.search.config.api;

import com.socp.search.config.domain.DataSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** API input for a data-source type. */
public record DataSourceTypeRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 2000) String description,
        boolean enabled) {

    public DataSourceType toDomain() {
        return DataSourceType.create(code, name, description, enabled);
    }
}
