package com.socp.incident.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CaseNoteRequest(@NotBlank @Size(max = 16000) String content,
                              @NotBlank @Size(max = 128) String idempotencyKey) { }
