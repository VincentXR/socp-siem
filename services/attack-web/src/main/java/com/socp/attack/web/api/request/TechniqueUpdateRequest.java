package com.socp.attack.web.api.request;
import jakarta.validation.constraints.Size;

public record TechniqueUpdateRequest(
        @Size(max = 256) String name,
        @Size(max = 128) String tactic,
        @Size(max = 1024) String url,
        @Size(max = 4096) String description) {
}
