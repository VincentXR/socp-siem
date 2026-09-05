package com.socp.soar.web.api.request;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Import remains a draft operation; publication still requires validation and approval policy. */
public record ImportV2PlaybookRequest(
        String name,
        String description,
        List<String> tags,
        JsonNode definition,
        JsonNode layout
) { }
