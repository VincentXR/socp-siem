package com.socp.threat.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaxiiSyncRequest(
        @NotBlank @Size(max = 128) String feed,
        @NotBlank @Size(max = 1024) String collectionUrl,
        @Size(max = 4096) String authorization,
        Boolean allowHttp) {
}
