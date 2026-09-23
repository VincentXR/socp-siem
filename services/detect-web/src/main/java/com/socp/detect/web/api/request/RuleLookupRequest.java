package com.socp.detect.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Bounded identity lookup for compliance mappings and selection labels. */
public record RuleLookupRequest(@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 128) String> ids) {}
