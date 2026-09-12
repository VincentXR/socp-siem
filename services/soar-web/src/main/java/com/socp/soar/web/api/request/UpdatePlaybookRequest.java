package com.socp.soar.web.api.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Partial metadata update for a playbook; the immutable version remains separate. */
public record UpdatePlaybookRequest(
        @Size(max = 128) String name,
        @Size(max = 2048) String description,
        @Size(max = 32) List<@Size(max = 64) String> tags,
        @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
        @Min(0) Long rowVersion) {
}
