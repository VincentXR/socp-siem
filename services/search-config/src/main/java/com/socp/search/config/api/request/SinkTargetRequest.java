package com.socp.search.config.api.request;
import com.socp.search.config.domain.SinkTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API input for an output target; id and creation time are server-owned. */
public record SinkTargetRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "(?i)HTTP|GLS_INGEST") String type,
        @NotBlank @Size(max = 2048)
        @Pattern(regexp = "(?i)^https?://.*$") String uri,
        @Size(max = 4096) String authToken,
        boolean enabled) {

    public void validateHttpOutput() {
        if (!"GLS_INGEST".equalsIgnoreCase(type) && !"HTTP".equalsIgnoreCase(type)) {
            throw com.socp.platform.error.exception.ApiException.badRequest("Only GLS_INGEST and NDJSON HTTP outputs are supported; use a managed adapter for other transports");
        }
        java.net.URI parsed;
        try { parsed = java.net.URI.create(uri); }
        catch (IllegalArgumentException invalid) { throw com.socp.platform.error.exception.ApiException.badRequest("Invalid output URI"); }
        if (!("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getFragment() != null) {
            throw com.socp.platform.error.exception.ApiException.badRequest("Use an HTTP(S) endpoint without embedded credentials or fragment");
        }
    }

    public SinkTarget toDomain() {
        return SinkTarget.create(name, type, uri, authToken, enabled);
    }
}
