package com.socp.search.config.api.request;
import com.socp.search.config.domain.SinkTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API input for an output target; id and creation time are server-owned. */
public record SinkTargetRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "(?i)SEARCH|OPENSEARCH|HTTP|KAFKA") String type,
        @NotBlank @Size(max = 2048)
        @Pattern(regexp = "(?i)^(https?|kafka|opensearch)://.*$") String uri,
        @Size(max = 4096) String authToken,
        boolean enabled) {

    public SinkTarget toDomain() {
        return SinkTarget.create(name, type, uri, authToken, enabled);
    }
}
