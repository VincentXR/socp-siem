package com.socp.ai.api.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Explicit bounded input for the knowledge/LLM assistant. */
public record AiAskRequest(
        @NotBlank(message = "question is required")
        @Size(max = 8192, message = "question must be at most 8192 characters")
        String question
) {
}
