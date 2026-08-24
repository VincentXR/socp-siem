package com.socp.asset.web.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bounded request for the asset discovery collection boundary. */
public record AssetCollectionRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]*") String type,
        @Size(max = 64) String ip,
        @Size(max = 128) String os,
        @Size(max = 128) String owner,
        @Size(max = 32) String criticality) {
}
