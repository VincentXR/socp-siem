package com.socp.ai.api.request;

import jakarta.validation.constraints.Size;

/** Optional target case; blank means create or merge the case from the alert. */
public record AppendInvestigationRequest(@Size(max = 255) String incidentId) {
}
